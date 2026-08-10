#!/usr/bin/env bash
# 安全体系 S1 · SEC-FR-071：gitleaks 密钥扫描（本地版，与 CI security-gate 同款口径）。
# 背景：2026-08 密钥泄露事件（start-backend-claude.ps1 明文密钥被推上远端）——防第二次。
# 用法：
#   首次全量建 baseline（人工确认既有命中均为误报后执行）：
#     bash agent-platform/scripts/security/gitleaks-scan.sh --baseline
#   日常增量扫描（提交前）：
#     bash agent-platform/scripts/security/gitleaks-scan.sh
# 依赖：gitleaks（Windows: winget install gitleaks / 或 docker 镜像 zricethezav/gitleaks）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REPO_ROOT="$(cd "$ROOT/.." && pwd)"   # git 仓库根（agent-platform 的上一级）
cd "$REPO_ROOT"

BASELINE="agent-platform/.gitleaks-baseline.json"
CONFIG="agent-platform/.gitleaks.toml"

if ! command -v gitleaks >/dev/null 2>&1; then
  echo "❌ 未找到 gitleaks。安装：winget install gitleaks 或参考 https://github.com/gitleaks/gitleaks"
  exit 2
fi

if [ "${1:-}" = "--baseline" ]; then
  # 全量扫 + 生成 baseline（命中项须人工逐条确认为误报/历史串，再提交 baseline 文件）
  gitleaks git --config "$CONFIG" --baseline-path /dev/null --report-path "$BASELINE" --report-format json --exit-code 0 .
  echo "baseline 已写入 $BASELINE —— 请人工确认全部为误报后再提交！"
  exit 0
fi

# 日常：有 baseline 则只拦新增
EXTRA_ARGS=()
if [ -f "$BASELINE" ]; then
  EXTRA_ARGS+=(--baseline-path "$BASELINE")
fi

gitleaks git --config "$CONFIG" "${EXTRA_ARGS[@]}" --redact=50 -v .
echo "✅ SEC-FR-071：gitleaks 未检出新增密钥。"
