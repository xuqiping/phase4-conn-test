#!/usr/bin/env bash
# 安全体系 S3 · SEC-FR-054：前端 HTML 注入门禁（XSS 防回潮）。
# 规则：frontend/src 下 *.vue/*.ts 出现 v-html 指令或 innerHTML 赋值即红——
#   LLM/AI 生成内容、知识库证据、用户昵称等全是不可信文本，裸 v-html/innerHTML 直渲 = 存储型 XSS。
#   未来引入 markdown/富文本渲染时必须配 DOMPurify（sanitize 后再渲），并登记白名单+转义测试。
# 现状（2026-08-15）：唯一命中 MentionTextarea.vue 的命令式 innerHTML——上游 mentionLogic.ts
#   escapeHtml 全段转义 + 芯片字面量拼接 + 测试覆盖，登记白名单；其余 0 命中。
# 用法：bash agent-platform/scripts/security/frontend-html-gate.sh   （仓库任意目录可跑，Windows 用 Git Bash）
set -euo pipefail

ALLOWLIST="$(cd "$(dirname "$0")" && pwd)/frontend-html-allowlist.txt"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

# 扫描范围：前端源码 .vue/.ts；排除测试文件（jsdom 里 document.body.innerHTML='' 是清理惯用法，非渲染）
hits="$(grep -rn -E 'v-html|innerHTML[[:space:]]*=' frontend/src \
        --include='*.vue' --include='*.ts' --exclude='*.test.ts' --exclude='*.spec.ts' || true)"

# 过滤注释：截掉行内注释尾部再判（整行豁免曾实证可绕过——同 mybatis 门禁 Phase4 教训）。
# Vue 模板注释 <!--、TS/JS 行注释 //；块注释首行的 /** 保留判定（注释里写 v-html= 也该改说法）。
hits="$(printf '%s\n' "$hits" | while IFS= read -r line; do
  code="${line%%<!--*}"
  code="${code%%//*}"
  case "$code" in
    *v-html*|*innerHTML[[:space:]]*=*) printf '%s\n' "$line" ;;
  esac
done || true)"

# 白名单豁免（确有全段转义 + 测试覆盖的场景，整行贴进 allowlist 并注明理由）
# Phase4 修正：-x 整行精确匹配——无锚点的子串匹配会让 "foo.vue:1" 误豁免 "foo.vue:12/123" 行
if [ -n "$hits" ] && [ -f "$ALLOWLIST" ]; then
  allow="$(grep -v '^[[:space:]]*$' "$ALLOWLIST" | grep -v '^#' || true)"
  if [ -n "$allow" ]; then
    hits="$(printf '%s\n' "$hits" | grep -v -x -F -f <(printf '%s\n' "$allow") || true)"
  fi
fi

if [ -n "$hits" ]; then
  echo "❌ SEC-FR-054 门禁命中：前端出现 v-html / innerHTML 赋值（XSS 风险）："
  printf '%s\n' "$hits"
  echo "LLM/知识库/用户内容一律不可信：改插值渲染；确需 HTML 管道必须 DOMPurify 消毒，"
  echo "并在 scripts/security/frontend-html-allowlist.txt 整行登记 + 补转义测试。"
  exit 1
fi

echo "✅ SEC-FR-054：前端无未登记 v-html / innerHTML 注入点，XSS 门禁通过。"
