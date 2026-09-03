# 知识库RAG检索能力增强 · Feature Map

> 代码速查：文件位置 + 作用 + 大白话。7 能力（C1-C7）六工作包（WP1-6）。后端包根 `com.superprogrammer.knowledge`，前端 `src/components/knowledge/`。

## 后端

| 文件 | 作用 | 大白话 |
|---|---|---|
| relation/`DocumentRelationService` | C1 关系边 CRUD+建议采纳 | 文档间的「详见」连线管理台；建边时防自环/跨库/语义等价重复 |
| relation/`RelationSuggestionService`/`Worker` | C1 关系自动建议 | 后台翻文档找「这俩很像」的线索，攒给人一键采纳 |
| relation/`RelationGraphPostProcessor` | C1 召回扩展 | 搜到 A 就把和 A 连线的 B 也捎上——像超市把啤酒摆尿布旁边 |
| attachment/`AttachmentContentInjector` | C2 附件内容注入 | 附件被选中后现场把原文（或图的 AI 描述）塞进给大模型的材料 |
| attachment/`AttachmentVisionCache` | C2 图片描述缓存 | 同一张图别反复花钱问 VLM「这是什么」，答案存起来复用 |
| query/`LlmQueryPlanner`·`QueryPlan` | C3 查询规划 | 把用户一句口语拆成「主检索词+改写变体+该搜几轮」的作战计划 |
| retrieval/`IterativeRetrievalOrchestrator` | C3 有界循环 | 检索→看材料够不够→不够换个问法再搜，最多两三轮，够就立刻停 |
| context/`CoverageVerifier` | C3 覆盖判定 | 材料审稿员：对照问题检查证据有没有缺口，有才准续轮 |
| context/`NeighborExpander` | C3 邻居扩展 | 把命中切片的前后同块捎上，答案不断章取义 |
| service/`LlmContextualizer` | C4 上下文定位语 | 每个切片入库存时带一句「这是谁的第几节」，指代词不再失忆 |
| service/`ContextualRebuildService`/`TxService` | C4 存量重建 | 老库补定位语的工程队——job 排队限速，先给用户看成本预估 |
| entity/`KnowledgeNode`(+V171 列) | C4 存储 | 节点表加「定位语」列，检索原文时拼在切片前 |
| multimodal/`ColpaliGateway` | C5 ColPali 通道 | 视觉检索的实验通道：总开关+库开关+探活三重闸，坏了自动禁用 |
| service/internal/`RrfFusion` | C5/C1 融合 | 文本向量、关键词、图片三路榜单合成一张——RRF 像三评委各打分取加权名次 |
| V173 `knowledge_image_embeddings` | C5 图片向量表 | 图片向量与文本向量分家（halfvec），互不挤占 |
| connector/`KnowledgeConnectorSpi` | C6 SPI | 「数据源长啥样」的统一契约：列出文件/下载/关闭 |
| connector/`UrlSiteConnector`·`WebDavConnector`·`S3Connector` | C6 三实现 | 爬站点（同域 BFS）/网盘目录协议（PROPFIND）/S3 兼容桶（AWS SDK） |
| connector/`SafeHttpFetch` | C6 HTTP 底座 | 重定向手动跟（每跳重验目的地）、错误只报状态号——防 SSRF 与信息回显 |
| connector/`FetchLimiter` | C6 限速 | 1 req/s+200MB 闸——别把人家站点/网盘打爆 |
| connector/`ConnectorFactory` | C6 装配 | 按类型拼装连接器，生产态注入 SsrfGuard（防内网探测） |
| connector/`ConnectorSyncWorker` | C6 定时同步 | 60s 扫到期连接器→etag 差分四态（新增/变更/复活/重试），单轮 ≤50 动作 |
| connector/`ConnectorSyncTxService` | C6 记账事务 | 行锁认领（SKIP LOCKED 防多实例撞车）、账本读写、隔离/复位 |
| service/`KnowledgeConnectorService` | C6 CRUD+启停 | 凭证 AES-GCM 加密落库只写不读；KB 治理级权限 |
| global/`GlobalAnswerStrategy` | C7 跨库问答 | 每个库各自检索回答，再汇总成总答案（map-reduce） |
| global/`KbSummaryWorker` | C7 库摘要 | 给每个库养一份「这库讲什么」的简介，供汇总时取舍 |
| service/internal/`CitationChecker` | C7 引用核验 | 汇总答案里的引用逐条验真，防张冠李戴 |
| service/internal/`AnswerCacheService` | 缓存 | evidence hash 含关系边+附件内容——知识变了缓存自动失效 |
| trace/`RagTraceContext`（扩展） | 可观测 | 各轮/各通道耗时与命中数全落 trace，检索调试面板可查 |
| retrieval/`ShadowRetrievalService` 等 6 文件 | 影子对比 | 新旧检索同跑落表比对（Step4 真实跑批挂起等黄金集） |

