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

# 过滤注释行（XML <!-- --> 与 Java // 行注释中的示例不拦）
hits="$(printf '%s' "$hits" | grep -v '<!--' | grep -vE ':\s*//' || true)"

# 白名单豁免（确有合法动态表名等场景时，整行贴进 allowlist）
if [ -n "$hits" ] && [ -f "$ALLOWLIST" ]; then
  hits="$(printf '%s\n' "$hits" | grep -v -F -f "$ALLOWLIST" || true)"
fi

if [ -n "$hits" ]; then
  echo "❌ SEC-FR-020 门禁命中：Mapper 中出现 \${} 拼接（SQL 注入风险），请改 #{}："
  printf '%s\n' "$hits"
  echo "如确属合法动态拼接（动态表名等），整行追加到 scripts/security/mybatis-dollar-allowlist.txt 并注明理由。"
  exit 1
fi

echo "✅ SEC-FR-020：Mapper 无 \${} 拼接，SQL 注入门禁通过。"
