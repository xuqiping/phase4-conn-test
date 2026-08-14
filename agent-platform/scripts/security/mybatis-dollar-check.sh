#!/usr/bin/env bash
# 安全体系 S1 · SEC-FR-020：MyBatis `${}` 拼接门禁（SQL 注入防回潮）。
# 规则：Mapper XML 与 Mapper 接口（含 @Select 注解）出现 `${` 即红——`#{}` 是唯一合法参数方式。
# 现状审计已全干净（2026-08-09），本门禁防回潮。命中白名单（allowlist，整行精确匹配）除外。
# 用法：bash agent-platform/scripts/security/mybatis-dollar-check.sh   （仓库任意目录可跑，Windows 用 Git Bash）
set -euo pipefail

# 定位 agent-platform 根（脚本在 agent-platform/scripts/security/ 下）；白名单路径须在 cd 前定格为绝对路径
ALLOWLIST="$(cd "$(dirname "$0")" && pwd)/mybatis-dollar-allowlist.txt"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

# 扫描范围：*Mapper.xml + mapper 包下 *.java；排除测试目录
hits="$({ grep -rn '\${' --include='*Mapper.xml' backend/src/main || true; \
          grep -rn '\${' --include='*.java' backend/src/main/java --exclude-dir=test | grep '/mapper/' || true; } \
        | grep -v '^\s*$' || true)"

# 过滤注释：只豁免「${ 出现在注释标记之后」的行（整行剔除曾实证可绕过——活的 ${} 后随行注释即漏拦，
# Phase4 交叉审查发现）。做法：逐行截掉注释尾部再判 ${ 是否仍在代码区。
hits="$(printf '%s\n' "$hits" | while IFS= read -r line; do
  code="${line%%<!--*}"   # XML：截掉行内注释及之后
  code="${code%%//*}"     # Java：截掉行注释及之后
  case "$code" in *'${'*) printf '%s\n' "$line";; esac
done || true)"

# 白名单豁免（确有合法动态表名等场景时，整行贴进 allowlist）
# 注意先剔空行/空白行：grep -F -f 遇空模式匹配一切 → 门禁无声永绿（Phase4 审查发现的脆弱点）。
# Phase4 修正：-x 整行精确匹配——无锚点的子串匹配会让 "Foo.xml:1" 误豁免 "Foo.xml:12/123" 行
if [ -n "$hits" ] && [ -f "$ALLOWLIST" ]; then
  allow="$(grep -v '^[[:space:]]*$' "$ALLOWLIST" || true)"
  if [ -n "$allow" ]; then
    hits="$(printf '%s\n' "$hits" | grep -v -x -F -f <(printf '%s\n' "$allow") || true)"
  fi
fi

if [ -n "$hits" ]; then
  echo "❌ SEC-FR-020 门禁命中：Mapper 中出现 \${} 拼接（SQL 注入风险），请改 #{}："
  printf '%s\n' "$hits"
  echo "如确属合法动态拼接（动态表名等），整行追加到 scripts/security/mybatis-dollar-allowlist.txt 并注明理由。"
  exit 1
fi

echo "✅ SEC-FR-020：Mapper 无 \${} 拼接，SQL 注入门禁通过。"