## 前端

| 文件 | 作用 | 大白话 |
|---|---|---|
| `DocumentRelationPanel`/`Modal`/`SuggestionModal` | C1 关系管理 | 建边/删边/采纳建议的操作台 |
| `DocumentManager`（扩展） | C2/C6 入口 | 附件文档 📎、连接器文档 🔌 徽标；直传/版本等 |
| `RetrievalDebugPanel`（扩展） | 调试 | 看 C3 每轮检索与覆盖判定、C7 全局答案分解 |
| `IndexOperationsPanel`（扩展） | C4 重建入口 | 存量库「重建上下文嵌入」按钮+成本预估确认框 |
| `RagAskPanel`（扩展） | C7 | 跨库全局问答开关与引用展示 |
| `ConnectorPanel` | C6 面板 | 列表（状态/摘要/ERROR 红标×N）+新建（类型切换表单+cron 预设）+启停/立即同步/删除 |
| `KnowledgeView`（扩展） | C6 挂载 | KB 行「连接器」按钮（owner/admin）→抽屉 |
| `api/knowledge.ts`（扩展） | API | 关系/附件/连接器/全局问答接口与类型 |

## 表与 SQL 注解（Flyway，已执行不可改）

| 版本 | 表/列 | 用途（生活比喻） |
|---|---|---|
| V170 | `knowledge_document_relations` | 关系边账本——文档间「亲友关系登记簿」；语义等价边去重 |
| V171 | `knowledge_nodes`+上下文列+多模态列 | 节点表添「定位语」（切片的随身身份牌）与图片向量挂点 |
| V172 | `knowledge_base_summaries` | 每库一份「图书馆分区导览牌」，全局问答靠它认识每个库 |
| V173 | `knowledge_image_embeddings` | 图片向量独立仓库（halfvec 半精度省一半空间） |
| V174 | `knowledge_bases.colpali_enabled` | ColPali 实验通道每库独立开关 |
| V175 | `knowledge_connectors`+`knowledge_connector_docs` | 订阅合同（连接器配置，凭证密文）+签收登记本（每文件 etag 账）；账本 uq(connector,external_id) 防重复签收，FK CASCADE 删合同清账本 |
| V176 | `sync_error_streak` 列 | 送奶工连败计数：连续 3 轮没送到→红灯停送（ERROR 停调度），人工「立即同步」可复位 |

## 关键调用链

- 问答：`RagAskPanel` → `/rag/ask` → `RagRetrievalService` → planner→router→orchestrator（循环+覆盖判定）→relation 扩展→attachment 注入→answer 合成（缓存 keyed by evidence hash）。
- 同步：`@Scheduled pollOnce` → 认领（SKIP LOCKED）→ SPI.list → etag 差分 → 新增走 `KnowledgeDocumentService.upload` 全管线 / 变更走 createVersion→activate→resetDocForResync → 摘要落库（脱敏）。
- 全局：`GlobalAnswerStrategy` → 逐库 retrieve+answer → `CitationChecker` → 汇总。
