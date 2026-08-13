# 企业级精准知识库 RAG

## 用户地图

- 普通用户：选择知识库提问，获得带精确引用和置信状态的多证据答案；可提交固定分类反馈。
- 知识库管理员：治理文档版本、权限、解析与索引；配置显式模型；运行评测并分析退化样本。
- 平台管理员：维护 OpenSearch、发布门禁、稳定灰度和回滚，查看低基数指标与审计链路。

## 技术说明

系统以 PostgreSQL 为权威源，OpenSearch 为可重建检索副本；检索先做 tenant/KB/ACL/status/version 强制过滤，再进行多通道召回、RRF、可选 LLM/Rerank 重排、动态证据预算、补检索、PG 复核、引用校验和六态置信输出。

模型不硬编码：Query/Ranking 模型由管理员或知识库显式选择；没有可用模型就明确失败。专用 Rerank 保留 SPI，未接入时 fail-closed。

发布采用黄金集、Champion/Challenger、影子检索和 5/20/50/100 稳定灰度。反馈只进入待审核队列，禁止直接改变排序。关键调用与平台审计/Java 日志通过 trace 关联，但日志不记录完整 Prompt、Query、Chunk、密钥或完整模型输出。

P5 已完成：评测 Dataset/Case/Run/Result 真实持久化，发布门禁读取真实指标；Shadow 已接入生产稳定采样并提供 tenant 隔离查询；灰度回滚可同时恢复 Ranking 配置、OpenSearch read alias 和答案缓存。当前停留在 Phase 3，未执行浏览器或真实外部模型验收。

## 文档入口

- 规格：`workflow_output/docs/specs/企业级精准知识库RAG-总览.md`
- 计划：`workflow_output/docs/plans/企业级精准知识库RAG.plan.md`
- Feature Map：`workflow_output/docs/feature-map/企业级精准知识库RAG.feature-map.md`
- User-Ops：`workflow_output/docs/user-ops/企业级精准知识库RAG用户操作手册.md`
- 测试方案：`workflow_output/docs/测试方案/企业级精准知识库RAG测试方案.md`
- 旧文档差异：`workflow_output/docs/specs/企业级精准知识库RAG-旧文档差异清单.md`
