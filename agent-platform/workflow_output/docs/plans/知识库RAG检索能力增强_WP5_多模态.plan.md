---
description: "C5 多模态检索（图片原生向量主路 + ColPali 实验通道预留）的实现计划（WP5）"
created-date: 2026-09-03
---

# Implementation Plan for WP5：多模态检索

> 上级索引：[知识库RAG检索能力增强.plan.md](知识库RAG检索能力增强.plan.md)｜规格：[§7 C5](../specs/知识库RAG检索能力增强设计.md)

## 坑点预判（WP5 内）

| 坑 | 规避 | 验证 |
|---|---|---|
| **halfvec 维度固定**（HalfVecUtil.DIM）：多模态嵌入模型维度若不同，写库直接炸 | embed 后校验 `vector.length == DIM`（现状已有同款校验）；不符→该库 IMAGE 通道禁用+配置提示（不报错不阻塞索引） | 单测维度不符降级 |
| DashScope 多模态嵌入协议与 OpenAI 兼容 `/embeddings` 不同形（content 数组 vs 单 text 字符串） | Provider 侧探测式组装：模型带多模态标记（llm_models 表 category/扩展列或 config）走 content 数组，否则走 text；探测失败一次后熔断该模型多模态调用 10min | 单测两种协议拼装；契约测试 mock |
| 文本 query 误打 IMAGE 行（或反之） | embeddings 表增 modality 列+**部分索引**（WHERE modality='IMAGE'）；所有检索 SQL 显式按 modality 过滤，向量查询两路分开 | 单测：TEXT query 只命中 TEXT 行 |
| 同 node 双向量行导致行数/计量翻倍误解 | embeddings 主键语义不变（node+model+modality 联合唯一）；指标按 modality 分开打点 | 数据盘点 SQL |
| 原件已清理场景（cleanOriginalFileAfterIndex 可能把原件删了？——实施前先核实该方法真实行为） | Step 0 先核实原件生命周期：若索引后删原件，IMAGE 向量生成必须在删除前；若保留则无问题 | 核实结论入备注 |
| ColPali sidecar 不存在却配置开启 | ColpaliGateway 健康探测失败→通道自动禁用+WARN；不阻塞主检索 | 单测探测失败降级 |

## 实现步骤

- [x] **Step 0：原件生命周期核实（半天调查）**（2026-09-03 核实毕）
  - **目标**：确认图片原件在索引后是否保留（决定 IMAGE 向量生成时机）
  - **动作**：读 `IndexJobWorker.cleanOriginalFileAfterIndex`（:174 调用处）实现：删除条件是什么（全 INDEXED 后删？FILE 型保留？）；若原件会被清理→IMAGE 向量生成必须提前到清理前，或对 IMAGE 文档豁免清理；结论写本 plan 备注
  - **文件**：只读核实
  - **依赖**：无｜**验证**：结论+代码行号落备注 ✅
  - **核实结论**：**无时序风险**。`IndexJobWorker.cleanOriginalFileAfterIndex`（IndexJobWorker.java:248-272）对 **IMAGE/FILE docType 明确跳过清理**（:257-259——原件是回显资产必须保留，仅记 info 日志）；其余 docType 受 `app.files.retain-after-index` 控制（默认 false=清，D5 文件生命周期）。即 IMAGE 文档原件自上传起永久保留至文档删除→IMAGE 向量 job 在索引流程任意阶段读原件 bytes 均安全。附带确认：清理在 DB 事务外、删失败不回滚不阻塞（:265-271），与 IMAGE 无关。

- [x] **Step 1：多模态 embed 协议扩展**（commit 2ffe581f，provider 28+route 8+gateway 25 绿）
  - **目标**：LlmGateway 可传图
  - **动作**：①`OpenAICompatibleProvider` EMBEDDING 行增重载：入参 List<ContentPart>（text/image_url data URI），按模型能力标记组装 content 数组或回退纯 text；②`LlmGateway.embedMultimodal(parts, model, owner)`；③模型多模态能力标记来源（llm_models 现有字段或配置，实施时定，最小改动优先）；④计费归户 owner
  - **文件**：`llm/provider/OpenAICompatibleProvider.java`、`llm/LlmGateway.java`、Test ×2
  - **依赖**：Step 0（不影响协议，可并行）｜**验证**：单测两种协议拼装/mock 契约；真实模型手动验证一次（需人工介入：提供支持图输入的模型）——单测 ✅（Test ×3 实际），真实模型留 Phase4
  - **实现注（偏离）**：①协议分派标记=**端点含 `/multimodal-embedding/`**（既有探测 `usesQwenMultimodalEmbeddingProtocol` 直接复用，零新增配置面——比计划「llm_models 能力列」改动更小；普通端点+图片段立即拒零 HTTP，纯文本段拼接回退）；②image 取值=URL 或裸 Base64（DashScope 协议口径，**不带 data: 前缀**——与计划写的 data URI 不同，按协议实际形态透传）；③熔断落 provider 静态表 provider|model→openUntil（10min，进程级自动恢复）；④响应解析两协议共用抽 `parseEmbedResult` 去重；⑤坑：接口加 List 重载致既有 5 处 mockito `embedWithUsage(any(), any())` 重载歧义编译炸→收紧 `anyString()`

