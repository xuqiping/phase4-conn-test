#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
霞鹜文楷（LXGW WenKai）子集化脚本 — TECH-0001-D1 / ASSUMP-0001 验证点
用法：
  1. 下载字体源文件（不入库，放本地缓存）：
     https://github.com/lxgw/LxgwWenKai/releases/latest → LXGWWenKai-Regular.ttf
     放到 D:/dev-cache/fonts/LXGWWenKai-Regular.ttf（或用 --src 指定）
  2. pip install fonttools brotli
  3. python scripts/subset-font.py
产物（生成目录，禁止手改）：
  public/fonts/lxgw-wenkai-display.woff2
  public/fonts/lxgw-wenkai-display.css
  public/fonts/OFL-LXGW-WenKai.txt（若源目录附带许可则复制）
体积红线：≤500KB（ART-QA-0001）；超线则缩减 font-glyphs.txt 用字。
"""

import argparse
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_SRC = r"D:\dev-cache\fonts\LXGWWenKai-Regular.ttf"
OUT_DIR = os.path.join(ROOT, "public", "fonts")
GLYPHS = os.path.join(ROOT, "scripts", "font-glyphs.txt")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=DEFAULT_SRC, help="LXGWWenKai-Regular.ttf 路径")
    args = ap.parse_args()

    if not os.path.isfile(args.src):
        print(f"[FAIL] 字体源文件不存在: {args.src}")
        print("       请从 https://github.com/lxgw/LxgwWenKai/releases/latest 下载 LXGWWenKai-Regular.ttf")
        return 1

    os.makedirs(OUT_DIR, exist_ok=True)
    out_woff2 = os.path.join(OUT_DIR, "lxgw-wenkai-display.woff2")

    cmd = [
        sys.executable, "-m", "fontTools.subset", args.src,
        f"--text-file={GLYPHS}",
        f"--output-file={out_woff2}",
        "--flavor=woff2",
        "--layout-features=*",
        "--no-hinting",
        "--desubroutinize",
    ]
    print("[RUN]", " ".join(cmd))
    subprocess.run(cmd, check=True)

    size_kb = os.path.getsize(out_woff2) // 1024
    print(f"[OK] {out_woff2}  {size_kb} KB")
    if size_kb > 500:
        print("[WARN] 超过 500KB 红线（ART-QA-0001），请缩减 scripts/font-glyphs.txt 用字集")

    css = (
        "/* 生成物：subset-font.py 自动生成，禁止手改 */\n"
        "@font-face {\n"
        "  font-family: 'LXGW WenKai';\n"
        "  src: url('/fonts/lxgw-wenkai-display.woff2') format('woff2');\n"
        "  font-display: swap;\n"
        "  font-weight: 400;\n"
        "  font-style: normal;\n"
        "}\n"
    )
    with open(os.path.join(OUT_DIR, "lxgw-wenkai-display.css"), "w", encoding="utf-8") as f:
        f.write(css)
    print("[OK] lxgw-wenkai-display.css")

    # 许可副本（若字体源同目录有 OFL.txt / License 文件则复制）
    for cand in ("OFL.txt", "LICENSE", "License.txt"):
        src_lic = os.path.join(os.path.dirname(args.src), cand)
        if os.path.isfile(src_lic):
            shutil.copy(src_lic, os.path.join(OUT_DIR, "OFL-LXGW-WenKai.txt"))
            print(f"[OK] 许可副本 <- {src_lic}")
            break
    else:
        print("[WARN] 未找到 OFL 许可文件，请手动放置 public/fonts/OFL-LXGW-WenKai.txt（ART-QA 版权基线）")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
