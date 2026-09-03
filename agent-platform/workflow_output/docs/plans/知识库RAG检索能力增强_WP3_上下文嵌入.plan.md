---
description: "C4 上下文嵌入升级（规则版前缀 → LLM 定位表）的实现计划（WP3）"
created-date: 2026-09-03
---

# Implementation Plan for WP3：上下文嵌入升级

> 上级索引：[知识库RAG检索能力增强.plan.md](知识库RAG检索能力增强.plan.md)｜规格：[§6 C4](../specs/知识库RAG检索能力增强设计.md)

## 坑点预判（WP3 内）

| 坑 | 规避 | 验证 |
|---|---|---|
| 定位表 LLM 输出 JSON 烂尾/缺 chunk 项（glm-5.1 既有教训：14x 文档记录过 maxTokens 截断） | 输出预算给足（20 chunk×50 字 ≤1500 token，maxTokens≥2000）+ 解析容错：缺失项该 chunk 降级纯规则前缀（**逐 chunk 独立降级**，非整文档失败） | 单测：截断 JSON→部分 chunk 有定位语部分无 |
| contextHash 语义变更导致存量 job 全部 void 风暴 | contextHash 公式升级（=sha256(规则前缀+定位语+原文)）只对**新生成**的 job 生效；存量 embedding 行不动照常检索；仅重建时新 hash 接管 | 单测：升级后不触发存量重嵌 |
| 重建入口一次全库 → LLM/嵌入成本风暴 | 单库粒度；确认框带成本预估（chunk 数×每次调用 token 估算）；重建 job 入队走既有索引队列背压；支持中断（已完成的保留） | 手测：确认框数字正确；中断后可续 |
| 定位语污染权限信息（Contextualizer 现有约束「不含权限治理信息」） | 提示词硬约束+输出过滤：定位语中命中文档所有者/授权字样的词替换为标题 | 单测注入用例 |
| OpenSearch 双写不一致（pipeline_version/定位语文本） | chunk 文档同步带 contextual_text 与 pipeline_version=CTX_LLM_V1；别名切换前的旧索引不受影响 | 集成：写后读回断言 |
| LLM 定位语与 L1 摘要重复调用浪费 | 与 SUMMARIZING 阶段同批（同一次管线停留），输入复用 L1 结果，不做第二次文档级理解 | 代码评审确认单次调用 |

## 实现步骤

- [ ] **Step 1：定位表生成（Contextualizer 升级）**
  - **目标**：每文档 1 次 LLM 调用产出全 chunk 定位语
  - **动作**：①`Contextualizer` 增 `contextualizeWithLlm(doc, version, nodes, l1Summary)`：提示词=输入 L1 摘要+chunk 清单（id/标题/首行），输出 JSON 数组 [{nodeId, locator ≤50字}]；maxTokens≥2000、超时与 L1 同口径；解析容错逐 chunk 降级；②定位语不含权限信息过滤；③开关 `rag.contextual.llm.enabled` 默认 true，关=纯规则版现状；④计费归户 docOwner
  - **文件**：`service/Contextualizer.java`、`RagConfig`、`LlmGateway`（调用）、Test ×2
  - **依赖**：无（L1 链路现状已有）｜**验证**：单测——正常生成/JSON 烂尾部分降级/权限词过滤/开关关闭走纯规则

- [ ] **Step 2：存储与索引接线**
  - **目标**：定位语落库、embed 文本升级、双写一致
  - **动作**：①迁移 `V1xx__knowledge_rag_context_multimodal.sql`：`knowledge_nodes.contextual_text TEXT NULL`（C5 的 modality 列同文件，WP5 用）；②SUMMARIZING 完成后写回 nodes.contextual_text；③`IndexJobWorker` embed 文本=规则前缀+定位语+原文，contextHash=新公式（含定位语），pipeline_version=CTX_LLM_V1；④OpenSearch chunk 文档带 contextual_text
  - **文件**：迁移 ×1、`entity/KnowledgeNode.java`、`service/Contextualizer.java`、`IndexJobWorker.java`、`opensearch/OpenSearchChunkDocument.java`、Test ×2
  - **依赖**：Step 1｜**验证**：单测——embed 文本拼接顺序、hash 新公式、存量行不受影响；集成：新文档索引后 OpenSearch 读回

- [ ] **Step 3：存量可选重建入口**
  - **目标**：库 owner 可选为存量文档应用 LLM 上下文增强
  - **动作**：①KB 索引运维入口（既有重建路径）加「应用 LLM 上下文增强」选项：仅解析型文档（ATTACHMENT 豁免，规格 §6.3）、成本预估（chunk 数估算显示）、确认后生成全量重嵌 job（复用换 embedding 重建机制）；②中断可续（已完成 job 不重复）
  - **文件**：既有索引运维 Service/Controller（定位实施时）、`IndexJobTxService.java`、前端索引运维组件、Test ×1
  - **依赖**：Step 2｜**验证**：单测——重建 job 全量生成/ATTACHMENT 跳过/中断续传；手测：小库重建→检索调试 embed 文本含定位语

- [ ] **Step 4：影子对比验证增益**
  - **目标**：用数据证明 C4 有效再转正（不盲上）
  - **动作**：①选 1-2 个真实库开影子对比（V117 机制）：新旧（规则版 vs LLM 版）索引并行检索跑黄金集；②Recall/MRR 对比报告；③增益不达标（Recall 提升 <2pp）→默认开关改 false 并记录
  - **文件**：`retrieval/ShadowRetrievalService.java`（接入点）、评估跑批脚本/入口、报告落 `workflow_output/开发进度/`
  - **依赖**：Step 2-3｜**需人工介入**：选库+黄金集用例确认｜**验证**：对比报告产出

## 联动点（WP3 专属细化）

| 触发 | 联动 | 边界 |
|---|---|---|
| rag.contextual.llm.enabled 关 | 新索引回纯规则 | 存量 LLM 版索引照常检索（hash 已匹配）；重开→新文档重新生效 |
| 文档新版本 | 定位表重生成 | 旧版本 contextual_text 随版本快照不回写；ATTACHMENT 无此环节 |
| 换 embedding 重建 | 定位语保留复用 | 只重嵌不重生成定位语（省一次 LLM）；「应用 LLM 增强」选项单独勾选才重生成 |

## 验证汇总

- [ ] 单测新增 ~8
- [ ] 影子对比报告：Recall/MRR 提升量化落档；未达标走开关回退路径
- [ ] 手测剧本：新文档→检索调试看 embed 文本含定位语；重建小库全流程；开关切换行为
