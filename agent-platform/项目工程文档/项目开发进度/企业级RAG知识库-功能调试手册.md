# 企业级 RAG 知识库 — 功能调试手册（每个功能是什么 · 怎么在真实环境测）

> 创建：2026-06-23
> 范围：企业级 RAG 知识库**每一个已完成的步骤/功能**，单独成节。每节讲清楚：**是什么（保留专业名词）→ 大白话 → 案例 → 真实环境怎么调试测试**。
> 配套文档：`企业级RAG知识库-集成脱离分析报告.md`（讲哪里和平台接得不好）。
> 进度总览：`当前项目开发进度-企业级RAG知识库.md`。

---

## 〇、通用调试前置（所有功能都要先做）

### 0.1 起服务

三个进程，按需起：

```bash
# 1. backend（Java，端口 8080）——必须
cd e:/workspace/agent-platform/backend
mvn spring-boot:run
# 历史坑：曾需 -Dmaven.test.skip=true 避开陈旧测试，阶段7 已修，现可直接跑

# 2. runtime-sidecar（Python，端口 8090）——工作流检索/Agent 真实执行才需要
cd e:/workspace/agent-platform/runtime-sidecar
python -m pip install -r requirements.txt
python -m uvicorn app.main:app --host 0.0.0.0 --port 8090

# 3. frontend（Vue，端口 5173）——UI 调试才需要
cd e:/workspace/agent-platform/frontend
npm install
npm run dev
```

### 0.2 后端配置确认（`backend/src/main/resources/application.yml`）

- `runtime.gateway.mode`：调工作流/Agent 用 `sidecar`；只调检索/上传用 `mock` 也行（不启 Python）。
- `rag.visibility-cache.enabled` / `rag.answer-cache.enabled` / `rag.reconciliation.enabled`：调试时建议**先关**（false）保证确定性，确认基线后再开。
- `spring.task.scheduling.pool.size: 2`：保证后台两个轮询（IndexJobWorker + ReconciliationWorker）不互相饿死。

### 0.3 必备依赖

- **PostgreSQL 16**，库 `agent_platform`，已装 **pgvector 0.8.2**（用 halfvec，HNSW 索引）。psql 连：`psql -U <用户> -d agent_platform`。
- **Redis** 开（可见集缓存 + JWT 黑名单）。
- **Ark / 豆包 API Key**：管理员经前端「设置 → 供应商」录入（AES 加密存库），embedding 用 `doubao-embedding-vision`（2048 维），chat 用 `doubao-seed-2.0-code`。**key 不进 git**。

### 0.4 管理员登录拿 token

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"admin\",\"password\":\"admin123\"}"
# 返回 data.accessToken，后续所有请求带 -H "Authorization: Bearer <token>"
```

> **中文 body 坑**：Windows curl 经 Git Bash 传中文会 GBK→UTF8 报错。带中文的 body **写到 UTF-8 文件**，用 `--data-binary @file.json`。

### 0.5 验证 embedding key 通

```bash
# provider id 以实际为准（先 GET /api/llm/providers 查 embedding 那条的 id）
curl -X POST http://localhost:8080/api/llm/providers/5/test-embed \
  -H "Authorization: Bearer <token>"
# 期望：message 含"连接成功 (维度 2048)"
```

### 0.6 速查表：核心表

| 表 | 装什么 |
|----|--------|
| `knowledge_bases` | 知识库（embeddingModel/visibility/summaryStrategy） |
| `knowledge_documents` | 文档（status 状态机/parseError） |
| `knowledge_nodes` | 文档拆出来的节点（L0 摘要/L1 元数据/L2 原文，parent_id 层级） |
| `knowledge_index_jobs` | 向量化任务（PENDING/RUNNING/DONE/DEAD） |
| `knowledge_embeddings_doubao` | 向量行（halfvec(2048)，node_id 唯一） |
| `knowledge_permissions` | 授权（USER/ROLE/DEPARTMENT × KB/DIR/DOC） |
| `rag_retrieval_logs` | 检索审计 trace |
| `rag_answer_cache` | 答案缓存 |
| `user_memories` | 用户长期记忆（category/key/value/confidence/source/block_label/embedding/conflict_id）+ V29 `uk_user_memories_user_key_clean`（clean 同 user 同 key 唯一，FLAGGED 可共存） |
| `memory_conflicts` | 记忆冲突（status: PENDING/FLAGGED/RESOLVED + ask_text + new_memory 快照） |
| `chat_sessions` | 对话会话（kb_ids / rag_enabled） |
| `system_settings` | 全局开关（`rag.memory.enabled` + `rag.memory.process-mode` ASYNC/HYBRID） |

---

## 一、建库 + 权限模型（阶段 1）

### 是什么

- **KnowledgeBase（KB）**：一个知识库容器，绑定一个 embedding 模型、一种摘要策略、可见性（私有/团队/公开）。
- **权限三层主体**：USER（个人）/ ROLE（角色）/ DEPARTMENT（部门）；**权限粒度**：KB 级 / DIRECTORY 级 / DOCUMENT 级；**动作**：`knowledge:read` / `knowledge:write` / `knowledge:manage`。
- **owner 隐式 manage**：建库者和管理员天然有管理权（`canManage = admin || createdBy`）。
- **撤销 = 硬删**：`knowledge_permissions` 表无 deleted/version，撤销直接删行。

### 大白话

知识库就像一个"带门禁的资料柜"。你能建柜子（需 `knowledge:write` 权限），能把钥匙发给具体的人、某个角色、或某个部门；柜子主人天然是管理员。发钥匙的粒度可以粗（整个柜子）也可以细（柜子里某个文件夹/某份文档）。

### 案例

- 管理员建一个"产品文档库"，授权给"产品部"整个部门读，再单独给实习生小王发个读权限。
- 普通用户默认只有 `knowledge:read`，想建库得管理员授 `knowledge:write`。

### 真实环境调试

```bash
# 1. 建库（需 knowledge:write；admin 自带）
curl -X POST http://localhost:8080/api/knowledge/bases \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  --data-binary @create-kb.json
# create-kb.json: {"name":"测试KB","visibility":"PRIVATE","summaryStrategy":"PER_SECTION"}

# 2. 授权（USER 读权限）
curl -X POST http://localhost:8080/api/knowledge/permissions \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d "{\"kbId\":1,\"targetType\":\"USER\",\"targetId\":3,\"permission\":\"can_read\"}"

# 3. 列授权
curl http://localhost:8080/api/knowledge/permissions?kbId=1 -H "Authorization: Bearer <token>"
```

psql 验证：
```sql
SELECT id, name, visibility, embedding_model, summary_strategy FROM knowledge_bases;
SELECT * FROM knowledge_permissions WHERE kb_id = 1;
```

**负例测试**：用普通用户 token 调建库 → 应 403；用无 manage 权限的用户调授权 → 应 403。

---

## 二、文档上传（阶段 2 前置）

### 是什么

- 上传文件（md/txt/pdf/docx/html）经 `FileStorageService` 落盘 + 算 **SHA256**，在 `knowledge_documents` 建一行，**status = PENDING**。
- 上传后发 `DocumentUploadedEvent`，触发异步解析（见第三节）。**上传本身不解析、不向量化**。

### 大白话

把文件丢进知识库，系统先收下、登个记（状态"待处理"），真正的拆解和建索引是后台异步干的，不卡你上传操作。

### 案例

往"测试KB"传一份 `安装说明.md`，上传接口立刻返回成功，文档状态先是 PENDING，过几秒后台才开始处理。

### 真实环境调试

```bash
curl -X POST "http://localhost:8080/api/knowledge/documents?kbId=1" \
  -H "Authorization: Bearer <token>" \
  -F "file=@安装说明.md"