- [x] **Step 2：IMAGE 向量索引双写**（后端全量 2876/2876=2865+Step1 的 3+Step2 的 8，Step1 后未跑全量本轮一并核）
  - **目标**：图片文档入库时追加图片向量行
  - **动作**：①迁移（与 WP3 共用文件 `V1xx__knowledge_rag_context_multimodal.sql`）：`knowledge_embeddings.modality VARCHAR(16) NOT NULL DEFAULT 'TEXT'` + 部分索引；②`IndexJobWorker`：IMAGE 文档索引时在文本向量外追加图片向量 job（原件 bytes→data URI→embedMultimodal；维度校验不符→跳过+标记该库 IMAGE 通道禁用）；③OpenSearch chunk 同步带 modality；④检索侧不变（WP5 Step 3 才消费）
  - **文件**：迁移（WP3 已建则 ALTER 补列）、`entity/KnowledgeEmbedding.java`、`IndexJobWorker.java`、`opensearch/OpenSearchChunkDocument.java`、Test ×2
  - **依赖**：Step 1、Step 0（原件在）｜**验证**：单测——双向量行写入/维度不符跳过/TEXT 存量默认值 ✅；上传图片→embed 数=2（手测留 Phase4）
  - **实现注（偏离）**：①**通道分表替 modality 列**——计划假设 embeddings 单表加 modality 列（node+model+modality 联合唯一），实际 schema=**per-model 分表**（knowledge_embeddings_doubao，node_id UNIQUE+ON CONFLICT(node_id) upsert），加列须动全部分表+冲突键，爆炸半径大；V36 L1 通道先例=**分表**（knowledge_doc_embeddings_doubao，document_id UNIQUE）→ 照做：**V173 新表 `knowledge_image_embeddings_doubao`**（document_id UNIQUE，每 IMAGE 文档 1 行图片向量）+新 `KnowledgeImageEmbeddingMapper`。「表即 modality」= modality 过滤最强形式；②**job 型 `UPSERT_IMAGE`**（doc 级，document_id 锚定、node_id NULL，V36 已放宽列）——非计划「文本向量外追加」同 job 双写，而是独立 doc 级 job（重试/幂等/INDEXED 门闸语义免费复用：markDocIndexedIfDone 计入本 job，文档 INDEXED 需图片向量也完成或作废）；③幂等键=sha256(docId:fileRefHash:versionId:model:pipeline:UPSERT_IMAGE)，**fileRefHash=sha256(fileRef) 原件指纹**（换图→新 job 接管，worker/tx 双层漂移复校同 L1 口径）；④「标记该库 IMAGE 通道禁用」**无独立标记**——失败关闭=voidJob+WARN+无向量行（检索侧自然无图可召回，Step3 落地消费），模型不支持图输入（UnsupportedOperation/IllegalArgument）与维度不符→void 不重试；暂态异常（网络/熔断中 IllegalStateException）→failJob 指数退避；⑤图片向量模型=kb.embeddingModel（协议分派 provider 侧按端点标记，零新增配置面）；⑥bytes 经 `FileStorageService.loadPath(fileId, doc.createdBy, false)` 归属咽喉点读原件→裸 Base64（DashScope 口径）；⑦删除联动：doc 软删（KnowledgeDocumentService.delete）+注入隔离（DocumentParserService QUARANTINED）两处挂 imageEmbeddingMapper.deleteByDocument；⑧OpenSearch chunk modality=node.modality 透传（V171 列），索引模板动态映射自吸纳，检索过滤 Step3 做；⑨对账（findDriftedNodeIds/countOrphanEmbeddings）不含图片表——图片行 doc 级 FK CASCADE+软删显式清双兜底，无 orphan 路径，不扩对账；⑩换 embedding 模型蓝绿重建（enqueueSnapshotJobs，L2 node 级）不含 IMAGE doc 级 job——存量 IMAGE 换模型重生成靠重解析触发（新幂等键接管覆盖），快照式重建合并留 Step3/后续按需；⑪坑：@RequiredArgsConstructor 服务加字段→3 处测试显式构造器断裂（ConfidentialGuard/InjectionScan/MetadataGovernance）逐处补参

