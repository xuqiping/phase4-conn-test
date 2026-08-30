#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_docs.py —— workflow_output 文档规则自动校验（零依赖，Python 3.8+）

校验项：
  1. 5000 tokens 硬上限：>4000 预警（WARN），>5000 失败（FAIL）
  2. 失效链接：markdown 相对链接指向不存在的文件（FAIL）
  3. 孤立文档：未被任何其他文档链接的文件（INFO，仅提示）

用法：
  python scripts/check_docs.py            # 全量检查，有 FAIL 则 exit 1
  python scripts/check_docs.py --quiet    # 只输出 WARN/FAIL（供 hooks 调用）

token 估算规则（与工作流约定一致：5000 tokens ≈ 3000~4000 汉字）：
  CJK 字符按 1 token 计，ASCII 字符按 0.25 token 计（4 字符 ≈ 1 token）。
"""
import os
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

WARN_TOKENS = 4000
FAIL_TOKENS = 5000
DOCS_DIR = "workflow_output"
# 入口/索引类文件不计入「孤立文档」
ENTRY_NAMES = {"README.md", "总路由.md", "开发进度总览.md", "变更记录.md", "AGENTS.md"}

LINK_RE = re.compile(r"\[[^\]]*\]\(([^)]+)\)")


def estimate_tokens(text: str) -> int:
    cjk = sum(1 for ch in text if ord(ch) > 0x2E7F)
    other = len(text) - cjk
    return int(cjk + other * 0.25)


def find_md_files(root: str):
    base = os.path.join(root, DOCS_DIR)
    for dirpath, _dirnames, filenames in os.walk(base):
        for name in filenames:
            if name.endswith(".md"):
                yield os.path.join(dirpath, name)


def check_links(path: str, root: str):
    """返回失效链接列表 [(链接, 所在文件)]"""
    broken = []
    with open(path, encoding="utf-8") as f:
        text = f.read()
    for m in LINK_RE.finditer(text):
        target = m.group(1).strip()
        if not target or target.startswith(("http://", "https://", "mailto:", "#")):
            continue
        if "<" in target or ">" in target:
            continue  # 模板占位符链接（如 <功能名>.plan.md），不算失效
        target = target.split("#")[0]
        if not target:
            continue
        if not os.path.exists(os.path.normpath(os.path.join(os.path.dirname(path), target))):
            broken.append((target, path))
    return broken


def main():
    quiet = "--quiet" in sys.argv
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    files = sorted(find_md_files(root))
    if not files:
        print(f"[check_docs] 未找到 {DOCS_DIR}/ 下的 markdown 文件")
        return 0

    warn, fail, info = [], [], []
    linked = set()

    for path in files:
        with open(path, encoding="utf-8") as f:
            text = f.read()
        tokens = estimate_tokens(text)
        rel = os.path.relpath(path, root)
        if tokens > FAIL_TOKENS:
            fail.append(f"超限 {tokens} tokens（>{FAIL_TOKENS}）：{rel} —— 按拆分规则拆子文件+总路由")
        elif tokens > WARN_TOKENS:
            warn.append(f"接近上限 {tokens} tokens（>{WARN_TOKENS}）：{rel} —— 准备收尾拆分")

        is_template = os.path.basename(path).startswith("_模板")
        for target, src in check_links(path, root):
            msg = f"失效链接：{os.path.relpath(src, root)} -> {target}"
            if is_template:
                info.append(msg + "（模板骨架文件，占位所致，仅提示）")
            else:
                fail.append(msg)
        for m in LINK_RE.finditer(text):
            t = m.group(1).strip().split("#")[0]
            if t and not t.startswith(("http://", "https://", "mailto:", "#")) and "<" not in t:
                linked.add(os.path.normcase(os.path.normpath(os.path.join(os.path.dirname(path), t))))

    for path in files:
        if os.path.basename(path) in ENTRY_NAMES:
            continue
        if os.path.normcase(os.path.normpath(path)) not in linked:
            info.append(f"孤立文档（无任何文档链接到它）：{os.path.relpath(path, root)}")

    if not quiet:
        print("=" * 60)
        print(f" [check_docs] 扫描 {len(files)} 个 markdown 文件")
        print("=" * 60)
    for msg in fail:
        print(f" [FAIL] {msg}")
    for msg in warn:
        print(f" [WARN] {msg}")
    if not quiet:
        for msg in info:
            print(f" [INFO] {msg}")
        print("-" * 60)
        print(f" [check_docs] FAIL={len(fail)} WARN={len(warn)} INFO={len(info)}")
        if fail:
            print(" 存在 FAIL —— 修复后再 commit（5000 tokens 是硬上限）")
        else:
            print(" 无 FAIL，可提交")
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())
