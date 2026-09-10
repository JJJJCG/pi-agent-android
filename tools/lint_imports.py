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

# 这些名字太常见，作为普通标识符出现会误报，必须要求前面是 '.' 或 '('
NEED_DOT = {"weight"}

# 这些名字同时也是 MaterialTheme 之类的具名参数（如 `background = ...`），
# 出现 `name =` 形式时要额外确认是不是真的调用了扩展函数。
NAMED_ARG_SAFE = {"background", "border", "padding", "size", "width", "height", "clip"}


def strip_comments_and_strings(text: str) -> str:
    """粗略去掉注释和字符串，避免把注释里的示例代码当成真代码。"""
    # 块注释
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    # 三引号字符串
    text = re.sub(r'""".*?"""', '""', text, flags=re.S)
    # 行注释
    text = re.sub(r"//[^\n]*", "", text)
    # 普通字符串
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    return text


def check_file(path: str) -> list[str]:
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        raw = fh.read()

    body = strip_comments_and_strings(raw)
    imports = "\n".join(
        ln.strip() for ln in raw.splitlines() if ln.lstrip().startswith("import ")
    )

    # 形如 `foo = background` / `background = bar` 的是具名参数，不是扩展函数调用
    named_args = set(re.findall(r"\b([A-Za-z_]\w*)\s*=(?!=)", body))

    problems: list[str] = []
    for member, candidates in CHECKS.items():
        if member in named_args and member in NAMED_ARG_SAFE:
            # 该名字在本文件里至少一处是具名参数写法；只有当完全找不到
            # 「扩展调用」写法时才跳过，避免误报。
            ext_used = re.search(
                r"\.\s*" + re.escape(member) + r"\s*\(|\b" + re.escape(member) + r"\s*\{", body
            )
            if ext_used is None:
                continue

        if member in NEED_DOT:
            used = re.search(r"[.:(]\s*" + re.escape(member) + r"\s*\(", body) is not None
        else:
            if member in ("Column", "Row", "Box", "Spacer", "remember", "size", "width", "height"):
                # 这些既可能是变量名也可能是 composable / 扩展，要求后面跟 '(' 或 '{'
                used = re.search(
                    r"(?<![\w.])" + re.escape(member) + r"\s*[({]|\.\s*" + re.escape(member) + r"\s*\(",
                    body,
                ) is not None
            else:
                used = re.search(r"(?<![\w.])" + re.escape(member) + r"\b", body) is not None

        if not used:
            continue
        if any(c in imports for c in candidates):
            continue
        problems.append(f"{path}: 用到 `{member}` 但没 import（候选: {' / '.join(candidates)}）")

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