# 返回 document id，status=PENDING
```

psql 跟状态：
```sql
SELECT id, title, status, parse_error, content_hash FROM knowledge_documents WHERE kb_id=1;
```

---

## 三、解析 + 摘要 + 落节点（阶段 2 第 1 项 · DocumentParserService）

### 是什么

- **Tika 抽取**正文 → 按 section（章节）切分。
- **三段层级**（对齐 v6 §3.1）：
  - **L0**：每个 section 一个**摘要节点**（content = 摘要），是检索的主召回单元。
  - **L2**：L0 的子节点，**原文片段**（≤1024 token 切片），`parent_id` 指向所属 L0。
  - **L1**：文档级 **metadata**（元数据），存文档摘要。
- **摘要策略**（`summaryStrategy`）：PER_SECTION（逐节）/ BATCH（批量）/ HYBRID（混合）。
- **状态机**：`PENDING → PARSING → SUMMARIZING → EMBEDDING`（等向量化）｜异常 → `FAILED + parse_error`。
- **空摘要兜底**：摘要为空时用 section 原文前 400 字（防止空 L0 污染召回）。

### 大白话

系统读你的文档，按章节拆开，每个章节写一句"这段讲啥"的摘要（L0），原文按块存成它的子节点（L2）。检索时主要拿摘要去匹配，匹配上了再回头看原文。就像给每段资料贴个"标签"，查的时候先查标签。

### 案例

传一份"安装说明.md"，里面有"环境准备""安装步骤""配置"三个章节。解析后产 3 个 L0（每个一句摘要）+ 若干 L2（原文片段），文档摘要进 L1。

### 真实环境调试

上传后等几秒，psql 跟全过程：
```sql
-- 文档状态机走完到 EMBEDDING（向量化前）
SELECT title, status, parse_error FROM knowledge_documents WHERE id=<docId>;

-- 节点层级：每 section 一个 L0，L2.parent_id 指向 L0
SELECT id, level, parent_id, node_type, left(title,30) AS title, token_count, status
FROM knowledge_nodes WHERE document_id=<docId> ORDER BY id;

-- L1 元数据非空
SELECT l1_metadata FROM knowledge_nodes WHERE document_id=<docId> AND level='L1';
```

**不变式验证**：
```sql
-- L0.content_hash == sha256(L0.content)
SELECT (n.content_hash = encode(digest(n.content,'sha256'),'hex')) AS ok
FROM knowledge_nodes n WHERE document_id=<docId> AND level='L0';
```

**失败排查**：`status=FAILED` → 看 `parse_error`（历史上踩过 V17 漏建 `knowledge_nodes` 审计列的坑，V24 已补）。

---

## 四、向量化（阶段 2 第 4 项 · IndexJobWorker）

### 是什么

- **IndexJobWorker**（`@Scheduled` 轮询 + `knowledgeTaskExecutor` 异步消费）认领 PENDING/RUNNING(过期) 的 job。
- `claimBatch` 用 `FOR UPDATE SKIP LOCKED`（多 worker 安全）。
- 对 L0 调 `LlmGateway.embed()` 拿 2048 维向量，写 `knowledge_embeddings_doubao`（`halfvec(2048)`，`ON CONFLICT(node_id)` 就地覆盖）。
- **不变式**：
  - **I2 re-check**：embed 前后两次校验 node 状态/content_hash（防 embed 期间变更）。
  - **I1**：embedding 行 content_hash 始终 = node.content_hash。
  - **I4 幂等**：node_id 唯一，重复认领不产多行向量。
  - **DEAD/退避**：超 max_attempt 置 DEAD，否则指数退避重试。
- 文档所有 job DONE → 置 `INDEXED`。

### 大白话

后台有个"工人"轮询待办，把每个摘要（L0）送去 embedding 模型变成一串数字（向量），存进向量表。存的时候反复核对"这个摘要没被改过吧"，保证向量和对应当前内容严格对齐。一篇文档所有摘要都向量化完，文档状态变"已索引"，就能被检索了。

### 案例

第三节解析完的文档，状态从 EMBEDDING 变 INDEXED；`knowledge_embeddings_doubao` 里每个 L0 一行向量，维度 2048。

### 真实环境调试

确保 Ark embedding key 已录（0.5 节 test-embed 通）。上传后等 worker 跑（`knowledge.index.poll-ms` 默认 5 秒一轮）：
```sql
-- job 全 DONE（无 RUNNING/DEAD）
SELECT status, count(*) FROM knowledge_index_jobs WHERE document_id=<docId> GROUP BY status;

-- 文档终态 INDEXED
SELECT status FROM knowledge_documents WHERE id=<docId>;

-- 向量行数 = L0 数，维度 = 2048
SELECT count(*) AS rows, vector_dims(embedding::vector) AS dim
FROM knowledge_embeddings_doubao e
JOIN knowledge_nodes n ON e.node_id=n.id
WHERE n.document_id=<docId>;

-- I1：向量行 hash 对齐 node
SELECT (e.content_hash = n.content_hash) AS aligned
FROM knowledge_embeddings_doubao e
JOIN knowledge_nodes n ON e.node_id=n.id
WHERE n.document_id=<docId>;
-- 期望全 true
```

**调试技巧**：worker 是 `@Scheduled`，想立刻触发可临时把 `application.yml` 的 `knowledge.index.poll-ms` 调小（如 2000）；排查 DEAD job 看 `SELECT * FROM knowledge_index_jobs WHERE status='DEAD'` 的 `error_message` + `attempt`。

---

## 五、权限可见集缓存（阶段 4-A · VisibilitySetService）

### 是什么

- 把 USER + ROLE + DEPARTMENT 三层主体的授权**并集**算成一组 doc_id 集合（`VisibleDocSet`）。
- **Redis 缓存**：key `vis:{tenant}:USER:{userId}:{kbId}`，值 `{"all":true}` 或 `{"all":false,"docs":[...]}`，TTL 30min。
- cache-first：命中返回；miss → DB 三层并集算 → writeback；per-key 互斥锁防击穿。
- grant/revoke/删文档后，`AFTER_COMMIT` 发 `VisibilityInvalidationEvent`，listener `SCAN+DEL` 该 KB 全部缓存 key。
- admin/owner 短路成 `all`（全库），不缓存。

### 大白话

每次检索前都要算"这个用户能看到哪些文档"——这个计算挺贵（要查人/角色/部门三层）。所以算一次存 Redis，下次直接拿。一旦有人改了授权或删了文档，就把相关的缓存全清掉，避免读到旧的权限。

### 案例

给用户 3 授权读 KB1 → 检索 → Redis 写入可见集；再检索 → 缓存命中（不重算）。撤销授权 → 缓存清 → 再检索 → 403。

### 真实环境调试

```bash
# 授权 + 登录用户 3，检索一次（见第八节 retrieve）
# 然后 redis-cli 看缓存
redis-cli GET "vis:1:USER:3:1"
# 期望: {"all":true}（KB 级授权）或 {"all":false,"docs":[...]}

# 再检索一次，看后端日志无 computeVisibleDocs3Layer/hasKbLevelRead SQL（命中缓存）

# 撤销授权
curl -X DELETE "http://localhost:8080/api/knowledge/permissions?kbId=1&targetType=USER&targetId=3&permission=can_read" \
  -H "Authorization: Bearer <token>"
