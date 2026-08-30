#!/usr/bin/env bash
# ============================================================================
# check_all.sh —— 本地最小质量门（一条命令跑完全部自动检查）
#
# 硬规则：commit 前必跑本脚本，全绿（exit code 0）才允许提交。
# 用法：  bash scripts/check_all.sh
#
# 本项目技术栈：Tauri 2（前端 Vue3 在根目录 + Rust 后端在 src-tauri/）
# 维护：新增检查（如 E2E、cargo test）按同样结构追加。
# ============================================================================
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
FAIL=0

echo "============================================================"
echo " [check_all] 最小质量门开始  $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================================"

# ------------------------------ 前端（Vue 3，根目录）------------------------------
if [ -f "package.json" ]; then
    echo ""
    echo "[Frontend] 前端项目检测到（根目录），开始检查..."
    echo "> npx vue-tsc --noEmit"
    npx vue-tsc --noEmit || FAIL=1
    # 注：本项目 package.json 无 lint/test 脚本；如后续加入，在此追加：
    #   npm run lint || FAIL=1
    #   npm run test -- --run || FAIL=1
else
    echo ""
    echo "[Frontend] 未检测到 package.json，跳过"
fi

# ------------------------------ 后端（Rust，src-tauri/）------------------------------
if [ -f "src-tauri/Cargo.toml" ]; then
    echo ""
    echo "[Backend] Rust/Tauri 项目检测到（src-tauri/），开始检查..."
    echo "> cargo check (src-tauri)"
    ( cd src-tauri && cargo check ) || FAIL=1
    # 注：当前项目无测试用例；如后续加入单元测试，取消下行注释：
    # ( cd src-tauri && cargo test ) || FAIL=1
else
    echo ""
    echo "[Backend] 未检测到 src-tauri/Cargo.toml，跳过"
fi

# ------------------------------ 文档规则 ------------------------------
if [ -d "workflow_output" ]; then
    echo ""
    echo "[Docs] 文档规则检查（tokens 上限 / 失效链接）..."
    echo "> python scripts/check_docs.py --quiet"
    python scripts/check_docs.py --quiet || FAIL=1
fi

echo ""
echo "============================================================"
if [ "$FAIL" -eq 0 ]; then
    echo " [check_all] 全绿 PASS —— 可以 commit"
    echo "============================================================"
    exit 0
else
    echo " [check_all] 存在失败项 FAIL —— 修复前禁止 commit"
    echo " 提示：把失败日志贴回给 AI，让它修（铁律 #9）"
    echo "============================================================"
    exit 1
fi
