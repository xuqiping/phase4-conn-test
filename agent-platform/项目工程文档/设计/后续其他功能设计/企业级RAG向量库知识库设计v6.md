# 企业级 RAG 知识库设计 v6（精简版）

> 创建：2026-06-16
> 适用项目：agent-platform
> 状态：**Phase1 落地态**。本文取代 v3/v4/v5。设计目标 = OpenViking 三级上下文 + 目录递归，叠加企业三项刚需：**权限前置、引用可信、成本可控**。
> 方法论：能力**最小可跑闭环**优先。未列出的机制（记忆全栈/CRAG/多跳/HyDE/compression/模型迁移/CAS 版本）一律延后，列为 §11 非目标，**不进 Phase1**。流程线性、gate 仅依赖此前已发生步骤输出——杜绝循环引用。

---

## 0. 设计要点

- **三级上下文**（OpenViking 本体）：L0 章节摘要（召回单元）/ L1 文档元数据（背景）/ L2 原文证据（按需深读）。
- **目录优先于扁平向量**：先定位目录/主题范围，再范围内 dense 召回。
- **单路 dense 召回**：query embed vs L0 摘要 embed。词汇增强下沉到 L2 rerank 阶段（BM25 有真实词频，可排序），不在 L0 层做无序"存在性 RRF"。
- **权限前置**：(tenant,kb) 结构性分区 + Redis 可见集做 post-ANN 过滤。
- **引用可信**：生成后 Citation 硬校验（`[n]∉注入集`→拒绝）；简单 Abstention（top1 分数低→拒答，不编造）。
- **成本可控**：单 `effectiveContextCap`；rerank pair = 候选文档数 × 每文档 cap，硬封顶。
- **一致性最小**：原文 = 唯一真相源，向量可重建；Outbox 解耦写原文与写向量；软删 + 幂等清向量。
- **语义缓存**：跨会话 answer_cache，per-user + permission_signature + evidence hash 校验，高频 FAQ 省 token。

---

## 1. 背景与目标

平台已具 Agent/Skill/Workflow/Chat/Runtime。下一阶段给企业用户统一知识库：上传制度/手册/接口/纪要，在对话、Agent、工作流中按权限检索、引用。

本设计参考 OpenViking：上下文组织为目录/文件而非扁平块；L0/L1/L2 三级；目录递归检索；检索轨迹可观察。

平台增强：企业权限前置过滤；引用可追溯；Token 预算控制；Agent/Workflow 集成。**不追求 research-grade 记忆/自纠错/多跳**——按需后置。

---

## 2. 设计原则

1. **权限先于检索**：召回前按租户/知识库/文档/目录/用户/角色过滤。禁止先召回再过滤敏感内容。
2. **分层加载**：默认只给模型 L0 + 文档元数据子集；L2 原文仅在需精确条款/数字/表格时加载。
3. **目录优先**：先缩空间，再语义匹配。
4. **引用可追溯**：输出可追溯到文档/章节/页码/段落。
5. **成本可控**：每次检索有明确上限——召回数、L2 字符数、最终 token、rerank pair。
6. **原文为真相源**：向量是其可重建派生物，绝不脱离原文成独立真相。
7. **最小闭环**：能跑、能验收、能演进。复杂增强延后。

---

## 3. 三级上下文

### 3.1 L0：摘要索引层（召回单元）

L0 = 章节级（section）原子摘要，**主召回单元**。一个 section 一条 L0、最多一条 dense embedding。

- **粒度**：section 正文 200-800 tok。超 800 按子标题再切；不足 200 且语义不独立 → 合并相邻；语义独立（单 FAQ/表格行）→ 允许独立。
- **内容**：title + abstract（一句话摘要）+ path。
- **预算**：单 L0 摘要 50-120 tok；一次检索装载 top-N（默认 40）做候选。

文档级摘要仅作目录定位与背景，不参与召回语义匹配（避免粒度混杂）。

### 3.2 L1：文档元数据层（非检索层）

L1 不作检索层，存 `knowledge_documents.l1_metadata`：summary / outline / importantRules。检索中仅用于：

- 命中 L2 按文档去重；
- 注入 outline + importantRules 子集作背景（每文档 ≤250 tok，top D 文档合计有界）。