redis-cli GET "vis:1:USER:3:1"   # 期望 nil（已失效）
```

**调试开关**：`rag.visibility-cache.enabled=false` 时每次直算，便于对比。

---

## 六、检索 8 步核心（阶段 3 · RagRetrievalService）

### 是什么

经 `POST /api/knowledge/retrieve`（调试端点）暴露的完整线性 8 步检索：
1. **step1 可见集**（上节）。
2. **step2 答案缓存查**（命中短路，见第九节；默认关）。
3. **query 向量化**（B4：仅 embed 1 次）。
4. **dense 召回**（HNSW `<=>` 余弦距离，取 L0 候选）。
5. **L2 候选装载**（L0 的原文子节点，R1：纯 SQL 不嵌）。
6. **BM25 词法 boost**（`@@` + `ts_rank`，最多 +0.10）。
7. **rerank**（Phase1 用父 L0 cosine 代理，无 cross-encoder）。
8. **token 预算裁剪**（B1/B2：贪心截断，promptTokens ≤ 6000）+ 生成答案。

**不变式**：
- **A1 引用硬校验**（`CitationChecker`）：答案里 `[n]` 必须指向真实证据，越界重生成一次再败则 abstain。
- **A2 abstention**：best 父 L0 相似度 < 0.5 → 固定话术拒答（LOW_CONFIDENCE），不编造、不写缓存。
- **I1/I3**：dense SQL 内校 status/deleted/content_hash；evidence 装载前 node_hash 复校，失配丢弃。
- **P1**：post-ANN 在 dense SQL 里强制 `document_id ⊆ visible_set`。

### 大白话

用户问一个问题，系统：算你看得见哪些文档 → 把问题变成向量 → 在向量表里找最像的摘要 → 顺出对应原文片段 → 再用关键词匹配加加分 → 排个序 → 按token预算裁出刚好够喂给大模型的证据 → 大模型照证据写答案，答案里的引用编号必须是真实证据的编号。如果最像的也不够像（相似度太低），就直接说"我不确定"，绝不瞎编。

### 案例

KB1 有"安装步骤"文档，问"如何安装部署系统"：召回 doc2 → 答案"……需 PostgreSQL16/pgvector/…[1]"，带合法 `[1]` 引用，promptTokens=188≤6000。问无关的"量子物理夸克胶子"：top 相似度 0.17 < 0.5 → abstain LOW_CONFIDENCE，固定话术拒答。

### 真实环境调试

`/knowledge` 页「检索调试」tab，或直接打 API（中文 body 用文件）：
```bash
cat > retrieve.json <<'EOF'
{"kbId":1,"query":"如何安装部署系统","maxL0":40}
EOF
curl -X POST http://localhost:8080/api/knowledge/retrieve \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  --data-binary @retrieve.json
# 期望: abstained=false, answer 带 [1], citations 有 doc, candidatesL0 有 cosSim, tokenBudget
```

**无关 query 验 A2**：
```bash
cat > retrieve2.json <<'EOF'
{"kbId":1,"query":"量子物理夸克胶子"}
EOF
curl -X POST http://localhost:8080/api/knowledge/retrieve \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  --data-binary @retrieve2.json
# 期望: abstained=true, verdict=LOW_CONFIDENCE, topSim<0.5
```

**trace 验证**：
```sql
SELECT verdict, mode, latency_ms, left(query,30) AS query, token_budget
FROM rag_retrieval_logs ORDER BY id DESC LIMIT 5;
```

**调参点**：
- 过度拒答（abstain 太多）→ 降 `RagConfig.abstainThreshold`（如 0.5→0.35）。doubao 绝对相似度偏低（相关 query 仅约 0.50），0.5 处于边界，是模型特性非 bug。
- 召回不足 → `RagConfig.hnswEfSearch > 0` 触发 `SET LOCAL` 调 HNSW。

---

## 七、答案缓存（阶段 4-B · answer_cache）

### 是什么

- 跨会话**语义答案缓存**（`rag_answer_cache` 表，V17 已建）。opt-in 默认关。
- 重复/近义 query 命中 → 短路检索。接两个入口：
  - `retrieve()`（单KB debug）：命中跳过 step3-8 + 生成，回放缓存 answer。
  - `retrieveEvidence()`（多KB 生产路径 CHAT/AGENT/WORKFLOW/ask）：命中跳过 step3-7 检索，回放证据 systemPrompt；**不省生成**（按 persona 重新流式生成）。
- **校验链（懒失效，无主动 purge）**：
  - **P3 permission_signature**：sha256(可见集+kb_scope)，授权变 → 签名变 → 旧缓存自动 miss。
  - **P2a evidence hash 复校**：命中候选的 evidence node 逐一比 content_hash，doc 删/重传 → miss。
  - **per-user 强制**：SQL 恒带 `scope_user_id=?`，跨用户 HNSW 近邻被 WHERE 滤掉，永不命中。
- stale 行靠 `decay_at`（7 天）+ ReconciliationJob 清（第十二节）。

### 大白话

同一个问题反复问，每次都去知识库捞一遍 + 喂大模型很贵。系统把"这个问题 + 这个用户权限下"的检索结果/答案存一份，下次问一样或很像的，直接拿存好的，省掉检索那 3 秒和一次 embedding 计费。但权限一变或文档一删，存的就自动作废，不会给错答案。

### 案例

问"如何安装部署系统"首查 8121ms（存缓存）→ 同 query 再查 **190ms CACHE_HIT**（约 43 倍，零 LLM/embed 计费）。近义 query "系统安装部署步骤" 可能 miss（doubao 绝对相似度低，0.90 阈值偏严，属预期）。

### 真实环境调试

先开开关（`application.yml` 设 `rag.answer-cache.enabled: true` 重启 backend），同 query 打两次 retrieve：
```bash
# 第一次：SUPPORTED，耗时数秒
# 第二次：trace verdict=CACHE_HIT，~200ms
```

psql 看 cache 行：
```sql
SELECT id, scope_user_id, permission_signature, usage_count, decay_at,
       left(query_canonical,30) AS query
FROM rag_answer_cache ORDER BY id DESC LIMIT 5;
```

trace 验证：
```sql
SELECT verdict, latency_ms FROM rag_retrieval_logs WHERE verdict='CACHE_HIT';
```

**测完后还原**：`rag.answer-cache.enabled: false` 重启 + `DELETE FROM rag_answer_cache`。

---

## 八、三模式集成 CHAT / AGENT / WORKFLOW（阶段 5）

### 是什么

RAG 证据注入三个入口：
- **CHAT**：会话绑 kbIds + ragEnabled 开 → `resolveRagForChat` 调 `retrieveEvidence`，证据当 system 消息注入，生成后追加 citation disclaimer。
- **AGENT**：`agent_kb_bindings` 连表定 scope → `AgentRoutingStrategy` 首步注入证据 systemPrompt（prepend，保留 Agent 人设）。
- **WORKFLOW**：检索节点（RETRIEVAL）经 sidecar 回调 Java，`nodeConfig kbIds ∩ 用户` → `retrieveEvidence` → 证据作为节点 output.text 供下游。
- `RagScopeResolver` 做 **P4 求交**：所选 kbIds ∩ 用户可见集 ∩ 同 embedding_model。

### 大白话

三种用法都能接知识库：纯对话里绑库、Agent 挂库、工作流里放个检索节点。不管哪种，系统都会算"你要查的库 ∩ 你有权限看的库 ∩ 向量模型一致的库"，交集才是真正去查的范围。

### 案例

- CHAT：对话绑 KB1 → 回答带 `[1]` 引用。
- AGENT：Agent 绑 KB1 + config ragEnabled=true → 首步答案含证据。
- WORKFLOW：START→检索→END，检索节点配 KB1 → sidecar 回调 Java → 节点输出证据。

### 真实环境调试

**CHAT**（需起 backend，sidecar 可 mock）：
```bash
# 建会话绑 kbIds + ragEnabled=true（中文用文件）
cat > session.json <<'EOF'
{"title":"rag测试","mode":"CHAT","kbIds":[1],"ragEnabled":true}
EOF
curl -X POST http://localhost:8080/api/chat/sessions \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  --data-binary @session.json
# 发消息（流式）看是否带引用

