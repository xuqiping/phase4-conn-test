# M3 RAG 记忆设置动态化 · 测试方案

> UI 联动功能(按检索模式显隐 + entities 计数旋钮)→ 需人工/playwright 交互测试。对齐 plan.md「功能联动点清单」。

## 测试环境
- 后端 8080 + sidecar 8090 + 前端 5173 + redis 6379 + pg 5432 全起。
- 账号 admin / admin123,后台「系统设置 → RAG/记忆」tab。
- 前置:V45 迁移已跑(rag.memory.entities-config seed 入库)。

## 联动用例(含正向/反向/半选)
| # | 触发动作 | 预期联动 | 实测 |
|---|---|---|---|
| C1-正-全量 | 检索模式选「全量(LLM_FULL_CONTEXT)」 | 显「记忆标签语言」+「全量记忆阈值」;隐关键词/LLM_KEY/entities 卡片 | ⬜ |
| C1-正-向量 | 切「向量检索(EMBEDDING_VECTOR)」 | 模式专属组全隐(仅常驻 3 项:总开关/处理模式/检索模式) | ⬜ |
| C1-正-混合 | 切「混合(VECTOR_KEYWORD)」 | 显「关键词召回块阈值」+「关键词通道上限」+ entities 词袋计数卡片 | ⬜ |
| C1-正-LLMKEY | 切「LLM_KEY」 | 显「LLM_KEY 粗筛候选数」+「LLM_KEY 精排开关」+ entities 词袋计数卡片 | ⬜ |
| C2-正 | entities 卡片改 totalMax=15 → 刷新页面 | totalMax 仍 15(持久化成功);后端 GET /rag-memory 回显 entitiesConfig.totalMax=15 | ⬜ |
| C2-反 | 切回「全量」→ 改 fullContextThreshold → 保存 | 保存成功;entitiesConfig **不变**(切回非召回模式不提交 entitiesConfig) | ⬜ |
| C2-半 | 混合模式 → hypernymMin 调到 > hypernymMax(如 12 > 10) | NInputNumber :max 联动约束阻止;即使绕过,后端 normalized 互换 min/max 不崩 | ⬜ |
| C2-半2 | totalMax 填 0 或 999 | NInputNumber :min=1/:max=50 约束;绕过则后端 clamp [1,50] | ⬜ |
| C3-正 | 改 totalMax=8 → 对话发新事实(如「我女儿叫小红」) → 查 user_memories.entities | 抽出的 entities 词数 ≤ 8(Java readEntities 截断 + prompt 注入"共 ≤8 个") | ⬜ |
| C3-重抽 | 改完 totalMax → 点「重抽关键词」 → 查老记忆 entities | 老记忆按新 totalMax 重抽(异步,进度日志 memoryReextract) | ⬜ |
| C4-兜底 | 直接 SQL 把 entities-config value 改成非法 JSON → 对话抽记忆 | 不抛异常,走默认值(20/1-3/1-5/5-10),日志 memoryEntitiesConfig 告警 | ⬜ |

## 自动化覆盖
- vue-tsc:净(0 error)。
- mvn compile:绿。
- (可选)单测:MemoryEntitiesConfig.normalized() 边界(min>max 互换 / null 兜底 / totalMax 上调到合计);getMemoryEntitiesConfig 非法 JSON 回退默认。

## 非必测(纯视觉)
- entities 卡片左边框/分组底色:人眼/playwright 截图,非断言逻辑。

## 结论
出口条件:联动清单每条有用例覆盖(含反/半/兜底),自动化 + 冒烟双绿即收尾。