### 3.3 L2：原文证据层

存放完整原文、表格、附件文本、代码片段；仅在需精确条款/数字/表格/引用时加载。

- **切分**：单 passage ≤1024 tok（对齐 reranker max_seq 留余量）。长表按"行列定位摘要 + 局部行"切。
- **不向量化**：L2 仅 tsvector + 按 nodeId 取原文。词汇信号由 L2 tsvector BM25 在 rerank 阶段承担（L2 长文本有真实词频），无需 L2 dense 向量。
- **预算**：单次 L2 总预算默认 3000-6000 tok；支持按段落/表格行/页码局部加载。

---

## 4. 检索流程（线性，8 步，顺序锁定）

> 每步 gate 仅依赖此前步骤已产出的值。无循环、无"用后置步骤结果定前置 gate"。

1. **权限可见集加载**
   先取 Redis 可见集 `vis:{tenant}:{identity}:{kb}` → 可检索 doc_id set。identity 含 USER 与 SERVICE_ACCOUNT（后台执行身份）。未命中回源 DB 计算 + 写回 + per-key 互斥锁。

2. **缓存短路**（可选命中）
   算 `permission_signature = hash(visible_set + kb_scope)`。查 `rag_answer_cache`：exact-key 点查 → HNSW 兜底。命中且通过 `scope_user + permission_signature + evidence content_hash 现值 + evidence doc_id ⊆ visible_set` 全校验 → 直接返回（标"来自缓存"，可一键新鲜检索），不写新 episode。未命中 → 进 step3。
   > 高精度需求（法务/财务要新鲜证据）可显式跳过缓存短路，由调用方参数控制。

3. **Permission Pre-filter + metadata 硬过滤**
   可见集 ∩ 请求 filters（doc_type/effectiveAt 等）作为硬 pre-filter 并入 SQL WHERE。求交得最终范围，禁止单独放大。

4. **Directory Routing（前置路由）**
   在 KB 目录 + L0 摘要中定位候选目录（query vs 目录名/L0 摘要存在性匹配）。定位失败或跨目录 → 降级全库召回，**必须走 (tenant,kb) 分区 + 可见集 bitmap**，禁止裸 HNSW + 重 ACL 后过滤。

5. **Dense Recall（L0 单路）**
   query embed vs L0 摘要 embed（HNSW），候选目录内（或降级全库）取 top-N（默认 40）。所有召回 SQL 强制带 step6 的不变式过滤。

6. **L2 候选生成 + Rerank（单级）**
   - 取 dense top M（默认 8）L0、top D（默认 5）文档。
   - 对 top M 每个 L0 取其 L2 子节点（parent-anchored）。
   - 候选文档范围内 L2 tsvector BM25 预筛（命中 L2 反向 vote 父 L0 提权）。
   - L2 候选集 = top-M 子节点 ∪ BM25 命中，限 top-D 文档，每文档 cap（默认 ≤20），去重。
   - **单级 cross-encoder rerank**：对有界 L2 候选 pairwise 打分（query vs 片段原文），取 top K（默认 3）。
   - pair 上界 = D × cap = 5×20 = 100 ≤ `maxRerankPairs`（默认 100）。

7. **Abstention 判定（pre-gen）**
   取 rerank top1 分数。低于阈值（默认 cross-encoder 0.5）→ abstention：固定话术拒答（"未在当前知识库找到可信依据，建议转人工或补充知识"），不编造、不用通用知识兜底（企业合规），记原因到 trace，不写语义缓存。高于阈值 → 进 step8。

8. **Evidence Loading + 生成 + Citation 校验**
   - 按 `effectiveContextCap` 截断 top K L2 片段进 prompt；注入 L1 outline+importantRules 子集（≤250 tok/doc）。
   - 装载前 evidence content_hash 二次校验（≠ 库内现值 → 丢弃 + 补 REINDEX）。
   - 生成答案 + 结构化引用 `[1]..[K]`。
   - **Citation 硬校验（post-gen）**：`[n]` ∉ 注入集合 → 拒绝/重生成。零额外 LLM，确定性。
   - 输出 + 引用。写 trace；缓存候选（abstain 不缓存）。

### 4.1 三级定位（防粒度混杂）