# oracle: trace 行数
SELECT count(*) FROM rag_retrieval_logs WHERE mode='CHAT';
```

**AGENT**：
```bash
# 绑 agent_kb_bindings
curl -X PUT "http://localhost:8080/api/agents/4/kb-bindings" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d "{\"kbIds\":[1]}"
# 开 Agent ragEnabled
curl -X PUT "http://localhost:8080/api/agents/4/rag-enabled" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d "{\"enabled\":true}"
# Agent 模式会话发消息，验 trace mode='AGENT' +1
```

**WORKFLOW**（需起 sidecar）：
```bash
# 造 START→RETRIEVAL→END 工作流 + workflow_kb_bindings[1] + rag_enabled=true
curl -X POST "http://localhost:8080/api/workflows/<wfId>/run" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d "{\"input\":{\"message\":\"如何安装部署系统\"}}"
# 期望: EXECUTION_COMPLETED，RETRIEVAL NODE_COMPLETED 带证据
```

**检索节点 query 模板渲染（脱离点 1 已修 2026-06-23）**：
检索节点的「查询」配置现在支持 `{{上游别名.输出键}}` 模板，后端 `executeRetrieval` 过 `renderTemplate` 渲染（与 SKILL/AGENT_REF 一致）。

- 前端：选中检索节点 →「查询」框按 `/` 弹上游变量菜单 → 点插 `{{start.message}}`（上游须是 INPUT 节点或带 `inputKey` 的 START，sidecar 把输出按 `别名.输出键` + 平铺键合并进 input）。
- 后端验证（query 被渲染成真实输入，不是字面量 `{{start.message}}`）：
  ```sql
  SELECT query_text FROM rag_retrieval_logs ORDER BY id DESC LIMIT 1;
  -- 期望: "如何安装部署系统"，不是 "{{start.message}}"
  ```
- 反例：写错别名 `{{wrong.message}}` → 引用不存在的变量，`renderTemplate` 保留原 token → `query_text` 仍是字面量 `{{wrong.message}}`（一眼看出别名写错）。
- 留空 query 仍回退上游 `input/message/prompt/text` 平铺键（兜底不变）。
- 单测：`mvn test -Dtest=RuntimeNodeCallbackServiceTest`（含 `..._rendersRetrievalQueryTemplateFromUpstreamAliasOutput` + `..._fallsBackToUpstreamMessageWhenRetrievalQueryBlank`）。

**P4 负例**：绑一个无权限的 KB → 空集 → abstain NO_VISIBLE_DOCS（需无权限用户，admin 做不了真负例）。

---

## 九、记忆模式开关（ragEnabled 4 层门控）

### 是什么

- **opt-in「记忆模式」开关**：关 = 纯裸聊（零外部上下文）；开 = RAG 证据 + 用户长期记忆（+预留 answer_cache）。
- **4 层优先级**：session > agent/workflow > global；默认关。
  - 全局：`system_settings` 行 `rag.memory.enabled`（默认 false）。
- **记忆处理模式**（全局 `rag.memory.process-mode`，默认 `ASYNC`，V30）：`ASYNC`=processMemory 全异步后台（答完即 DONE 不卡顿，冲突走记忆面板 PENDING）；`HYBRID`=同步（答完卡 ~5s，即时 askText 追问冲突）。设置页「RAG/记忆」tab 下拉切。
  - 会话：`chat_sessions.rag_enabled`（null=继承）。
  - Agent：`agents.config` JSONB 键 `ragEnabled`。
  - Workflow：`workflows.rag_enabled`。
- `RagModeResolver` 按 `resolve(mode, sessionRagEnabled, agentId, workflowId)` 解析单个 boolean。
- false 时跳过：RAG 证据注入 + 记忆注入 + 记忆抽取全部 6 处。

### 大白话

"记忆模式"是个总闸（默认关）。关着的时候 AI 就是纯净模式，不查库、不记你说过啥。开着才会查知识库 + 把你的信息抽成长期记忆。这个闸可以在全局、某个 Agent、某个工作流、某次对话四个层面分别设，越具体的越优先。

### 案例

- 全局关 + 会话开 → 这次对话带 RAG + 记忆。
- 全局开 + 会话关 → 这次对话裸聊（会话覆盖全局）。

### 真实环境调试

```bash
# 看全局
curl http://localhost:8080/api/system/settings/rag-memory -H "Authorization: Bearer <token>"
# 开全局（需 role:manage）；processMode: ASYNC=全异步不卡(默认) / HYBRID=同步即时冲突追问
curl -X PUT http://localhost:8080/api/system/settings/rag-memory \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d "{\"enabled\":true,\"processMode\":\"ASYNC\"}"

# workflow 级
curl -X PUT "http://localhost:8080/api/workflows/<wfId>/rag-enabled" \
  -H "Authorization: Bearer <token>" -d "{\"enabled\":true}"
# agent 级
curl -X PUT "http://localhost:8080/api/agents/<agentId>/rag-enabled" \
  -H "Authorization: Bearer <token>" -d "{\"enabled\":true}"