- [x] **Step 3：IMAGE 检索通道接入 RRF**（代码+测试全落，后端全量 2879/2879）
  - **目标**：文本 query 可召回图片
  - **动作**：①`RagRetrievalQueryMapper` 增 IMAGE 向量查询（同 TEXT 查询按 modality 过滤）；②候选池新增 IMAGE 通道（通道来源标记 image）；③RRF 融合同参 k=60；④命中 IMAGE 行证据内容=该图既有文本描述（识图/手填/附件描述），引用走 fileRef inline 现状；⑤ATTACHMENT 图片：描述命中（C2）与 IMAGE 向量命中可能同 node→RRF 天然合并，证据去重 by nodeId（现状去重逻辑核实复用）
  - **文件**：`mapper/RagRetrievalQueryMapper.java`、`RagRetrievalService.java`、`service/internal/RrfFusion.java`（通道注册）、Test ×2
  - **依赖**：Step 2｜**验证**：单测——IMAGE 通道召回/混维度库仅 TEXT/同 node 双通道去重 ✅；手测：传产品截图问「这个界面哪里改配置」→图被召回（留 Phase4）
  - **实现注（偏离）**：①查询=`denseRecallImage` 全镜像 L1 SQL（FROM knowledge_image_embeddings_doubao JOIN doc/kb，kb.deleted=0/有效窗/e.embedding_model=kb.embedding_model/可见集+docTypes 过滤，ORDER BY cosine LIMIT maxImage）——「表即 modality」Step2 分表自然免过滤列；返回行/记录**双复用** `RagQueryRow.L1RecallRow`/`L1DocHit`（同为 doc 级锚，零新 DTO）；②通道=**独立 RRF 第三列表**：`multiDenseRecallImage` 多 query half 合并（同 L1 口径），单库/多库/补充检索三路径全接线，abstain 空判从「l0+l1」扩「l0+l1+img」；③RRF 同参 k=60，新权重 `rag.recall.rrf.weight-image-vector=0.8`（同 L1 量级，doc 级语义锚）；`fuseTopDDocs` 升三参（l0/l1/img 序列表非空才建 WeightedList——空通道零影响融合）；④**boost 槽位升级不新增槽**：`docL1Sim` 槽升义为「doc 级锚 sim = max(L1 元数据向量, IMAGE 图片向量)」（`merge(..., Math::max)`）——L2Candidate 记录零波及，rerank 语义自然「doc 级语义锚」；img 命中而 l0 未进 topM 的 doc 走 l1OnlyDocs 同路 fetchL2ChildrenByDoc 拉 L2（识图描述节点即证据，evidence=fileRef inline 现状零改动——fetchL1Metadata 已 JOIN stored_files）；⑤同 node 去重：L0 已进 topM 的 doc 不入 l1OnlyDocs（topDDocIds 差集口径），byNode map 跨通道按 nodeId 去重现状复用（测试③断言 fetchL2ChildrenByDoc 零调用+node 证据恰 1 次）；⑥RrfFusion **不改**（fuseWeighted 通用 List<WeightedList> 天然支持任意通道数，「通道注册」经 props 权重表达，无注册表需求）；⑦测试 +3：imageOnlyDoc 三通道独立救回（L0/L1 全空不 abstain+evidence 含识图描述节点）/imageChannel 空仅 TEXT 库回归门（verify 通道已接线+行为不变）/同 doc 双通道单次拉取不重复计数

- [ ] **Step 4：ColPali 实验通道接口预留**
  - **目标**：sidecar 接口与开关就位（不部署不实现推理）
  - **动作**：①`multimodal/ColpaliGateway.java`：接口定义（pageImage→multi-vector、health）；HTTP 客户端骨架+健康探测失败自动禁用 WARN；②`rag.visual.colpali.enabled` 全局开关+KB 级开关（KB 配置扩展）；③影子对比接线点（V117）注释预留——通道真正接入等 sidecar 部署另立运维项
  - **文件**：`multimodal/ColpaliGateway.java`（新）、`RagConfig`、KB 配置、Test ×1
  - **依赖**：无｜**验证**：单测探测失败禁用；开关关闭零调用

## 联动点（WP5 专属细化）

| 触发 | 联动 | 边界 |
|---|---|---|
| 换 embedding 重建 | IMAGE 向量一并重生成 | 模型不支持图→重建只做 TEXT（提示）；旧 IMAGE 行清理 |
| 模型下线 | 多模态调用失败 | 熔断 10min+该库 IMAGE 通道暂停（检索仍 TEXT 正常）；恢复自动重试 |
| C2 附件图片 | 描述（召回）与 IMAGE 向量（召回）双路 | 同 node 去重一次注入；注入内容走 C2 逻辑 |
| 删除图片文档 | 双向量行随 node 级联清理 | 核实现有删除链路是否含 embeddings 清理（历史一致则不动） |

## 验证汇总

- [ ] 单测新增 ~8
- [ ] 手测剧本：图片上传→双向量；文本 query 召回图片；混维度库降级；不支持图输入模型库仅 TEXT 无报错
- [ ] ColPali：仅接口+开关+探测降级，sidecar 部署明确不在本版