| 层 | 粒度 | 召回角色 | 向量化 | 词汇信号 |
|---|---|---|---|---|
| L0 | section 200-800 tok，摘要 50-120 tok | **主召回单元** | dense embedding | 无（Phase1） |
| L1 | 文档级 | 非检索层；背景注入 | 无 | 无 |
| L2 | passage ≤1024 tok | 证据层；rerank 阶段 BM25 提权 | **无** | tsvector BM25（词频） |

---

## 5. 权限与安全

### 5.1 权限模型

```
Tenant → KnowledgeBase → Directory → Document（L1 锚点 + 授权对象）→ Section/Chunk（继承 Document 权限）
```

- **授权粒度**：permission 仅在 KB / DIRECTORY / DOCUMENT 三级独立授权。Section/Chunk 继承所属 Document。需章节级隔离 → 拆独立 Document 或 KB。
- **权限类型**：canRead / canWrite / canManage（boolean）。
- **授权主体**：用户 / 角色 / 部门 / SERVICE_ACCOUNT。
- Agent/Workflow 绑定 KB = **检索范围（scope）**，非权限类型；绑定后以"执行身份对该 kb 实际授权 ∩ 绑定范围"求交。**禁止任一单独放大**（不变式 P4）。
- 执行身份：Chat=当前用户；Agent=用户权限 ∩ 绑定范围；Workflow=触发用户权限 ∩ 绑定 ∩ 节点配置；后台（无触发用户）= service-account 权限 ∩ 绑定范围。

### 5.2 可见集缓存

- key `vis:{tenant}:{identity}:{kb}` → 可见 doc_id set。
- 召回 SQL `AND document_id = ANY(:visible_set)` 做 **post-ANN 过滤**（pgvector HNSW 不支持前置过滤）。§6 分区已把候选缩到单 KB，doc 级后过滤在缩小集上做；重 ACL 配 over-fetch(3-5×) + 调大 ef_search，监控 `rag_recall_after_filter`。
- **失效**：grant/revoke/文档状态/KB 成员变更 → 经 index_job outbox 同事务发失效事件，异步删 key。最终一致，不阻塞授权事务。

### 5.3 安全边界

- 向量库不存明文敏感字段可逆索引；embedding metadata 只放必要过滤字段。
- 删除知识后，向量、L0/L2、缓存、检索轨迹都要失效。
- 检索轨迹展示给用户时只展示有权证据。
- 知识库级水印：答案标记来源为内部资料。

---

## 6. 一致性（原文-向量，Outbox 最小）

原文唯一真相源，向量可重建派生。`knowledge_index_jobs` 解耦写原文与写向量。

**写入（单事务）**：

1. 写 `knowledge_nodes`（原文 + content_hash + status + keywords 可选）。
2. 同事务写 `knowledge_index_jobs` 一条 PENDING（content_hash 须 = node 现值）。
3. 提交后 worker 拉、lock。**lock 后、生成 embedding 前 re-check**：`SELECT content_hash, status, deleted FROM knowledge_nodes WHERE id=?`。任一不一致（并发更新/删除）→ **作废本 job（不写向量、不置 DONE）**，由新版本 job 接管。一致才生成 embedding、写向量、置 DONE。
4. 失败指数退避重试，超 `max_attempt` 置 DEAD 告警。
5. 幂等：worker 写向量前按 `idempotency_key = sha(node_id + content_hash + job_type)` 查重，已 DONE 跳过。

**删除**：node 软删（deleted=1，status=ARCHIVED）→ 同事务发 delete_job 清向量 + 清 evidence 缓存 + trace 脱敏 → 各 job 独立重试 → 按保留期物理清理。

**版本**（Phase1 最小）：文档更新 = 新 content_hash + 同事务 CAS 作废旧 ACTIVE（`UPDATE ... SET status='STALE' WHERE id=? AND status='ACTIVE'`；affected=0 放弃重读）+ 发旧 DELETE/新 UPSERT job。worker 先删旧向量再写新。复杂版本链/回滚延后（§11）。

**对账**（Phase1 最小）：定时扫 ACTIVE node hash ≠ embedding hash → 补 REINDEX；孤儿向量 → DELETE。复杂 HNSW 退化监控延后。

