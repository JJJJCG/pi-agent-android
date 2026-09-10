#!/usr/bin/env python3
"""
CI 前的第一道体检：找出「用了但没 import」的 Compose / Kotlin 常用成员。

本地没有 Android SDK，没法真编译，所以用这个脚本兜住最容易踩的一类错：
漏 import 扩展函数（clickable / fillMaxWidth / padding / collectAsState...）。
这类错在 Kotlin 里报的是 "Unresolved reference"，一次能报十几条，很浪费时间。

用法:
    python tools/lint_imports.py
退出码 1 表示发现问题。
"""
from __future__ import annotations

import os
import re
import sys

SRC_ROOT = os.path.join("app", "src", "main", "java")

# 成员名 -> 需要存在的完整 import 前缀（任一命中即算通过）。
# 只列「作为扩展函数/属性出现在链式调用里」且容易漏的。
#
# 注：`Modifier.weight(...)` 不在表内 —— 它是 RowScope / ColumnScope 的
# 作用域成员扩展，在 Row{} / Column{} 里直接可用，本来就不需要 import。
CHECKS: dict[str, tuple[str, ...]] = {
    # --- foundation: 修饰符扩展 ---
    "clickable": ("androidx.compose.foundation.clickable",),
    "combinedClickable": ("androidx.compose.foundation.combinedClickable",),
    "background": ("androidx.compose.foundation.background",),
    "border": ("androidx.compose.foundation.border",),
    "fillMaxWidth": ("androidx.compose.foundation.layout.fillMaxWidth",),
    "fillMaxHeight": ("androidx.compose.foundation.layout.fillMaxHeight",),
    "fillMaxSize": ("androidx.compose.foundation.layout.fillMaxSize",),
    "padding": (
        "androidx.compose.foundation.layout.padding",
        "androidx.compose.material3.Scaffold",
    ),
    "width": ("androidx.compose.foundation.layout.width",),
    "height": ("androidx.compose.foundation.layout.height",),
    "size": ("androidx.compose.foundation.layout.size",),
    "verticalScroll": ("androidx.compose.foundation.verticalScroll",),
    "horizontalScroll": ("androidx.compose.foundation.horizontalScroll",),
    "clip": ("androidx.compose.ui.draw.clip",),
    # --- 布局容器 ---
    "Column": ("androidx.compose.foundation.layout.Column",),
    "Row": ("androidx.compose.foundation.layout.Row",),
    "Box": ("androidx.compose.foundation.layout.Box",),
    "Spacer": ("androidx.compose.foundation.layout.Spacer",),
    # --- runtime ---
    "remember": ("androidx.compose.runtime.remember",),
    "LaunchedEffect": ("androidx.compose.runtime.LaunchedEffect",),
    "derivedStateOf": ("androidx.compose.runtime.derivedStateOf",),
    # --- 状态收集 ---
    "collectAsStateWithLifecycle": ("androidx.lifecycle.compose.collectAsStateWithLifecycle",),
    "collectAsState": ("androidx.compose.runtime.collectAsState",),
}

# 这些名字太常见，光出现标识符不算「用过」，必须出现调用形式
# （如 `Modifier.padding(8.dp)`）才检查。
NEED_CALL_FORM = True


def used_as_call(body: str, name: str) -> bool:
    """
    判断 name 在本文件里是否被当作函数 / 扩展函数调用。

    只认「名字后面紧跟 ( 或 {」的出现，且要求它前面要么是 `.`（扩展调用，
    例如 `Modifier.fillMaxWidth()`），要么在本行开头（裸调用，例如缩进后的
    `Column {`）。

    为什么必须这么绕：早先的写法用负向后顾 `(?<![\\w.])` 把「前面有点」的情况
    排除掉了 —— 可 Compose 的扩展函数恰恰几乎全是 `Modifier.xxx()` 这种带点的
    形式，结果全部判成「没用到」，整个检查静默失效。这个坑很隐蔽：
    脚本照样输出「未发现问题」，其实一个真问题都没在查。

    比较前只回看到**本行行首**也很关键：跨行看的话，`Column {` 上一行的
    最后一个字母会被当成「前面的标识符」，于是裸调用全被漏掉。
    """
    for m in re.finditer(r"\b" + re.escape(name) + r"\s*(?=[({])", body):
        line_start = body.rfind("\n", 0, m.start()) + 1
        before = body[line_start : m.start()].rstrip()
        if not before:
            return True
        last = before[-1]
        if last == "." or not (last.isalnum() or last == "_"):
            return True
    return False


