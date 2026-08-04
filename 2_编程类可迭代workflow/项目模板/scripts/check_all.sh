#!/usr/bin/env bash
# ============================================================================
# check_all.sh —— 本地最小质量门（一条命令跑完全部自动检查）
#
# 硬规则：commit 前必跑本脚本，全绿（exit code 0）才允许提交。
# 用法：  bash scripts/check_all.sh
# 维护：  按本项目实际技术栈调整各段命令；项目没有的段整段删除（不要留"跳过"），
#         新增检查（如桌面端、E2E）按同样结构追加。
# ============================================================================
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
FAIL=0

echo "============================================================"
echo " [check_all] 最小质量门开始  $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================================"

# ------------------------------ 后端 ------------------------------
if [ -f "PROJECT/backend/pom.xml" ]; then
    echo ""
    echo "[Backend] Maven 项目检测到，开始检查..."
    ( cd PROJECT/backend && mvn -q compile ) || FAIL=1
    ( cd PROJECT/backend && mvn -q test )    || FAIL=1
elif [ -f "PROJECT/backend/build.gradle" ]; then
    echo ""
    echo "[Backend] Gradle 项目检测到，开始检查..."
    ( cd PROJECT/backend && ./gradlew test ) || FAIL=1
else
    echo ""
    echo "[Backend] 未检测到后端项目，跳过（若项目有后端，请在此补充对应命令）"
fi

# ------------------------------ 前端 ------------------------------
if [ -f "PROJECT/frontend/package.json" ]; then
    echo ""
    echo "[Frontend] 前端项目检测到，开始检查..."
    ( cd PROJECT/frontend && npx tsc --noEmit )        || FAIL=1
    ( cd PROJECT/frontend && npm run lint )            || FAIL=1
    ( cd PROJECT/frontend && npm run test -- --run )   || FAIL=1
else
    echo ""
    echo "[Frontend] 未检测到前端项目，跳过（若项目有前端，请在此补充对应命令）"
fi

# ------------------------------ 桌面端（若有，解除注释并调整） ------------------------------
# if [ -f "PROJECT/desktop/package.json" ]; then
#     echo ""
#     echo "[Desktop] 桌面端项目检测到，开始检查..."
#     ( cd PROJECT/desktop && npm run lint )          || FAIL=1
#     ( cd PROJECT/desktop && npm run test -- --run ) || FAIL=1
# fi

# ------------------------------ 文档规则 ------------------------------
if [ -d "workflow_output" ]; then
    echo ""
    echo "[Docs] 文档规则检查（tokens 上限 / 失效链接）..."
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