### 6.1 检索层强制约束

所有召回 SQL 与向量查询**必须**带：

```sql
WHERE n.status = 'ACTIVE'
  AND n.deleted = 0
  AND e.content_hash = n.content_hash
  AND e.embedding_model = kb.embedding_model
  AND n.document_id = ANY(:visible_set)        -- 权限 post-ANN
  AND (cardinality(:meta_filter)=0 OR n.doc_type = ANY(:meta_filter))  -- metadata 硬 pre-filter；空 filter 置真
```

封装在 `RagRetrievalService` 基础召回方法内，禁止业务层绕过（代码审查 + 单测强制）。

---

## 7. Token 与成本

### 7.1 渐进装载（默认）

```
L0 dense 召回 top 40
→ top 8 L0 / top 5 文档进 expand
→ L2 候选生成（top L0 子节点 + L2 BM25 提权，每文档 cap 20）
→ L2 单级 cross-encoder rerank，取 top 3
→ 最终上下文 ≤6000 tok（含 L1 outline+rules 子集 ≤250/doc；受 model 窗口 clamp）
```

### 7.2 Token 预算

每次检索携带：

```json
{
  "maxContextTokens": 6000,
  "modelMaxContext": 32000,
  "answerTokenReserve": 1200,
  "effectiveContextCap": 6000,
  "maxL0Candidates": 40,
  "maxL2Read": 3,
  "maxRerankPairs": 100
}
```

`effectiveContextCap = min(maxContextTokens, modelMaxContext − answerTokenReserve)`。业务层只认 effectiveContextCap，防小窗模型爆 context。

**成本上界（默认档，直接声明，无需扇出推导）**：

- embedding 调用：1（单 query embed，复用于 dense 召回 + answer_cache ANN）。
- rerank pair：D × cap = 5 × 20 = 100 ≤ maxRerankPairs。
- LLM 调用：1（单次生成）。Citation 硬校验零 LLM。

> 不做多模式扇出预算账。模式只有一个默认档 + 一个"高精度"开关（开 L2 全量精读、调大 L2 预算、可跳缓存），不引入独立预算推导。

---

## 8. 语义缓存（跨会话，per-user）

数据源 `rag_answer_cache`：

- **命中链路**：`query_hash = hash(query_canonical)` 点查 `(tenant, scope_user, query_hash)` → 命中走快路径；否则 key_embedding HNSW 近邻 + over-fetch 兜底。
- **校验链**：scope_user + permission_signature + evidence content_hash 逐条现值 + evidence doc_id ⊆ visible_set。任一不匹配 → miss。
- **强制 per-user**：cache key = `scope_user_id + permission_signature`。**跨用户命中禁用**（同 query 不同用户可见 evidence 不同，跨权限命中会泄露）。
- **失效粒度**：文档级。单文档编辑只失效引用该文档的答案（evidence content_hash 逐条 miss）。TTL 兜底（默认 1h）。
- abstention 不写缓存。

---

## 9. 数据模型

> Phase1 单 embedding 模型（标量 `embedding_model`），单表 `knowledge_embeddings`。per-model 分表 + 模型版本迁移延后（§11）。

### 9.1 knowledge_bases

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | 租户（单租户阶段=1） |
| name / description | VARCHAR / TEXT | |
| visibility | VARCHAR | PRIVATE / TEAM / PUBLIC |
| embedding_model | VARCHAR | **标量**，当前 embedding 模型 code |
| rerank_model | VARCHAR | reranker code |
| status | VARCHAR | ACTIVE / ARCHIVED |
| created_by / created_at / updated_at / deleted | — | 通用 |

### 9.2 knowledge_documents（L1 锚点）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| kb_id | BIGINT | |
| directory_id | BIGINT | 所属目录 node |
| title | VARCHAR | |
| doc_type | VARCHAR | policy/manual/faq/api/... |
| status | VARCHAR | PENDING/PARSING/EMBEDDING/INDEXED/FAILED |
| l1_metadata | TEXT(JSON) | summary/outline/importantRules；注入仅 outline+importantRules 子集 |
| file_ref / file_hash | TEXT / VARCHAR | 原始文件 / 重复检测 |
| effective_at | TIMESTAMPTZ | 生效时间（过期降权延后） |
| created_by / created_at / updated_at / deleted | — | 通用 |

