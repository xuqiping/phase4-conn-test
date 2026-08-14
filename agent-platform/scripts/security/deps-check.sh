#!/usr/bin/env bash
# 安全体系 S5 · SEC-FR-090~094/133（I5 汇总门禁单入口）：供应链+代码门禁一键汇总。
# 串五个检查，各自独立退出码，最后汇总——任一硬门禁失败则整体非 0。
# 用法：bash agent-platform/scripts/security/deps-check.sh [--skip-nvd]
#   --skip-nvd：跳过 dependency-check（首跑要下几百 MB NVD 库；离线/赶时间时用）
# 产出：
#   后端依赖漏洞报告： agent-platform/backend/target/dependency-check-report.html
#   前端 audit 摘要：  stdout（high 以上才逐条列出）
# 依赖：mvn / npm / gitleaks（缺哪个跳哪个并 WARN，不算失败——本地入口以可用性优先）
set -uo pipefail   # 不用 -e：单检查失败要继续跑完其余并汇总

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SEC="$ROOT/scripts/security"
BACKEND="$ROOT/backend"
FRONTEND="$ROOT/frontend"

SKIP_NVD=0
[ "${1:-}" = "--skip-nvd" ] && SKIP_NVD=1

declare -a NAMES RESULTS NOTES
idx=0
record() { # record <名称> <退出码> <备注>
  NAMES+=("$1"); RESULTS+=("$2"); NOTES+=("$3")
}

echo "========== I5 供应链+代码门禁汇总 =========="
echo "--- [1/5] 后端依赖漏洞（owasp dependency-check，profile deps-check）---"
if [ "$SKIP_NVD" -eq 1 ]; then
  record "backend-deps" 0 "跳过(--skip-nvd)"
elif command -v mvn >/dev/null 2>&1; then
  (cd "$BACKEND" && mvn -q -P deps-check verify -DskipTests)
  record "backend-deps" $? "报告 backend/target/dependency-check-report.html（failBuildOnCVSS=11 不阻断，人工看报告）"
else
  record "backend-deps" 0 "跳过（未安装 mvn）"
fi
echo "  -> ${NAMES[$idx]} exit=${RESULTS[$idx]} ${NOTES[$idx]}"; idx=$((idx+1))

echo "--- [2/5] 前端依赖漏洞（npm audit，生产依赖 high 门禁）---"
if command -v npm >/dev/null 2>&1 && [ -d "$FRONTEND" ]; then
  # 本地常配 npmmirror 镜像，其不实现 audit 端点（404 NOT_IMPLEMENTED）→ 强制官方 registry 只影响本命令。
  # 硬门禁只看生产依赖（--omit=dev）：dev 依赖（vite/esbuild 等）漏洞不进运行时产物，
  # dev-only 的 high 项升级常是 major 破坏性（另立任务），只在全量信息输出里可见，不阻断门禁。
  audit_rc=0
  (cd "$FRONTEND" && npm audit --audit-level=high --omit=dev --registry=https://registry.npmjs.org) || audit_rc=$?
  if [ "$audit_rc" -eq 0 ]; then
    record "frontend-audit" 0 "生产依赖无 high 以上（dev-only 项另看全量 npm audit，WARN 不阻断）"
  elif (cd "$FRONTEND" && npm ping --registry=https://registry.npmjs.org >/dev/null 2>&1); then
    # 官方 registry 可达 → audit 的非 0 是真实发现（high 以上漏洞）或依赖树错，都算红
    record "frontend-audit" "$audit_rc" "生产依赖 high 以上漏洞，见上方列表"
  else
    # 官方 registry 不可达（离线）→ 降级 WARN 跳过，不算门禁红
    record "frontend-audit" 0 "跳过（官方 registry 不可达，离线环境）"
  fi
else
  record "frontend-audit" 0 "跳过（未安装 npm）"
fi
echo "  -> ${NAMES[$idx]} exit=${RESULTS[$idx]} ${NOTES[$idx]:-}"; idx=$((idx+1))

echo "--- [3/5] 密钥扫描（gitleaks，SEC-FR-071）---"
if bash "$SEC/gitleaks-scan.sh" >/dev/null 2>&1; then
  record "gitleaks" 0 "未检出新增密钥"
else
  rc=$?
  if [ "$rc" -eq 2 ]; then
    record "gitleaks" 0 "跳过（未安装 gitleaks，exit=2 环境缺件）"
  else
    record "gitleaks" "$rc" "检出新增密钥——立即轮换，勿先提交"
  fi
fi
echo "  -> ${NAMES[$idx]} exit=${RESULTS[$idx]} ${NOTES[$idx]}"; idx=$((idx+1))

echo "--- [4/5] 前端 HTML 白名单门禁 ---"
if bash "$SEC/frontend-html-gate.sh"; then
  record "frontend-html" 0 "内联事件白名单通过"
else
  record "frontend-html" $? "新增内联 onclick/onerror 等须进 allowlist 复核"
fi
echo "  -> ${NAMES[$idx]} exit=${RESULTS[$idx]}"; idx=$((idx+1))

echo "--- [5/5] MyBatis \${} 注入门禁 ---"
if bash "$SEC/mybatis-dollar-check.sh"; then
  record "mybatis-dollar" 0 "无未放行 \${} 拼接"
else
  record "mybatis-dollar" $? "新增 \${} 拼接须进 allowlist 复核"
fi
echo "  -> ${NAMES[$idx]} exit=${RESULTS[$idx]}"; idx=$((idx+1))

echo "========== 汇总 =========="
overall=0
for i in "${!NAMES[@]}"; do
  mark="✅"; [ "${RESULTS[$i]}" -ne 0 ] && mark="❌" && overall=1
  echo "$mark ${NAMES[$i]} exit=${RESULTS[$i]} ${NOTES[$i]:-}"
done
if [ "$overall" -eq 0 ]; then
  echo "✅ I5 门禁全绿（backend-deps 报告须人工复核 CVE 列表——它不阻断）"
else
  echo "❌ I5 门禁有红项，见上"
fi
exit $overall