```

psql：
```sql
SELECT setting_key, setting_value FROM system_settings WHERE setting_key IN ('rag.memory.enabled','rag.memory.process-mode');
SELECT id, rag_enabled, kb_ids FROM chat_sessions ORDER BY id DESC LIMIT 5;
SELECT id, rag_enabled FROM workflows ORDER BY id DESC LIMIT 5;
SELECT id, config FROM agents ORDER BY id DESC LIMIT 5;  -- config JSONB 里看 ragEnabled
```

**验证门控**：全局关 + 发消息 → `rag_retrieval_logs` 无新行（RAG 跳过）+ `user_memories` 无新行（抽取跳过）；开了 → 各有新行。

> ⚠️ 注意配合读《集成脱离分析报告》脱离点 3/4/5/6：当前会话默认关 + 前端钉死 + 不回读等坑，调试时心里有数。

---

## 十、用户长期记忆抽取（processMemory · ASYNC/HYBRID 可配）

> ⚠️ 注意：早期 RAG 冒烟用的是 `extractMemoriesAsync`（异步、按 key upsert），**已被个人记忆重构替换**。当前线上是 `MemoryService.processMemory(...)`（V27/V28 + judge 调优 + V29/V30 异步化）。调用模式由全局 `rag.memory.process-mode` 决定。本节按线上态写。

### 是什么

- 记忆模式 ON 时，每轮回复生成后跑 `processMemory`（[MemoryService.java](backend/src/main/java/com/superprogrammer/chat/service/MemoryService.java)）；**调用模式由 `rag.memory.process-mode` 决定**：`ASYNC`(默认)= fire-and-forget 后台(boundedElastic)，答完即 DONE 不卡；`HYBRID`= 同步阻塞流末拿 askText(卡 ~5s)。
- 流程：① `judge.extract(...)` 一次 LLM 抽出 K 条事实（含 block 候选）→ ② 每条 `classifier.classify(...)` 调 embed 算向量 + 近邻归块（block_label）→ ③ 同块已有成员则 `judge.judge(...)` 判冲突 → ④ 无冲突 / 新块 → clean 入库；有冲突 → 进冲突流程（见第十一节）。
- 每条记忆 `source = INFERRED`（恒定，**无用户主动录入入口**）、`confidence` 由 LLM 给。
- 注入 `buildMemoryContext` 只取 `confidence ≥ 0.5`；**FLAGGED（带 conflict_id）的记忆也注入**，加 `[⚠️冲突]` 前缀（见矛盾点 14）。
- judge/extract/route：temp **0.0** + **Jackson 解析**（[MemoryConflictJudge.java:40](backend/src/main/java/com/superprogrammer/chat/service/internal/MemoryConflictJudge.java#L40)）。
- **reactor nio 禁 block**（V29 修复）：streaming 路径跑在 `reactor-http-nio` 线程，禁 `WebClient.block()`；[ChatSessionService](backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java) 两处 `concatWith(Flux.defer).subscribeOn(Schedulers.boundedElastic())` 把 processMemory 切到 boundedElastic 才合法（否则 `IllegalStateException: block()... not supported in thread reactor-http-nio`，extract 必炸、记忆不落）。
- **防竞态**（V29）：`uk_user_memories_user_key_clean` 部分唯一索引（clean 同 user 同 memory_key 唯一，FLAGGED 可共存）；`insertClean` catch 兜底，并发/重抽同 key 第二条被 DB 拒。
- **去重**：embed 前按 (userId, memoryKey, value) 预去重跳过（extract 每轮重抽历史 facts 不重复 embed）+ 归块后按同块 members 二次去重。
- **extract 重试**：`MemoryConflictJudge.chat()` LLM 调用 3 次重试（返空/异常均重试），治间歇失败。

### 大白话

AI 会自动从你聊天的内容里提炼"你是谁、你喜欢啥"，存成你的个人档案。下次对话把相关记忆喂回去，让 AI 记得你。比如你说"我叫张三、爱用 Java"，它就存下来。默认 **ASYNC 全异步**——AI 回完后记忆抽取在后台跑，不卡回复；要即时冲突追问可切 **HYBRID**（答完卡几秒）。

### 案例

记忆模式开 + CHAT 发"我叫张三，28岁，后端工程师，爱用 Java" → 抽 4 条事实 → 各自归块（name/age→基本信息，occupation→职业，favorite_language→偏好）→ clean 入库 4 行（全 conf 1.0，source=INFERRED）。会话 ragEnabled=false → processMemory 不跑 → 0 新行。

### 真实环境调试

```bash
# 全局或会话开记忆模式(见第九节)，发消息(ASYNC 默认后台不卡；HYBRID 同步卡几秒属正常)
# 然后查记忆（自服务，按 current userId 隔离，无需权限）
curl http://localhost:8080/api/chat/memories -H "Authorization: Bearer <userToken>"
```

psql：
```sql
SELECT category, memory_key, memory_value, confidence, source, block_label, conflict_id
FROM user_memories WHERE user_id=<uid> ORDER BY updated_at DESC;
```

**负例**：会话 `ragEnabled=false` → 发同样消息 → `user_memories` 0 新行（processMemory 未跑）。

### 2026-06-24 记忆链路调优补丁（全异步 + 防竞态 + key 归一 + 去重）

> 对应本次调试修的一串问题：streaming 记忆永不落 / 卡顿 / 并发重复 / education 多 key 共存。

- **process-mode 双模式（非硬编码，V30 接好）**：`streamChat`/`streamWorkflow` 读 `systemSettingService.getMemoryProcessMode()` 分支。`ASYNC`(默认) → `Mono.fromRunnable(...).subscribeOn(Schedulers.boundedElastic())` 切弹性线程 fire-and-forget → 答完即 DONE 不卡顿（旧同步 20–60s → 现约等于纯生成耗时），冲突走面板 PENDING；`HYBRID` → 同步阻塞流末跑 `processMemory`，askText 直接 append 进回复（即时追问，卡 ~5s）。切法：设置页下拉或 `PUT /api/system/settings/rag-memory {processMode}`。
- **防竞态（[V29](backend/src/main/resources/db/migration/V29__user_memories_partial_unique.sql)）**：`uk_user_memories_user_key_clean` 部分唯一索引 = clean 同 user 同 key 唯一，FLAGGED 可共存。异步并发插重复 → DB 兜底拦 + [insertClean](backend/src/main/java/com/superprogrammer/chat/service/MemoryService.java) catch 跳过。
- **去重**：embed 前按 `(user_id, key)` 查同 key，同 value 已存 → 跳过（省 embed；extract 每轮重抽历史不再重复 embed/插）。
- **key 归一（双保险）**：LLM 抽 education/兴趣等常造变体（`child_stage`/`child_school_stage`/`child_grade`/`child_education_stage`）→ 同概念多 key 不判冲突 → 共存（小班/中班/大班都在）。① [EXTRACT_PROMPT](backend/src/main/java/com/superprogrammer/chat/service/internal/MemoryConflictJudge.java#L53) 固定归一指引（教育→`child_education_stage`、爱好→`hobby`、孩子兴趣→`child_interest`）② MemoryService `KEY_ALIAS` alias 兜底（LLM 漏 prompt 时归一）。
- **extract 重试**：`MemoryConflictJudge.chat` LLM 调用 3 次重试（含返回空），治间歇返空导致偶发不落。
- **reactor block 修复**：streaming 路径 processMemory 原跑 `reactor-http-nio` 线程 → `LlmGateway.chat().block()` 被 reactor 禁 → extract 炸 → **永不落**。`subscribeOn(boundedElastic)` 切线程修。**复现必须打 streaming 端点**（non-stream servlet 线程不炸会掩盖 bug，本坑复现 1 次）。
- **可观测**：processMemory/extract raw 返回/classify vecLen 加 info 日志，链路黑盒可查（`grep processFacts backend-debug.log`）。

**待办状态（2026-06-24 真环境复核）**：
- ① ~~记忆处理模式可选 ASYNC/HYBRID~~ ✅ **已做（V30，原"硬编码"表述作废）**。process-mode 全链路接好（`systemSettingService.getMemoryProcessMode()` 读 `system_settings`），`streamChat`/`streamWorkflow` 分支：`ASYNC`(默认) → `Mono.fromRunnable.subscribeOn(boundedElastic)` fire-and-forget；`HYBRID` → 同步 `processMemory` 返 askText 内联追问。两端点真环境验通：ASYNC 跑 boundedElastic-2、冲突走面板 PENDING；HYBRID 回复末尾内联"你之前提到…现在改…"追问 + 生 PENDING。
- ② ~~resolve 点保留新~~ ✅ **已修+验**。根因：旧坏序 `insertSnap→hardDelete`（committed 0160063）插 new clean 时 old clean 还在 → 撞 V29 `uk_user_memories_user_key_clean` → 抛异常被 catch → 照写 RESOLVED → "成功但没变"。改 `hardDelete→insertSnap`（[MemoryConflictService.java:117](backend/src/main/java/com/superprogrammer/chat/service/MemoryConflictService.java#L117)）。验：user_memories **无 deleted 列**=硬删语义，KEEP_NEW 后 old 物理删、new clean 入、conflict→RESOLVED、状态守卫挡重 resolve、listActive 排除 resolved。
- ③ 多 pending 批量 resolve：`resolveAll` 已写 + 接端点 `POST /api/chat/memories/conflicts/batch-resolve`（返成功条数）。**未单独真环境验**，下轮补。

**性能观察**：开记忆模式前后对比同一 query 的回复延迟（trace `latency_ms` 或前端体感）；多事实消息延迟更高（N×embed+judge 串行）。

> 已知矛盾点（详见《集成脱离分析报告》矛盾点 12/14/19）：同步阻塞拖慢、FLAGGED 矛盾喂模型、记忆全 INFERRED 无主动录入。

---

## 十一、记忆冲突解决（MemoryConflictService）

### 是什么

- **embed 聚类分块**：每条记忆算向量，近邻 cosine ≥ `MEMORY_BLOCK_SIM_THRESHOLD`(0.6) 归同一 `block_label`（[MemoryBlockClassifier](backend/src/main/java/com/superprogrammer/chat/service/internal/MemoryBlockClassifier.java)）。
- **冲突判定**：新事实只跟**同 block_label** 的干净记忆比（`findCleanByBlock`），judge（LLM，temp 0.0 + Jackson）判语义矛盾。
- **会话锁交互解决**：冲突 → 建 PENDING（每会话唯一，[V27 唯一索引](backend/src/main/resources/db/migration/V27__memory_conflict_support.sql#L26)）+ 回复追加 askText 追问 → 用户下条消息经 route 判定 → resolve。
- **FLAGGED**：用户答无关 / PENDING 超 10min 懒过期 → 双版本共存打 `conflict_id` 标记，待手动 resolve。
- **resolve 决策**：`KEEP_NEW`（插新删旧）/ `KEEP_OLD`（丢新）/ `KEEP_BOTH`（都留 clean）/ `DISCARD`（删旧）。
- 2 端点：`GET /api/chat/memories/conflicts`（V29 起返 **PENDING+FLAGGED**，`MemoryConflictService.listActive`，PENDING 候选拼快照 new+old）+ `PUT /api/chat/memories/conflicts/{id}/resolve`（resolve 带 status 守卫，非 PENDING/FLAGGED 拒，防 RESOLVED 重复处理）。
- V27 加 `block_label/embedding/conflict_id` + `memory_conflicts` 表；V28 删旧 `unique(user_id,key)` 供冲突共存；V29 加部分唯一 `uk_user_memories_user_key_clean`（clean 唯一/FLAGGED 共存）+ listActive(PENDING 面板可见)；V30 加 `rag.memory.process-mode`(ASYNC/HYBRID)。

### 记忆配置（全部硬编码，**yml 里没有 `rag.memory.*`**）

调试调参要改代码重编译，无运行时钩子（[RagConfig.java:51-54](backend/src/main/java/com/superprogrammer/knowledge/service/RagConfig.java#L51-L54)）：

| 常量 | 值 | 含义 |
|------|-----|------|
| `MEMORY_EMBED_MODEL` | `doubao-embedding-vision` | 记忆聚类用 embedding 模型 |
| `MEMORY_BLOCK_SIM_THRESHOLD` | `0.6` | 归块门槛（doubao abs sim 偏低，可能偏严，见矛盾点 17） |
| `MEMORY_CONFLICT_EXPIRE_MIN` | `10` | PENDING 超时分钟数（超时懒转 FLAGGED） |
| `MEMORY_JUDGE_MODEL` | `doubao-seed-2.0-code` | 抽取/judge/route 用 chat 模型 |

### 大白话

你说"喜欢 Java"，过会又说"更喜欢 Python"——AI 发现矛盾了，不会偷偷覆盖，而是问你"到底要哪个"。你说保留新的，旧的删掉。如果你说的是无关的（比如突然聊天气），它判断不出关系，就把两条都留着打个标记，等你以后手动决定。

### 案例

- 场景1 KEEP_NEW：说"喜欢 Java"→ 存（block=偏好，clean）；说"更正更喜欢 Python" → 同块 judge 判冲突 → PENDING + askText 追问 → "保留 Python" → route 判定 KEEP_NEW → RESOLVED，Python 留 Java 删。
- 场景2 FLAGGED：Java → Python（PENDING）→ 顺嘴说"今天天气" → route 判无关 → 双行共存打 FLAGGED（同 conflict_id）→ 端点等手动 resolve。
- 场景3 超时：造 PENDING 后等 >10min（或临时改 `MEMORY_CONFLICT_EXPIRE_MIN=1` 重启加速）→ 发任意消息 → 懒转 FLAGGED。

### 真实环境调试

需记忆模式开（processMemory 同步路径上做冲突检测，**这轮会慢**）：
```bash
# 触发冲突（见案例发消息），等 askText 追问附在回复末尾
# 看冲突列表（仅 FLAGGED；PENDING 不返——PENDING=锁会话等行内答）
curl http://localhost:8080/api/chat/memories/conflicts -H "Authorization: Bearer <token>"