### 9.3 knowledge_nodes（L0/L2）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id / kb_id | BIGINT | 分区键 |
| document_id | BIGINT | |
| parent_id | BIGINT | 父节点 |
| path | TEXT | 虚拟路径 |
| node_type | VARCHAR | DIRECTORY / SECTION / TABLE / FAQ |
| level | VARCHAR | L0 / L2（L1 不占 node 行） |
| title / content | VARCHAR / TEXT | L0 摘要 / L2 原文 |
| content_tsv | tsvector | generated（to_tsvector over content）；L2 BM25 词频源 |
| metadata | TEXT(JSON) | 标题路径、页码、表头前缀 |
| token_count | INTEGER | 预估（section 200-800；L2≤1024） |
| content_hash | VARCHAR | 内容 hash |
| status | VARCHAR | ACTIVE / STALE / ARCHIVED |
| created_at / updated_at / deleted | — | 通用 |

索引：GIN(`content_tsv`)；`(tenant_id, kb_id, level)`；`(document_id)`；`(parent_id)`；`(tenant_id, kb_id) partial where level='L0'` 供 L0 召回。

### 9.4 knowledge_embeddings（L0 dense 向量）

> 只 L0 生成 dense embedding。L2 不向量化。1 个 L0 node ↔ 0..1 行。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| node_id | BIGINT | L0 节点（唯一） |
| tenant_id / kb_id | BIGINT | 分区键 |
| embedding_model | VARCHAR | 冗余便于查询 |
| embedding | halfvec(2048) | dense 向量（HNSW）；halfvec 而非 vector——pgvector HNSW 硬限 ≤2000 维，vector(2048) 建索引失败；halfvec HNSW ≤4000，2048 容得下且存储减半。dim 按 active 模型（Phase1 doubao=2048） |
| content_hash | VARCHAR | §6 一致性校验 |
| created_at | TIMESTAMPTZ | |

索引：`node_id` 唯一；`(tenant_id, kb_id)`；HNSW on `embedding halfvec_cosine_ops`（partial-per-kb 或分区）。

### 9.5 knowledge_permissions

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | |
| target_type | VARCHAR | KB / DIRECTORY / DOCUMENT |
| target_id | BIGINT | |
| subject_type | VARCHAR | USER / ROLE / DEPARTMENT / SERVICE_ACCOUNT |
| subject_id | BIGINT | |
| can_read / can_write / can_manage | BOOLEAN | |
| granted_by | BIGINT | |
| created_at | TIMESTAMPTZ | |

### 9.6 knowledge_index_jobs（Outbox）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| node_id / kb_id | BIGINT | |
| job_type | VARCHAR | UPSERT / DELETE / REINDEX |
| content_hash | VARCHAR | |
| status | VARCHAR | PENDING / RUNNING / DONE / FAILED / DEAD |
| attempt / max_attempt | INTEGER | |
| locked_until | TIMESTAMPTZ | |
| idempotency_key | VARCHAR | 唯一 |
| visibility_event | BOOLEAN | 是否触发可见集失效 |
| last_error | TEXT | |
| created_at / updated_at | TIMESTAMPTZ | |

索引：`(status, locked_until)`；`idempotency_key` 唯一；`(node_id, job_type)`。

### 9.7 rag_answer_cache（语义缓存，per-user）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| tenant_id | BIGINT | |
| scope_user_id | BIGINT | **非空**，per-user |
| kb_ids | TEXT | |
| query_canonical | TEXT | 归一化 query |
| query_hash | VARCHAR | exact-key（hash canonical） |
| key_embedding | halfvec(2048) | query 向量（HNSW 兜底）；同 §9.4 halfvec 修正 |
| answer | TEXT | 答案 JSON（脱敏） |
| provenance_node_ids | TEXT(JSON) | 回链 L2 evidence node |
| evidence_hashes | TEXT(JSON) | content_hash 集合（逐条二次校验） |
| permission_signature | VARCHAR | hash(visible_set + kb_scope) |
| confidence | REAL | |
| usage_count | INTEGER | |
| decay_at | TIMESTAMPTZ | |
| status | VARCHAR | ACTIVE / DISABLED / ARCHIVED / REVOKED |
| created_at / updated_at | TIMESTAMPTZ | |

