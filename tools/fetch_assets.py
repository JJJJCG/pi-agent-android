#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PiAgent 资源获取脚本 —— 把跑端侧语音所需的三样东西拉到位。

  1) native   : libsherpa-onnx-jni.so / libonnxruntime.so  → app/src/main/jniLibs/<abi>/
  2) vad      : silero_vad.onnx                            → app/src/main/assets/
  3) kws      : 关键词模型（encoder/decoder/joiner/tokens）  → app/src/main/assets/kws/

为什么用 Python 而不是 shell：`.tar.bz2` 在 Windows 上经常没有现成解压工具，
而 Python 标准库自带 bz2 + tarfile，且解压时可以直接过滤成员、剥掉顶层目录，
不需要任何 copy / mv 操作。

全程只用标准库，不需要 pip install。

用法（Windows 上走本地代理时）：
    python tools/fetch_assets.py --proxy http://127.0.0.1:20172
    python tools/fetch_assets.py --components vad            # 只拉 silero_vad
    python tools/fetch_assets.py --components native,kws
    python tools/fetch_assets.py --check                     # 只看现状，不下载

代理也可以直接给环境变量：
    set HTTPS_PROXY=http://127.0.0.1:20172
"""

from __future__ import annotations

import argparse
import bz2  # noqa: F401  (tarfile 内部会用到)
import json
import os
import shutil
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app" / "src" / "main"
JNI_DIR = APP / "jniLibs"
ASSETS_DIR = APP / "assets"
KWS_DIR = ASSETS_DIR / "kws"

# 只装 arm64 就够覆盖近几年的真机；模拟器需要 x86_64，按需自行加
DEFAULT_ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")

RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases"
SILERO_URL = f"{RELEASES}/download/asr-models/silero_vad.onnx"
KWS_MODEL = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"

# 与 build.gradle 里的 compileSdk/NDK 无关，纯粹是选上游的预编译产物
FALLBACK_VERSION = "1.12.14"

UA = {"User-Agent": "PiAgent-fetch-assets/1.0"}


# --------------------------------------------------------------------- 工具

def build_opener(proxy: str | None) -> urllib.request.OpenerDirector:
    handlers = []
    if proxy:
        handlers.append(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
    else:
        # 显式尊重环境变量，避免被某些环境下「不走代理」的默认行为坑到
        env_proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") \
            or os.environ.get("HTTP_PROXY") or os.environ.get("http_proxy")
        if env_proxy:
            handlers.append(urllib.request.ProxyHandler({"http": env_proxy, "https": env_proxy}))
    return urllib.request.build_opener(*handlers)


def human(size: float) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            return f"{size:.1f}{unit}"
        size /= 1024
    return f"{size:.1f}GB"


def download(opener, url: str, dest: Path, attempts: int = 4) -> Path:
    """
    带重试和断点续传的下载。

    GitHub release 会 302 到 objects.githubusercontent.com，大文件经过代理时
    偶发 SSL 被掐断 —— 所以失败要能续着下，而不是从头再来。
    """
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")

    last_error: Exception | None = None
    for attempt in range(1, attempts + 1):
        start = tmp.stat().st_size if tmp.exists() else 0
        headers = dict(UA)
        if start:
            headers["Range"] = f"bytes={start}-"

        try:
            req = urllib.request.Request(url, headers=headers)
            with opener.open(req, timeout=180) as resp:
                resuming = start > 0 and getattr(resp, "status", 200) == 206
                if start and not resuming:
                    start = 0  # 服务端不支持续传，老实从头来
                total = int(resp.headers.get("Content-Length") or 0) + start
                got = start
                with open(tmp, "ab" if resuming else "wb") as fh:
                    while True:
                        chunk = resp.read(1 << 16)
                        if not chunk:
                            break
                        fh.write(chunk)
                        got += len(chunk)
                        if total:
                            print(f"\r    {dest.name}  {got * 100 // total:3d}%  "
                                  f"{human(got)}/{human(total)}", end="")
                if total:
                    print()

            if tmp.stat().st_size > 0:
                tmp.replace(dest)
                return dest
            raise IOError("下载到 0 字节")

        except Exception as exc:  # noqa: BLE001
            last_error = exc
            have = human(tmp.stat().st_size) if tmp.exists() else "0B"
            print(f"\n    第 {attempt}/{attempts} 次失败（已下 {have}）：{exc}")
            if attempt < attempts:
                wait = 2 * attempt
                print(f"    {wait}s 后续传重试…")
                time.sleep(wait)

    raise RuntimeError(f"下载失败：{url}（最后一次原因：{last_error}）")


def latest_version(opener) -> str:
    """能问到就用最新的，问不到就用兜底版本，不因为这一步失败而中断。"""
    try:
        req = urllib.request.Request(
            "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/latest",
            headers=UA,
        )
        with opener.open(req, timeout=30) as resp:
            tag = json.load(resp).get("tag_name", "")
        tag = tag.lstrip("v")
        if tag:
            print(f"  上游最新版本：{tag}")
            return tag
    except Exception as exc:  # noqa: BLE001
        print(f"  查询最新版本失败（{exc}），改用兜底版本 {FALLBACK_VERSION}")
    return FALLBACK_VERSION


# -------------------------------------------------------------- 1) native

def fetch_native(opener, abis, workdir: Path) -> None:
    # 已经齐了就别重复下 43MB。想强制重下一次，删掉 jniLibs 下对应 ABI 目录即可。
    already = [abi for abi in abis if (JNI_DIR / abi / "libsherpa-onnx-jni.so").exists()]
    if len(already) == len(abis):
        print(f"[native] 已就位（{', '.join(abis)}），跳过。")
        for abi in abis:
            for so in sorted((JNI_DIR / abi).glob("*.so")):
                print(f"    jniLibs/{abi}/{so.name}  {human(so.stat().st_size)}")
        return

    version = latest_version(opener)
    url = f"{RELEASES}/download/v{version}/sherpa-onnx-v{version}-android.tar.bz2"
    print(f"[native] {url}")
    archive = workdir / f"sherpa-onnx-v{version}-android.tar.bz2"
    try:
        download(opener, url, archive)
    except urllib.error.HTTPError as exc:
        print(f"  下载失败（HTTP {exc.code}）。如果版本号不对，用 --version 指定一个存在的 tag。")
        return

    written = 0
    with tarfile.open(archive, "r:bz2") as tar:
        for member in tar.getmembers():
            if not member.isfile() or not member.name.endswith(".so"):
                continue
            parts = Path(member.name).parts
            abi = next((p for p in parts if p in abis), None)
            if abi is None:
                continue
            target = JNI_DIR / abi / Path(member.name).name
            target.parent.mkdir(parents=True, exist_ok=True)
            src = tar.extractfile(member)
            if src is None:
                continue
            with open(target, "wb") as out:
                shutil.copyfileobj(src, out)
            written += 1
            print(f"  → jniLibs/{abi}/{target.name}  {human(target.stat().st_size)}")

    if written == 0:
        print("  没抽到任何 .so，检查 tar 包结构和 --abis 参数。")
    else:
        print(f"  完成，共 {written} 个 so。")


# ----------------------------------------------------------------- 2) vad

def fetch_vad(opener) -> None:
    target = ASSETS_DIR / "silero_vad.onnx"
    print(f"[vad] {SILERO_URL}")
    if target.exists():
        print(f"  已存在（{human(target.stat().st_size)}），跳过。删掉它可重新下载。")
        return
    download(opener, SILERO_URL, target)
    print(f"  → assets/silero_vad.onnx  {human(target.stat().st_size)}")


# ----------------------------------------------------------------- 3) kws

KWS_WANT_PREFIXES = ("encoder", "decoder", "joiner", "tokens", "keywords")


def kws_ready() -> bool:
    """五个必需文件都在就算齐了（keywords 要排掉 *_raw.txt）。"""
    if not KWS_DIR.exists():
        return False
    names = [f.name.lower() for f in KWS_DIR.iterdir() if f.is_file()]

    def has(prefix: str, suffix: str) -> bool:
        return any(n.startswith(prefix) and n.endswith(suffix) for n in names)

    keywords_ok = any(
        n.startswith("keywords") and n.endswith(".txt") and "raw" not in n
        for n in names
    )
    return (
        has("encoder", ".onnx")
        and has("decoder", ".onnx")
        and has("joiner", ".onnx")
        and has("tokens", ".txt")
        and keywords_ok
    )


def fetch_kws(opener, workdir: Path, keywords: str | None) -> None:
    # 模型已就位时不用碰网络：改唤醒词只需要本地的 tokens.txt
    if kws_ready():
        if keywords:
            print("[kws] 模型已就位，只重新生成唤醒词。")
            write_keywords(workdir, keywords)
        else:
            print("[kws] 模型文件已就位，跳过下载。")
            for f in sorted(KWS_DIR.glob("*")):
                print(f"    assets/kws/{f.name}  {human(f.stat().st_size)}")
            print("  唤醒词沿用现有 keywords.txt（改词加 --keywords，或直接改文件）。")
        return

    url = f"{RELEASES}/download/kws-models/{KWS_MODEL}.tar.bz2"
    print(f"[kws] {url}")
    archive = workdir / f"{KWS_MODEL}.tar.bz2"
    download(opener, url, archive)

    KWS_DIR.mkdir(parents=True, exist_ok=True)
    written = 0
    with tarfile.open(archive, "r:bz2") as tar:
        for member in tar.getmembers():
            if not member.isfile():
                continue
            name = Path(member.name).name
            if not name.startswith(KWS_WANT_PREFIXES):
                continue

            # 已有一份 keywords.txt 就别覆盖它 —— 那可能是你手写/生成好的
            # 唤醒词，被模型自带的演示词盖掉会很难查。
            if name.lower() == "keywords.txt" and (KWS_DIR / "keywords.txt").exists():
                name = "keywords_model.txt"

            target = KWS_DIR / name
            src = tar.extractfile(member)
            if src is None:
                continue
            with open(target, "wb") as out:
                shutil.copyfileobj(src, out)
            written += 1
            print(f"  → assets/kws/{name}  {human(target.stat().st_size)}")

    print(f"  完成，共 {written} 个文件。")
    prune_kws_duplicates()
    write_keywords(workdir, keywords)


def prune_kws_duplicates() -> None:
    """encoder/decoder/joiner 若同时有全精度和 int8，只留全精度那份。"""
    for prefix in ("encoder", "decoder", "joiner"):
        candidates = sorted(KWS_DIR.glob(f"{prefix}*.onnx"))
        if len(candidates) <= 1:
            continue
        keep = next((c for c in candidates if "int8" not in c.name), candidates[0])
        for c in candidates:
            if c != keep:
                c.unlink()
                print(f"  - 删掉多余量化版 assets/kws/{c.name}")


def write_keywords(workdir: Path, keywords: str | None) -> None:
    """
    生成 keywords.txt。

    这一步需要 sherpa-onnx-cli（pip install sherpa-onnx），
    没有的话就保留模型自带的 keywords.txt —— 能跑，但唤醒词是模型自带的那个。
    """
    if not keywords:
        have = KWS_DIR / "keywords.txt"
        if have.exists():
            print("  已保留现有的 keywords.txt（没传 --keywords，不覆盖）：")
            print("    " + have.read_text(encoding="utf-8").strip().replace("\n", "\n    "))
            print("  要换成别的词：--keywords 小派同学,你好派")
        else:
            print("  提示：当前唤醒词是模型自带的演示词（你好军哥 / 小爱同学 之类）。")
            print("        换成自己的：--keywords 小派同学,你好派")
        print()
        print("  也可以直接手改 assets/kws/keywords.txt —— 格式是 ppinyin：")
        print("    一个汉字 = 声母 + 韵母（韵母带声调），例：小派同学 → x iǎo p ài t óng x ué @小派同学")
        print("    可用音素在 assets/kws/tokens.txt 里，照着已有行拼即可。")
        return

    raw = KWS_DIR / "keywords_raw.txt"
    lines = [f"{w} @{w}" for w in (w.strip() for w in keywords.replace("，", ",").split(",")) if w]
    raw.write_text("\n".join(lines) + "\n", encoding="utf-8")

    tokens = next(iter(sorted(KWS_DIR.glob("tokens*.txt"))), None)
    if tokens is None:
        print("  找不到 tokens.txt，无法生成 keywords.txt")
        return

    cli = shutil.which("sherpa-onnx-cli")
    if cli is None:
        print(f"  已写出 {raw.relative_to(ROOT)}，但没找到 sherpa-onnx-cli。")
        print("  装一下再跑一次即可生成 keywords.txt：")
        print("    pip install sherpa-onnx")
        print(f"    sherpa-onnx-cli text2token --tokens {tokens} "
              f"--tokens-type ppinyin {raw} {KWS_DIR / 'keywords.txt'}")
        return

    import subprocess

    out = KWS_DIR / "keywords.txt"
    cmd = [cli, "text2token", "--tokens", str(tokens), "--tokens-type", "ppinyin",
           str(raw), str(out)]
    print("  " + " ".join(cmd))
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode == 0 and out.exists():
        print(f"  → assets/kws/keywords.txt")
        print("    " + out.read_text(encoding="utf-8").strip().replace("\n", "\n    "))
    else:
        print("  生成失败，保留模型自带的 keywords.txt")
        print((result.stderr or result.stdout or "").strip()[:500])


# ----------------------------------------------------------------- 检查

def safe(name: str, fn, failed: list[str]) -> None:
    """单个组件失败不该拖垮整个脚本 —— 剩下的继续拉。"""
    try:
        fn()
    except Exception as exc:  # noqa: BLE001
        failed.append(name)
        print(f"  ✗ {name} 没成功：{exc}")
    print()


def check() -> None:
    print("当前状态：")
    print(f"  {SherpaNative_line()}")

    so_files = sorted(JNI_DIR.rglob("*.so")) if JNI_DIR.exists() else []
    print(f"  jniLibs: {'缺' if not so_files else ''}")
    for f in so_files:
        print(f"    {f.relative_to(ROOT)}  {human(f.stat().st_size)}")

    vad = ASSETS_DIR / "silero_vad.onnx"
    print(f"  assets/silero_vad.onnx: {'有  ' + human(vad.stat().st_size) if vad.exists() else '缺'}")

    kws_files = sorted(KWS_DIR.glob("*")) if KWS_DIR.exists() else []
    print(f"  assets/kws/: {'缺' if not kws_files else ''}")
    for f in kws_files:
        print(f"    {f.name}  {human(f.stat().st_size)}")

    print()
    print("缺东西也不影响编译：App 会退化成「只有文本对话 + 云端语音」。")


def SherpaNative_line() -> str:  # noqa: N802 - 只是为了打印好看
    return "native 库会被打包进 APK，运行时按 jniLibs 里的 ABI 加载"


# ------------------------------------------------------------------ main

def main() -> int:
    parser = argparse.ArgumentParser(description="拉取 PiAgent 的端侧语音资源")
    parser.add_argument("--components", default="native,vad,kws",
                        help="要拉哪些，逗号分隔：native,vad,kws（默认全拉）")
    parser.add_argument("--proxy", default=None,
                        help="形如 http://127.0.0.1:20172；不填则读 HTTPS_PROXY 环境变量")
    parser.add_argument("--abis", default=",".join(DEFAULT_ABIS),
                        help=f"要抽哪些 ABI，默认 {','.join(DEFAULT_ABIS)}")
    parser.add_argument("--version", default=None,
                        help="指定 sherpa-onnx 版本 tag（默认取最新 release）")
    parser.add_argument("--keywords", default=None,
                        help="唤醒词，如 小派同学,你好派；需要本机有 sherpa-onnx-cli")
    parser.add_argument("--check", action="store_true", help="只报告现状，不下载")
    args = parser.parse_args()

    if args.check:
        check()
        return 0

    global FALLBACK_VERSION
    if args.version:
        FALLBACK_VERSION = args.version.lstrip("v")

    wanted = {c.strip() for c in args.components.split(",") if c.strip()}
    abis = tuple(a.strip() for a in args.abis.split(",") if a.strip())

    opener = build_opener(args.proxy)
    if args.proxy:
        print(f"使用代理：{args.proxy}")
    print(f"工程根目录：{ROOT}")
    print()

    workdir = Path(tempfile.mkdtemp(prefix="piagent-assets-"))
    failed: list[str] = []
    try:
        if "native" in wanted:
            safe("native", lambda: fetch_native(opener, abis, workdir), failed)
        if "vad" in wanted:
            safe("vad", lambda: fetch_vad(opener), failed)
        if "kws" in wanted:
            safe("kws", lambda: fetch_kws(opener, workdir, args.keywords), failed)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    check()
    print()
    if failed:
        print(f"有 {len(failed)} 项没成功：{', '.join(failed)}")
        print("直接重跑同一条命令即可 —— 已下好的部分会断点续传。")
        return 1
    print("下一步：Android Studio 里 Sync + Run。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
