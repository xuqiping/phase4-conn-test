# M3 RAG 记忆设置动态化 · README

> 个人记忆模块 M3 迭代。合并原待办 #14(按检索模式动态显隐)+ #19(entities 计数用户可配)。
> 受众:B(管理员/运维)。纯设置层,不动对话主链路。

## 做了什么
1. **动态显隐**:后台「记忆设置」tab 顶部选检索模式后,下方只露该模式相关旋钮,无关的全隐藏。
2. **entities 计数可配**:LLM 抽「召回关键词」的数量上限(总数/变体/专名/上位词)从代码硬编码搬进设置页,管理员可调。

## 为什么
- 原 tab 所有旋钮全摊,选「向量」还看到「全量阈值」「LLM_KEY 粗筛」等无关项,体验差。
- entities 抽取量写死(totalMax=20、上位词 5~10),不同用户记忆复杂度不同,想调改不了。

## 怎么实现
- **存储**:单 JSON key `rag.memory.entities-config`,value=`{totalMax,variantMin-Max,properNounMin-Max,hypernymMin-Max}`。默认值 = V38 硬上限(零行为变更,存量无感)。Flyway V45 seed。
- **后端**:`SystemSettingService` 读 JSON(Jackson,损坏回退默认+告警),`MemoryConflictJudge` extract/batchExtractEntities 读配置,EXTRACT_PROMPT/BATCH_ENTITIES_PROMPT 硬编码数字改命名 token(`{TOTAL_MAX}` 等)由 `applyEntitiesConfig` 替换,`readEntities` 截断读 `totalMax`。
- **前端**:`RagMemorySettingsTab.vue` 4 模式旋钮 `:disabled` → `v-if` 按模式显隐;entities 卡片仅 LLM_KEY/VECTOR_KEYWORD 显,min/max NInputNumber 联动约束;切回非召回模式不提交 entitiesConfig(本地 ref 保留)。

## 关键文件
- [plan.md](plan.md) — 设计决策 + 步骤拆分 + 安全/运维/联动清单。
- [Feature Map](../../../docs/feature-map/M3-RAG记忆设置动态化.feature-map.md) — 代码速查 + 表注解。
- [User-Ops](../../../docs/user-ops/M3-RAG记忆设置动态化用户操作手册.md) — 管理员操作步骤。
- [测试方案](../../../docs/测试方案/M3-RAG记忆设置动态化测试方案.md) — 联动用例(含反/半/兜底)。

## 已知限制
- 改 entities-config 只对**新入库**记忆生效;老记忆需手动点「重抽关键词」端点吃新配。
- 默认值 = 旧硬上限 → 不改配置时行为零变更。

## 验证状态
- mvn compile 绿 / vue-tsc 净。
- 联动冒烟用例(测试方案)待人工/playwright 跑(TDD 自动化覆盖 prompt 文本/截断)。