索引：HNSW on `key_embedding halfvec_cosine_ops`；`(tenant_id, scope_user_id, query_hash)` 唯一；`(scope_user_id, decay_at)`。

### 9.8 rag_retrieval_logs（可选审计）

trace_id / tenant / identity / kb_ids / query / mode / candidates_l0 / evidence_l2（含 content_hash）/ crag-style verdict（这里是 abstain 标记）/ token_usage / latency / created_at。

---

## 10. 部署与分期

### 10.1 部署（pgvector HNSW）

- PostgreSQL 16 + pgvector，**必须 HNSW**（`USING hnsw (embedding halfvec_cosine_ops)` + `SET hnsw.ef_search`）。向量列用 **halfvec(2048)** 非 vector(2048)——pgvector HNSW 硬限 ≤2000 维，halfvec ≤4000。
- **Windows PG16**：取预编译 `vector.dll` 放 `lib/` 后 `CREATE EXTENSION vector`。**Phase1 第 0 步先验证扩展可加载 + HNSW 建成功**，否则验收阻塞。
- HNSW × 强过滤劣化对策：`(tenant_id, kb_id)` 分区/partial index 把范围变结构性索引选择（缩搜索空间根本）；可见集 post-ANN 过滤；重 ACL over-fetch + ef_search。
- reranker：L2 ≤1024 tok；Phase2 上 bge-reranker-v2-m3（8k 多语含中文）。
- embedding：Phase1 Doubao embedding API（复用 chat 栈，无 GPU）。
- 异步任务：Spring `@Async` 起步，后续接队列。
- 解析：Java 基础解析 + 可插拔 Python/sidecar。

### 10.2 Phase1 验收（最小闭环）

1. 创建知识库、上传文档、看解析与索引状态。
2. 自动生成 L0 + 文档 L1 + L2，section 200-800 tok，L2≤1024。
3. 检索默认走 L0 dense + 目录路由；仅必要证据进 L2；L1 仅 outline+rules 子集注入。
4. RAG 回答带引用（文档/章节/页码）。
5. 未授权用户无法检索（可见集生效）；缓存 per-user 不跨权限泄露。
6. 文档更新后旧版本不进默认检索；删文档后不再召回，向量被 delete_job 清除。
7. worker 失败重试不产生重复向量（幂等键）；并发更新 re-check 不写过期 embedding。
8. Citation 硬校验拦截伪造引用；top1 分数低正确 abstain 不硬答不编造。
9. token 上限生效（effectiveContextCap）。

### 10.3 后续 Phase（按需，非 Phase1）

- **Phase2**：cross-encoder rerank（bge-reranker-v2-m3）、query rewrite、Contextual Retrieval（L0 per-section 前缀）、prompt caching、HNSW 退化监控 + 对账增强。
- **Phase3**：Agent/Workflow 深集成、service-account 身份、个性化。
- **Phase4**：记忆层（语义软提示/缺口闭环，仅作软提示不替代证据）、多跳、CRAG、模型版本迁移、版本链/回滚、PII 脱敏、过期降权、连接器、对象存储。

---

## 11. 非目标（显式排除 / 延后）

| 项 | 状态 | 理由 / 解禁条件 |
|---|---|---|
| L3 记忆全栈（episodic/semantic facts/active-learning/个性化/自纠错/治理） | 延后 Phase4 | research-grade，非企业 RAG 刚需；按真需求加，且记忆仅作软提示绝不替代证据 |
| CRAG 分档 + 二次召回 evaluator | 延后 | 用极简 Abstention（top1 分阈值）替代，足够防硬答 |
| 多跳（decompose/并行/级联） | 延后 | 复杂度高，多数企业问答单跳够 |
| HyDE 假设摘要检索 | 延后 | 边际增强，边际 LLM/embedding 成本 |
| Contextual compression（tsvector 选句） | 延后 | L2 局部加载已够省 token |
| 三模式 + Intent Routing 扇出预算账 | 砍 | 默认档 + 高精度开关够；不做扇出推导 |
| per-model 分表 + shadow 模型迁移 | 延后 | Phase1 单模型标量；切模型时按 kb re-embed |
| prompt caching 锁结构 / BYOK | 延后 Phase2 | 锦上添花 |
| 跨用户语义缓存 | **永久非目标** | 跨权限命中会泄露 |
| 记忆作答案事实来源 | **永久非目标** | 记忆失真产生幻觉，违企业合规 |
| 跨模型在线 union + RRF 两路 dense | **永久非目标** | 同源 dense 用 RRF 非最优，双倍成本 |
| L2 dense 兜底懒生成 | **永久非目标** | 与 L2 BM25 同候选集重叠，爆 embedding 预算 |