def strip_comments_and_strings(text: str) -> str:
    """
    去掉注释和字符串字面量，只留「结构代码」。

    必须按状态机逐字符走，不能靠正则。正则版本踩过一个坑：
    `baseUrl = "http://192.168.31.145:9901"` 里的 `//` 会被当成行注释起点，
    连同该字符串的收尾引号一起删掉，于是后面所有字符串的配对全部错位、
    大段代码被吞 —— 实测同一个文件从 671 行缩成 229 行，漏报随之而来。
    这类工具一旦漏报就毫无意义，所以这里老老实实做词法扫描。
    """
    out: list[str] = []
    i = 0
    n = len(text)

    while i < n:
        ch = text[i]

        # 块注释 /* ... */（含 KDoc）—— 保留换行，好让行号对得上
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                if text[i] == "\n":
                    out.append("\n")
                i += 1
            i += 2
            continue

        # 行注释 // ... —— 只在「不在字符串里」时才会走到这
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue

        # 三引号原始字符串
        if ch == '"' and text.startswith('"""', i):
            i += 3
            while i < n and not text.startswith('"""', i):
                if text[i] == "\n":
                    out.append("\n")
                i += 1
            i += 3
            out.append('""')
            continue

        # 普通字符串
        if ch == '"':
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\\":
                    i += 1
                elif text[i] == "\n":
                    out.append("\n")
                i += 1
            i += 1
            out.append('""')
            continue

        # 字符字面量 —— Kotlin 里 ' 只用于此，不会误伤
        if ch == "'":
            i += 1
            while i < n and text[i] != "'":
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
            out.append("''")
            continue

        out.append(ch)
        i += 1

    return "".join(out)


def check_icons(path: str, raw: str, body: str, imports: str) -> list[str]:
    """
    检查 `Icons.Filled.Xxx` / `Icons.AutoMirrored.Filled.Xxx` 有没有对应 import。

    这类漏 import 特别隐蔽：单个文件的图标名各不相同，不像 fillMaxWidth 那样
    一眼能看出规律。所以单独扫一遍 —— 要求存在一条以该图标名为结尾的 import。
    """
    used = set(re.findall(r"\bIcons\.(?:AutoMirrored\.)?(?:Filled|Outlined|Rounded|Sharp|TwoTone)\.(\w+)", body))
    if not used:
        return []

    imported = set()
    for ln in imports.splitlines():
        if "material.icons" in ln:
            imported.add(ln.rsplit(".", 1)[-1])

    return [
        f"{path}: 用到 `Icons.….{name}` 但没 import（应加 androidx.compose.material.icons.*.filled.{name} 之类）"
        for name in sorted(used - imported)
    ]


def check_file(path: str) -> list[str]:
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        raw = fh.read()

    body = strip_comments_and_strings(raw)
    imports = "\n".join(
        ln.strip() for ln in raw.splitlines() if ln.lstrip().startswith("import ")
    )

    problems: list[str] = []
    for member, candidates in CHECKS.items():
        if not used_as_call(body, member):
            continue
        if any(c in imports for c in candidates):
            continue
        problems.append(f"{path}: 用到 `{member}` 但没 import（候选: {' / '.join(candidates)}）")

    problems.extend(check_icons(path, raw, body, imports))

    return problems


def main() -> int:
    if not os.path.isdir(SRC_ROOT):
        print(f"[!] 找不到源码目录: {SRC_ROOT}（请在 PiAgent 仓库根目录运行）", file=sys.stderr)
        return 2

    all_problems: list[str] = []
    scanned = 0
    for root, _dirs, files in os.walk(SRC_ROOT):
        for name in sorted(files):
            if name.endswith(".kt"):
                scanned += 1
                all_problems.extend(check_file(os.path.join(root, name)))

    print(f"扫描 {scanned} 个 Kotlin 文件")
    if all_problems:
        print(f"\n发现 {len(all_problems)} 处可疑的漏 import：\n")
        for p in all_problems:
            print("  - " + p)
        print("\n这些是启发式判断，可能有误报（比如同名局部变量），请人工确认。")
        return 1

    print("未发现漏 import 的可疑点")
    return 0


if __name__ == "__main__":
    sys.exit(main())
