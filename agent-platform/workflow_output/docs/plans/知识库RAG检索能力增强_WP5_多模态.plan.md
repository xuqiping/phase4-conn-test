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

- [ ] **Step 1：多模态 embed 协议扩展**
  - **目标**：LlmGateway 可传图
  - **动作**：①`OpenAICompatibleProvider` EMBEDDING 行增重载：入参 List<ContentPart>（text/image_url data URI），按模型能力标记组装 content 数组或回退纯 text；②`LlmGateway.embedMultimodal(parts, model, owner)`；③模型多模态能力标记来源（llm_models 现有字段或配置，实施时定，最小改动优先）；④计费归户 owner
  - **文件**：`llm/provider/OpenAICompatibleProvider.java`、`llm/LlmGateway.java`、Test ×2
  - **依赖**：Step 0（不影响协议，可并行）｜**验证**：单测两种协议拼装/mock 契约；真实模型手动验证一次（需人工介入：提供支持图输入的模型）

- [ ] **Step 2：IMAGE 向量索引双写**
  - **目标**：图片文档入库时追加图片向量行
  - **动作**：①迁移（与 WP3 共用文件 `V1xx__knowledge_rag_context_multimodal.sql`）：`knowledge_embeddings.modality VARCHAR(16) NOT NULL DEFAULT 'TEXT'` + 部分索引；②`IndexJobWorker`：IMAGE 文档索引时在文本向量外追加图片向量 job（原件 bytes→data URI→embedMultimodal；维度校验不符→跳过+标记该库 IMAGE 通道禁用）；③OpenSearch chunk 同步带 modality；④检索侧不变（WP5 Step 3 才消费）
  - **文件**：迁移（WP3 已建则 ALTER 补列）、`entity/KnowledgeEmbedding.java`、`IndexJobWorker.java`、`opensearch/OpenSearchChunkDocument.java`、Test ×2
  - **依赖**：Step 1、Step 0（原件在）｜**验证**：单测——双向量行写入/维度不符跳过/TEXT 存量默认值；上传图片→embed 数=2

- [ ] **Step 3：IMAGE 检索通道接入 RRF**
  - **目标**：文本 query 可召回图片
  - **动作**：①`RagRetrievalQueryMapper` 增 IMAGE 向量查询（同 TEXT 查询按 modality 过滤）；②候选池新增 IMAGE 通道（通道来源标记 image）；③RRF 融合同参 k=60；④命中 IMAGE 行证据内容=该图既有文本描述（识图/手填/附件描述），引用走 fileRef inline 现状；⑤ATTACHMENT 图片：描述命中（C2）与 IMAGE 向量命中可能同 node→RRF 天然合并，证据去重 by nodeId（现状去重逻辑核实复用）
  - **文件**：`mapper/RagRetrievalQueryMapper.java`、`RagRetrievalService.java`、`service/internal/RrfFusion.java`（通道注册）、Test ×2
  - **依赖**：Step 2｜**验证**：单测——IMAGE 通道召回/混维度库仅 TEXT/同 node 双通道去重；手测：传产品截图问「这个界面哪里改配置」→图被召回

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