---

## 12. 不变式（可机械校验，落单测）

```
I1  ∀ node 参与召回:
      node.status='ACTIVE' AND node.deleted=0
      AND embed.content_hash = node.content_hash
      AND embed.embedding_model = kb.embedding_model
I2  ∀ index_job lock 后写向量前: re-check (content_hash, status, deleted) 一致，否则作废
I3  ∀ evidence 装载: 装载前 content_hash == 库内现值，否则丢弃 + 补 REINDEX
I4  idempotency_key = sha(node_id + content_hash + job_type) 唯一
P1  ∀ 召回结果 doc ∈ visible_set(tenant, identity, kb)   -- post-ANN SQL 过滤
P2  ∀ answer_cache 命中: scope_user + permission_signature 匹配
      AND evidence doc_id ⊆ visible_set AND evidence content_hash 现值逐条匹配
P3  permission_signature = hash(visible_doc_set + kb_scope)
P4  Agent/Workflow 最终范围 = 执行身份权限 ∩ 绑定范围；任一为空 → 空集（禁止单独放大）
B1  effectiveContextCap = min(maxContextTokens, modelMaxContext − answerTokenReserve)
B2  final prompt token ≤ effectiveContextCap
B3  rerank pair = D × cap ≤ maxRerankPairs（默认 5×20=100 ≤ 100）
B4  在线 embedding 调用 = 1（单 query embed，多处复用）
A1  Citation 硬校验: ∀ 引用 [n] ∈ 注入集合，否则拒绝/重生成
A2  abstention: rerank top1 < 阈值 → 拒答，不编造，不写缓存
R1  L2 不向量化、无 L2 dense 通道；词汇信号 = L2 tsvector BM25（rerank 阶段，有词频可排序）
```

封装在 `RagRetrievalService` 基础方法 + 单测强制。流程线性：step1..8，每个 gate 仅依赖此前步骤输出——**无循环、无后置结果定前置 gate**。

---

## 13. 与 v5 的关键差异（迁移参考）

- **砍 L3 记忆全栈**：v5 episodic + semantic facts + active-learning + 个性化 + 自纠错 + 治理（7 表）→ v6 仅留语义缓存 answer_cache。
- **砍 CRAG 分档 + 多跳 + HyDE + contextual compression**：→ v6 极简 Abstention（top1 分阈值）。
- **砍三模式 Intent Routing + 扇出预算账**：→ v6 默认档 + 高精度开关，成本直接声明（embed=1, pair=D×cap, LLM=1）。
- **砍 per-model 分表 + shadow 迁移 + CAS 版本链 + HNSW 退化对账增强**：→ v6 单模型标量、最小 CAS、最小对账，复杂项延后。
- **砍 L0 keywords lexical RRF 通道**（v5 双独立种子）：→ v6 L0 单路 dense，词汇信号下沉 L2 BM25（有词频可排序），消除 v5「无序存在性通道进 RRF」的方法论断裂。
- **线性 8 步流程**：消除 v5 多跳「并行 retrieve vs 逐跳 CRAG」互斥、top-1 全量释放「rerank margin gate 自指」两硬矛盾。
- 单文件 self-contained，不变式落单测，流程无循环。

---

## 14. 参考资料

- OpenViking：<https://github.com/volcengine/OpenViking>
- OpenViking Context Layers：<https://github.com/volcengine/OpenViking/blob/main/docs/en/concepts/03-context-layers.md>
- bge-reranker-v2-m3：<https://huggingface.co/BAAI/bge-reranker-v2-m3>
- pgvector：<https://github.com/pgvector/pgvector>