# 解决（decision: KEEP_NEW/KEEP_OLD/KEEP_BOTH/DISCARD）
curl -X PUT "http://localhost:8080/api/chat/memories/conflicts/<conflictId>/resolve" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d "{\"decision\":\"KEEP_NEW\"}"
```

psql：
```sql
-- 冲突状态机
SELECT id, status, resolution, block_label, left(ask_text,40) AS ask, expires_at
FROM memory_conflicts ORDER BY id DESC;
-- FLAGGED 记忆（带 conflict_id）
SELECT id, memory_key, memory_value, block_label, conflict_id
FROM user_memories WHERE conflict_id IS NOT NULL;
```

UI 路径：ChatView「记忆」抽屉 → 记忆冲突区 → 4 个解决按钮。

> 已知矛盾点（详见《集成脱离分析报告》矛盾点 13/15/16/17/18）：跨块漏判、锁忙降级 clean、FLAGGED 无通知、阈值硬编码、单会话单 PENDING。下一节给出真实环境验证步骤。

---

## 十一·补：个人记忆已知矛盾点的真实环境验证

> 对应《集成脱离分析报告》矛盾点 12–17。每个给"怎么在真实环境复现/观察"。

### 验证矛盾点 12（同步阻塞延迟）

1. 记忆模式 OFF，发一条消息，记下回复耗时 T0。
2. 开记忆模式（全局或会话），发同样消息，记耗时 T1。
3. 期望 T1 ≈ T0 + 20–60s（多事实消息更久）。psql 看是否抽了多条记忆：
   ```sql
   SELECT count(*) FROM user_memories WHERE updated_at > now() - interval '5 min';
   ```

### 验证矛盾点 13（跨块冲突漏判）

1. 发"我是后端工程师"（归"职业"块）。
2. 发"更正：我是前端工程师"——观察是否触发 askText。
3. 若两条被分到不同 `block_label`，judge 不会判冲突，两条 clean 共存：
   ```sql
   SELECT memory_key, memory_value, block_label, conflict_id FROM user_memories
   WHERE user_id=<uid> AND memory_value LIKE '%工程师';
   -- 若两行 block_label 不同 + conflict_id 全 NULL = 漏判复现
   ```

### 验证矛盾点 14（FLAGGED 注入模型）

1. 制造一个 FLAGGED 冲突（场景2）。
2. 开记忆模式发一条普通问题，抓注入上下文：检索 trace 或 debug 看 `buildMemoryContext` 输出。
3. 期望 system prompt 含 `[⚠️冲突] ...（与"X"冲突，待澄清）`（两条矛盾都进）。

### 验证矛盾点 15（锁忙降级 clean）

1. 造一个 PENDING（说 A → 说矛盾 B，触发 askText，**先别答**）。
2. 不答 PENDING，继续发一条新的矛盾事实 C。
3. 期望：C 被 clean 入库（无 conflict_id），后端日志有 `warn ... 降级 clean 入库（不打标）`：
   ```sql
   SELECT memory_key, conflict_id FROM user_memories WHERE user_id=<uid>;
   -- C 行 conflict_id=NULL（本该 FLAGGED 却 clean）
   ```
   后端日志 grep：`会话 .* 记忆锁忙`。

### 验证矛盾点 16（FLAGGED 无通知）

1. 制造 FLAGGED（场景2，不打开记忆抽屉）。
2. 继续正常对话若干轮。
3. 期望：对话里**无任何**"你有冲突待解决"提示；只有主动调 `GET /memories/conflicts` 或开抽屉才看到。

### 验证矛盾点 17（阈值 0.6 偏严）

1. 发两条语义相关但措辞不同的事实（如"我用 Java"和"我写后端"）。
2. 查归块：
   ```sql
   SELECT memory_key, memory_value, block_label FROM user_memories WHERE user_id=<uid>;
   -- 若 block_label 不同 = 没归到一块（相似度 < 0.6）
   ```
3. 调参须改 [RagConfig.java:52](backend/src/main/java/com/superprogrammer/knowledge/service/RagConfig.java#L52) `MEMORY_BLOCK_SIM_THRESHOLD` 重编译（如试 0.45）。

---

## 十二、一致性对账（阶段 7 · ReconciliationJob）

### 是什么

- **opt-in**（默认关）后台对账，`@Scheduled` 独立 executor。
- `scanKb` 聚合计数（drift/orphan/dead）+ 落 `knowledge_reconciliation_reports` 报告行。
- 清理：`purgeOrphanEmbeddings`（孤儿向量）/ `purgeDecayedAnswerCache` / `purgeDecayedMemoryFacts`（decay 过期行硬删）。
- **autoRepair**（opt-in，默认关）：drift → 入 REINDEX job → claimBatch 消费 → 重嵌修复（I2 复校 + I4 幂等 + 退避）。
- drift = node.content_hash 与向量行 hash 不一致；orphan = node 软删/ARCHIVED 但向量还在；dead = job 卡 DEAD。

### 大白话

后台定期"体检"知识库：查有没有"内容和向量对不上"的（漂移）、"该删没删的孤儿向量"、"卡死的任务"。发现问题能报告，开了自动修复还能自己重新向量化修好。还会清过期的缓存行。

### 案例

手动改某个 node 的 content_hash 但不动向量 → 制造 drift；对账扫到 → 报告 drift+1；开 autoRepair → 自动重嵌修好。

### 真实环境调试

临时开（`application.yml`）：
```yaml
rag:
  reconciliation:
    enabled: true
    auto-repair: true   # 想测自动修复才开
