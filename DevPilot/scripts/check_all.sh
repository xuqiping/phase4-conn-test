#!/usr/bin/env bash
# ============================================================================
# check_all.sh —— DevPilot 本地最小质量门（commit 前必跑，全绿才提交）
# 用法：bash scripts/check_all.sh
# ============================================================================
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
FAIL=0

echo "============================================================"
echo " [check_all] DevPilot 最小质量门  $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================================"

# ------------------------------ 桌面端（前端） ------------------------------
if [ -f "PROJECT/desktop/package.json" ]; then
    echo ""
    echo "[Desktop-UI] 类型检查 / 单测 / 构建..."
    ( cd PROJECT/desktop && npx tsc --noEmit )   || FAIL=1
    ( cd PROJECT/desktop && npm run test )       || FAIL=1
    ( cd PROJECT/desktop && npx vite build )     || FAIL=1
fi

# ------------------------------ 桌面端（Rust 内核） ------------------------------
if [ -f "PROJECT/desktop/src-tauri/Cargo.toml" ]; then
    echo ""
    echo "[Desktop-Core] fmt / clippy / test..."
    # 注意：workspace 下必须 --workspace，否则 crates/* 的测试不会执行
    ( cd PROJECT/desktop/src-tauri && cargo fmt --check )                || FAIL=1
    ( cd PROJECT/desktop/src-tauri && cargo clippy --workspace --all-targets -- -D warnings ) || FAIL=1
    ( cd PROJECT/desktop/src-tauri && cargo test --workspace )           || FAIL=1
fi

# ------------------------------ 云端（P02 起启用） ------------------------------
if [ -f "PROJECT/cloud/package.json" ]; then
    echo ""
    echo "[Cloud] 类型检查 / 单测..."
    ( cd PROJECT/cloud && npx tsc --noEmit )  || FAIL=1
    ( cd PROJECT/cloud && npm run lint )      || FAIL=1
    ( cd PROJECT/cloud && npm run test )      || FAIL=1
    ( cd PROJECT/cloud && npm run test:e2e )  || FAIL=1
fi

# ------------------------------ 依赖安全 ------------------------------
# 本地网络受限（GitHub advisory-db 需代理 / npmmirror 无 audit 端点）时降级为 WARN，
# 硬拦截由 CI（GitHub Actions 网络环境）兜底——见 .github/workflows/ci.yml（Step 9 建）。
if [ -f "PROJECT/desktop/src-tauri/Cargo.toml" ]; then
    echo ""
    echo "[Security] 依赖漏洞审计（本地失败仅 WARN，CI 硬拦截）..."
    ( cd PROJECT/desktop/src-tauri && cargo audit )  || echo " [WARN] cargo audit 未执行成功（网络受限），CI 兜底"
    ( cd PROJECT/desktop && npm audit --omit=dev )   || echo " [WARN] npm audit 未执行成功（镜像无 audit 端点），CI 兜底"
    ( cd PROJECT/cloud && npm audit --omit=dev )    || echo " [WARN] 云端 npm audit 未执行成功（镜像无 audit 端点），CI 兜底"
fi

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
    echo "============================================================"
    exit 1
fi