```

重启 backend，等 `rag.reconciliation.poll-ms`（默认 600000ms=10min，调试调小）或手动触发后：
```sql
SELECT kb_id, total_nodes, drift_count, orphan_count, dead_count, created_at
FROM knowledge_reconciliation_reports ORDER BY id DESC LIMIT 5;

-- 制造 drift 测 autoRepair：改 node hash 不动向量
UPDATE knowledge_nodes SET content_hash='manual_drift_test' WHERE id=<nodeId>;
-- 等下一轮 scan + REINDEX job
SELECT job_type, status, content_hash FROM knowledge_index_jobs WHERE job_type='REINDEX';
```

**测完还原**：`enabled: false` 重启 + 清测试报告 `DELETE FROM knowledge_reconciliation_reports`。

---

## 十三、前端 /knowledge 页（阶段 6 + 8 项收口）

### 是什么

单 `/knowledge` 路由 + `n-tabs`，4 个 tab：
1. **知识库管理**：KB 表格（全列）+ 行操作（文档/编辑/授权/删除，按 `knowledge:write` + `canManage` 门控）+ `KbFormModal`（新建/编辑）+ `KbPermissionModal`（授权）+ 文档抽屉 `DocumentManager`（拖拽上传 + 3s 状态轮询 + **INDEXED 行可展开查看拆分节点全文**，脱离点 7 已修）。
2. **检索调试**：`RetrievalDebugPanel`（kbId/maxL0/docTypes + query → 答案/引用/候选L0/证据L2/token预算/traceId+延迟）。
3. **RAG 问答**：`RagAskPanel`（KB 多选 + query → 流式答案 + 引用列表，消费 SSE CHUNK/CITATION/DONE）。
4. **检索审计**（`knowledge:manage`）：`RetrievalAuditPanel`（trace 分页表 + 过滤 + 详情抽屉 + 清理）。

### 大白话

一个页面管整个知识库：建库传文档、试检索、直接问答、管理员还能看检索日志审计。

### 案例

登录 → /knowledge → 知识库管理 tab 看到 KB 列表 + 授权按钮 → 检索调试 tab 输入问题看答案带引用 → RAG 问答 tab 流式问答 → 管理员额外看到检索审计 tab。

### 真实环境调试

起 frontend（5173），浏览器走 `/knowledge`。自动化用 **playwright-mcp**（按约定不用 camoufox）：
- 登录 → /knowledge → 各 tab 切换验证渲染。
- 检索调试：query「如何安装部署系统」→ SUPPORTED + `[1]` 引用 + 候选 L0 cosSim + token 预算。
- RAG 问答：选 KB + query → 流式答案 + 引用列表。
- 文档抽屉：拖拽上传 → 轮询 PENDING→…→INDEXED → **点 INDEXED 行左侧 ▶ 展开看拆分节点全文**（L0 摘要 + L2 原文，懒加载 `GET /documents/{docId}/nodes`，脱离点 7 已修）。

**类型检查**：`cd frontend && npm run build`（= `vue-tsc && vite build`）期望通过；或 `npx vue-tsc --noEmit`。

> ✅ **脱离点 7 已修（2026-06-23）**：`GET /documents/{docId}/nodes` 已接前端，INDEXED 文档行可展开看全文（L0 摘要 + L2 原文片段 + token）。快速 API 验证：
> ```bash
> curl -H "Authorization: Bearer <token>" \
>   http://localhost:8080/api/knowledge/documents/<docId>/nodes
> # 返回节点数组，每项含 content（L0 摘要 / L2 原文）。无 knowledge:read → 403。
> ```

---

## 十四、检索审计 + 记忆/冲突自服务（端点）

### 是什么

- **`/api/knowledge/retrieval-logs`**（`knowledge:manage`）：分页查 trace（filter userId/mode/时间；kbId 走 Java post-filter 精确判定）+ 行删 + 按时间批量清。
- **`/api/chat/memories`**（用户自服务，按 current userId 隔离，无权限要求）：列表/删单条/清空。
- **`/api/chat/memories/conflicts`** + `resolve`：FLAGGED 冲突列表 + 手动解决。

### 大白话

管理员能查"谁在什么时候查了什么、结果如何"的检索日志，还能清理。普通用户能管自己的记忆（看/删/清空）和待解决的冲突。

### 案例

管理员在检索审计 tab 看 27 条 trace，点详情看 token 预算/候选/证据 JSON；用户在对话页「记忆」抽屉看自己被抽的记忆、清空、解决冲突。

### 真实环境调试

```bash
# 检索审计（管理员）
curl "http://localhost:8080/api/knowledge/retrieval-logs?page=1&size=20" \
  -H "Authorization: Bearer <adminToken>"
curl -X DELETE "http://localhost:8080/api/knowledge/retrieval-logs?before=2026-06-16T00:00:00Z" \
  -H "Authorization: Bearer <adminToken>"   # 清 7 天前

# 记忆自服务（用户自己 token）
curl http://localhost:8080/api/chat/memories -H "Authorization: Bearer <userToken>"
curl -X DELETE http://localhost:8080/api/chat/memories/<memoryId> -H "Authorization: Bearer <userToken>"
curl -X DELETE http://localhost:8080/api/chat/memories -H "Authorization: Bearer <userToken>"  # 清空
```

---

## 十五、删除清理（doc 软删 → nodes + 向量清 · Gap-1 修复）

### 是什么

- `KnowledgeDocumentService.delete` 删文档：**同事务**软删 doc（@TableLogic deleted=1）+ **软删该 doc 全部 nodes**（deleted=1）+ **硬删对应向量行**（`deleteByDocument`，JOIN nodes 按 document_id；emb 表无 deleted 列本就硬删语义）。
- 发 `VisibilityInvalidationEvent` 失效可见集缓存。
- 检索安全：dense SQL `d.deleted=0` 滤软删 doc，删后重检索 candidates=0 abstain NO_DENSE_HITS。

### 大白话

删一篇文档，系统会一次性把它的"登记、拆出来的所有节点、所有向量"全清干净，不会留垃圾。删完再问相关问题，系统就查不到了，不会拿已删的内容瞎答。

### 案例

删 doc4 → nodes 全 deleted=1 + 向量 0 行（删前 1）+ 重检索 NO_DENSE_HITS。

### 真实环境调试

```bash
curl -X DELETE http://localhost:8080/api/knowledge/documents/<docId> \
  -H "Authorization: Bearer <token>"
```

psql 验三清：
```sql
SELECT deleted FROM knowledge_documents WHERE id=<docId>;            -- 1
SELECT count(*) FROM knowledge_nodes WHERE document_id=<docId> AND deleted=0;  -- 0
SELECT count(*) FROM knowledge_embeddings_doubao e
JOIN knowledge_nodes n ON e.node_id=n.id WHERE n.document_id=<docId>;         -- 0
```

重检索验证安全：同 query 再 retrieve → abstained=true, candidates=0。

**权限负例**：无 `knowledge:write`/manage → 拒，不触清理。

---

## 附录 A：调试完必做的"环境还原"

每次测完清测试数据、关 opt-in 开关，避免污染下次：
```bash
# 关开关（yml 改回 + 重启 backend）
# rag.visibility-cache.enabled / answer-cache.enabled / reconciliation.enabled → false
# rag.memory.enabled（全局）→ false（DB seed 默认）
```
```sql
DELETE FROM rag_answer_cache;
DELETE FROM rag_retrieval_logs;
DELETE FROM knowledge_reconciliation_reports;
DELETE FROM user_memories;
DELETE FROM memory_conflicts;
-- 删测试 KB/doc/workflow
```
- Agent.config 清成 `{}`、workflow rag_enabled 置 NULL。
- 停 sidecar（除非要继续测工作流）。

## 附录 B：常见坑速查

| 坑 | 现象 | 解法 |
|----|------|------|
| 中文 curl 报 `Invalid UTF-8 middle byte` | GBK 编码 | body 写 UTF-8 文件，`--data-binary @file` |
| `mvn spring-boot:run` 编译失败（陈旧测试） | test-compile 红灯 | 阶段7 已修；若仍遇加 `-Dmaven.test.skip=true` |
| test-embed 报错/维度不对 | Ark key 没录或 provider.models/KB.embeddingModel 不一致 | admin UI 录 key；`doubao-embedding-vision` 三处（provider.models / KB.embeddingModel / 真实 ep-id）必须同时一致 |
| 向量入库失败 `updated_at 字段不存在` | 历史 mapper 误引 | 已修（重嵌就地覆盖，无 updated_at） |
| trace 静默丢（`l2_lexical_fallback NOT NULL`） | CACHE_HIT / 多KB 路径短路没设该字段 | 已修；复发 3 次，写 trace 前必设 false |
| H2 跑不了 pgvector | halfvec/HNSW/tsvector 不支持 | 集成测用真 PG（库 `agent_platform_it`，`mvn test -Dsurefire.excludedGroups=`） |
| @Data 实体 mock 串台 | `eq()` 跨实例相等 | 用 `same()` 恒等（见 memory `reference-rag-test-infra`） |
| 记忆模式开了回复卡顿 | processMemory 跑 N×(embed+judge) 阻塞流末 | 默认 `process-mode=ASYNC` 全异步不卡；卡了查是否被切 HYBRID，或改回 ASYNC |
| 记忆配置改了不生效 | `rag.memory.*` 不在 yml，4 常量全硬编码 | 改 [RagConfig.java:51-54](backend/src/main/java/com/superprogrammer/knowledge/service/RagConfig.java#L51-L54) 重编译 |
| 特型列（jsonb/halfvec/bigint[]）读写失败 | MP 默认 handler 不支持 | 标量列 `@Select` + `::text`/`array_to_string` 专法读；定向 update 不走 updateById |
| `bigint[]` 字面量被 PG 拒 | `[13, 14]` 方括号+空格 | 用 `{13,14}` 花括无空格（见个人记忆进度文档 bug#3） |
| route 把"保留 Python"读成保旧 | KEEP_NEW/OLD 对 LLM 晦涩 | A=旧/B=新 候选标签（judge 调优已修，见 bug#4） |
| `./mvnw spring-boot:run` 报找不到命令 | backend 无 Maven wrapper（`mvnw`/`mvnw.cmd` 都不存在） | 用全局 `mvn spring-boot:run`（仓库未提供 wrapper，0_快速启动.md 已更正） |
| 前端「看不到新功能 / 按钮缺失」 | backend 不托管前端 dist，访问了 8080 或 dev 没起 | `cd frontend && npm run dev`，开 **http://localhost:5173**（非 8080），Ctrl+Shift+R 硬刷 |
| 记忆面板某列(如 value)看不到内容 | naive `n-data-table` 该列无 `width` + 表格有 `fixed` 列 → 进 scroll-x → 无 width 列塌缩 0 宽(DOM 有文本但肉眼不见) | 每列都设 `width`(值列 `width:200`) |
| streaming 发消息记忆永不落(extract 炸 `block()... not supported in thread reactor-http-nio`) | processMemory 跑在 reactor-http-nio 线程,内部 `LlmGateway.chat().block()` 被 reactor 禁 | `concatWith(Flux.defer).subscribeOn(Schedulers.boundedElastic())` 切线程;复现必须打 streaming 端点(non-stream servlet 线程不炸会掩盖) |
| 异步并发记忆重复(同 user 同 key 多条 clean) | 多个 processMemory 并发互相看不到对方刚插的行 | V29 `uk_user_memories_user_key_clean` 部分唯一索引兜底 + `insertClean` catch;clean 同 user 同 key 唯一,FLAGGED 可共存 |
| 记忆面板看不到冲突记忆(改名/改值后"没记住") | 冲突进 PENDING 锁会话,旧版面板只显 FLAGGED | V29 起 `listActive` 返 PENDING+FLAGGED,面板显「待你确认」+「新」标 |
| 记忆 education/兴趣抽多 key 共存(小班/中班/大班都在,改名没替换;大班直接丢) | LLM extract 抽 key 每轮变(child_stage/school_stage/grade/education_stage),同概念不同 key 不判冲突;新值撞同 key unique 被拦丢 | key 归一:EXTRACT_PROMPT 固定归一 + MemoryService KEY_ALIAS alias 兜底 |
| resolve 点保留新"提示成功但记忆没变/冲突还在" | ~~(待修)~~ ✅**已修+验(2026-06-24)**：旧坏序 `insertSnap→hardDelete` 撞 V29 unique 被吞异常，改 `hardDelete→insertSnap`；user_memories 无 deleted 列=硬删，KEEP_NEW old 物理删 new clean 入 | 见上「十节·待办状态②」 |
