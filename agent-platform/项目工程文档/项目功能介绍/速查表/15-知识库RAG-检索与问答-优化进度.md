# 知识库 RAG 召回升级 — 优化进度（治"换说法就召回不到"）

> 单一进度源。背景/已做/待做/续做全在此。速查表 [15-知识库RAG-检索与问答](15-知识库RAG-检索与问答.md) 的"待增修改 1"指向本文件。
> 方案原始版：`C:\Users\Administrator\.claude\plans\sharded-conjuring-wozniak.md`（已批准）。

## 背景 + 实测 bug

当前 RAG 召回是**单信号**：只靠 L0 section 摘要向量的余弦相似度，撞 `abstainThreshold=0.5` 硬悬崖拒答。playwright + 真 PG trace 实测（smoke-kb）：

| query | best L0 cosSim | 改造前 verdict |
|---|---|---|
| 如何安装部署系统 | 0.5005 | SUPPORTED（踩线） |
| 如何安装部署我的系统 | 0.4965 | LOW_CONFIDENCE 拒答 |

加"我的"把相似度从 0.5005 拉到 0.4965，差 0.004 跌下悬崖 → 拒答。"能命中"那条本是运气。

根因 5 条叠加：① 无 query 改写步（`rewrittenQuery=query` 原样）；② 拒答 0.5 硬悬崖无灰区；③ BM25 用 `'simple'` 不分中文，词法兜底死（`l2_lexical_fallback=false`）；④ L1 文档元数据（outline/rules）从不参与召回，只注入 prompt；⑤ 小库放大（smoke-kb 仅 1 L0）。

## 锁定设计决策

- **中文分词** = Java-jieba + simple tsvector（非 zhparser/pg_trgm）。效果≈zhparser、零装机风险、复用 PG FTS。
- **不接 cross-encoder rerank**（升级启发式精排）。
- **跳过 L2 向量通道**（长文档细节命中场景少，省索引成本）。
- 目标：召回天花板拉满，换说法不漏。

## Phase1 ✅ 已落地验证（2026-06-27，无迁移）

**多路扩展 + 多 qvec L0 召回 + 软拒答 + lowConfidence。**

### 新文件
- [QueryExpansionService.java](../../backend/src/main/java/com/superprogrammer/knowledge/service/QueryExpansionService.java) — 1 次 LLM chat 生成 K 释义 + 1 HyDE 假想答案，各 embed → 返回 `ExpandedQuery{canonicalQuery, qHalfs}`（规范 query 第一个，作缓存键）。失败/关闭降级单 query。servlet-sync。
- [RrfFusion.java](../../backend/src/main/java/com/superprogrammer/knowledge/service/internal/RrfFusion.java) — 纯函数 RRF 融合（`fuse`/`fuseWeighted`/`sortByScoreDesc`）。Phase1 未用（单通道 max-sim 更合适），Phase2/3 跨通道接。
- [RagRecallProperties.java](../../backend/src/main/java/com/superprogrammer/knowledge/config/RagRecallProperties.java) — `@ConfigurationProperties(prefix="rag.recall")`（仿 [AnswerCacheProperties](../../backend/src/main/java/com/superprogrammer/knowledge/config/AnswerCacheProperties.java)）。旋钮：`expansion.{enabled,count=2}`、`hyde.enabled`、`rrf.{k=60,weightL0Vector/l1Vector/bm25}`、`abstain.{hard=0.30,soft=0.45}`、`rerank.{weightRrf/parentSim/lexical}`、`jieba.{enabled,userDict}`。

### 改文件
- [RagRetrievalService.java](../../backend/src/main/java/com/superprogrammer/knowledge/service/RagRetrievalService.java)：
  - `retrieve()`/`retrieveEvidence()` 的 B4 单 `embed` → `queryExpansionService.expand`（返回多 qHalfs）。retrieveEvidence 扩展在 per-KB 循环前做一次（query 级非 KB 级）。
  - step5 新增 `multiDenseRecallL0`：多 qvec 各跑 `denseRecallL0`，按 nodeId 去重保留 max 余弦 sim，按 sim 降序。
  - abstain 单 0.5 悬崖 → hard0.30/soft0.45 双阈：`<hard` 拒答 LOW_CONFIDENCE；`[hard,soft)` 灰区照回答但 `vo.lowConfidence=true` 且**不写缓存**；`≥soft` SUPPORTED。
  - 缓存键仍用规范 query `qHalfs.get(0)`（[AnswerCacheService](../../backend/src/main/java/com/superprogrammer/knowledge/service/internal/AnswerCacheService.java) 不动，命中率/正确性保）。
- [RagRetrieveVO.java](../../backend/src/main/java/com/superprogrammer/knowledge/dto/RagRetrieveVO.java) — 加 `boolean lowConfidence`。
- [RagConfig.java](../../backend/src/main/java/com/superprogrammer/knowledge/service/RagConfig.java) — **原样未动**（abstainThreshold/bm25BoostMax 保留 legacy，新代码读 RagRecallProperties）。

### 测试
- 新 [RrfFusionTest](../../backend/src/test/java/com/superprogrammer/knowledge/service/internal/RrfFusionTest.java)、[QueryExpansionServiceTest](../../backend/src/test/java/com/superprogrammer/knowledge/service/QueryExpansionServiceTest.java)。
- 改 [RagRetrievalServiceTest](../../backend/src/test/java/com/superprogrammer/knowledge/service/RagRetrievalServiceTest.java)：构造加 2 mock（QueryExpansionService + real RagRecallProperties）；`b4_queryEmbeddedExactlyOnce` → `b4_queryExpandedExactlyOnce_embedDelegatedToExpansion`（verify expand times(1)、embed 不直调）；`a2_lowConfidenceAbstains` 拆 `hardAbstain`(sim0.2<0.30→LOW_CONFIDENCE) + `grayZone`(sim0.40∈[0.30,0.45)→回答+lowConfidence)。
- `mvn test` 全绿（含全量 mock 无回归）。**未跑 IT**（Phase1 无新 SQL，无需真 PG）。

### 验证（live，smoke-kb）
- ⚠️ 当前 backend = 自打 fat jar **PID 25516**（`java -jar target/agent-platform-0.1.0-SNAPSHOT.jar`），**非用户 IDE 进程**（原 PID 41644 已 kill）。日志 `backend/runtime.{out,err}.log`。
- `如何安装部署我的系统` **LOW_CONFIDENCE 拒答 → SUPPORTED**（best sim 仍 0.4965，靠降阈 0.45 通过）。
- `如何安装部署系统` 仍 SUPPORTED（0.5005→0.5021，无回归）。
- **机制诚实记录**：本 case 主靠降阈（0.4965≥0.45）；多 qvec 在单节点 KB 增益小，大库/硬改写才显效。
- 代价 latency 5.4s→18.8s（+1 LLM 改写 +4 embed），`rag.recall.expansion.enabled=false` 可关。

---

## Phase2 ✅ 已落地验证（2026-06-27，jieba-BM25 词法兜底，迁移 V35）

救活死的词法召回（`l2_lexical_fallback=false`）。

### 步骤 / 文件（9/9 全完成）
- [x] 1. `pom.xml` 加 `com.huaban:jieba-analysis:1.0.2`（Apache-2.0，Maven Central，无 native 依赖）。
- [x] 2. 新 [JiebaTokenizer.java](../../backend/src/main/java/com/superprogrammer/knowledge/util/JiebaTokenizer.java)：单例分词，`tokenize(String)→空格拼串`。用户词典未接（YAGNI）。
- [x] 3. [KnowledgeNode.java](../../backend/src/main/java/com/superprogrammer/knowledge/entity/KnowledgeNode.java) 加 `contentTokens` 字段。
- [x] 4. [KnowledgeNodeWriter.java](../../backend/src/main/java/com/superprogrammer/knowledge/service/KnowledgeNodeWriter.java) `buildNode` 内 `setContentTokens(JiebaTokenizer.tokenize(content))`（L119）。
- [x] 5. [RagRetrievalQueryMapper.java](../../backend/src/main/java/com/superprogrammer/knowledge/mapper/RagRetrievalQueryMapper.java) 加 `bm25HitsJieba`。
- [x] 6. [RagRetrievalService.gatherL2Candidates](../../backend/src/main/java/com/superprogrammer/knowledge/service/RagRetrievalService.java) `bm25Hits` → `bm25HitsJieba(kbId, JiebaTokenizer.tokenize(query), docIds)`。
- [x] 7. 回填端点 `POST /api/knowledge/admin/backfill-tokens`（`@RequirePermission("knowledge:manage")`）。
- [x] 8. 测：[JiebaTokenizerTest](../../backend/src/test/java/com/superprogrammer/knowledge/util/JiebaTokenizerTest.java) + [RagRetrievalQueryMapperIT](../../backend/src/test/java/com/superprogrammer/knowledge/mapper/RagRetrievalQueryMapperIT.java)。
- [x] 9. 编译 + 重打 jar + kill PID 25516 + 重启（新 PID **5500**）+ 回填 smoke-kb（2 节点）+ 验证。

### ⚠️ 关键设计修正：bm25HitsJieba 用 OR 语义（非 AND）
原计划 `plainto_tsquery('simple', 整串)` 是 **AND**：query "如何 安装 部署" → `如何 & 安装 & 部署`，节点缺"如何"→ 全丢，治不了"换说法"。实测 IT 复现此 bug。
**改为 per-token OR**：`EXISTS (unnest(string_to_array(query,' ')) tok WHERE tsv @@ plainto_tsquery('simple', tok))`，bm25_rank = 命中 token 的 ts_rank 之和。任一词命中即召回（召回优先），多命中靠前。逐 token 用 `plainto_tsquery` 安全（无 `| &` 操作符注入风险）。空 query → `['']`→ `@@` 不命中→空返回。

### 步骤 7 偏差（已记）
未加进 KnowledgeNodeController（其 base `/api/knowledge/documents` 会让 URL 变 `/documents/admin/...`），新建 [KnowledgeAdminController](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeAdminController.java)（`/api/knowledge/admin`）保 URL 干净。`KnowledgeNodeService.backfillContentTokens()`：`content_tokens IS NULL AND status=ACTIVE` 节点逐条 `tokenize(content)` UPDATE。

### 测试
- `mvn test`：**330/330 全绿**（JiebaTokenizerTest 3、RagRetrievalServiceTest 6 — mock 改 `bm25HitsJieba`）。
- `mvn test -Dsurefire.excludedGroups= -Dtest=RagRetrievalQueryMapperIT`：**3/3 全绿**（真 PG agent_platform_it）——命中换说法 chunk / 无关词空 / 未回填 NULL 优雅降级。
- ⚠️ IT 写法坑：Maven 默认 GBK 源码编码 → SQL 字面量中文 mojibake（PSQLException 语法错）。**所有中文走 JdbcTemplate `?` 参数绑定**，KB/doc title 用 ASCII；KeyHolder 在 PG 返全行多列 → 改 `queryForObject ... WHERE name=?` 取 id。

### 验证（live，smoke-kb，PID 5500）
- 回填：admin/admin123 登录 → POST `/api/knowledge/admin/backfill-tokens` → `data=2`（smoke-kb 2 节点），二次调返 0（幂等 ✅）。
- retrieve "如何安装部署我的系统"：**SUPPORTED**（best L0 sim 0.4976 ≥ soft 0.45，无回归），latency 15.9s。
- smoke-kb L2 content_tokens 含"安装/系统"→ jieba 分词 OR 可命中（手动验 tsv 内容；mapper 真实命中由 IT 3/3 覆盖，psql CLI 内联中文会 mojibake 不能直跑）。
- **机制诚实记录**：`l2_lexical_fallback` 在 smoke-kb **仍=false**——该 flag 仅在"纯 BM25 候选（L2 父节点不在 top-M L0 召回内）入 pool"时翻 true；smoke-kb 仅 1 L0，所有 L2 都是其子节点 → 永远 parent-anchored → BM25 命中重叠既有子节点 → 不翻。**属 smoke-kb 结构限制，非代码 bug**；大库（L0 漏召回父、但 L2 含关键词）才显效。Legacy `bm25Hits`（raw `content_tsv`，simple 中文弱）mapper 方法保留未用，待清理。


---

## Phase3 ⏳ 待做（L1 向量通道，迁移 V36）

召回从不参与召回的 L1 文档元数据（doc 级语义锚，对措辞远比 chunk 稳）。

### 迁移 `V36__l1_doc_embeddings.sql`
```sql
CREATE TABLE knowledge_doc_embeddings_doubao (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL UNIQUE REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    kb_id BIGINT NOT NULL,
    embedding_model VARCHAR(64) NOT NULL DEFAULT 'doubao',
    embedding halfvec(2048) NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_docemb_hnsw ON knowledge_doc_embeddings_doubao USING hnsw (embedding halfvec_cosine_ops);
CREATE INDEX idx_docemb_kb ON knowledge_doc_embeddings_doubao(tenant_id, kb_id);
ALTER TABLE knowledge_index_jobs ADD COLUMN document_id BIGINT;  -- UPSERT_L1 job 用
```

### 步骤 / 文件
1. 新 `entity/KnowledgeDocEmbedding.java` + `mapper/KnowledgeDocEmbeddingMapper.java`（`upsert`/`deleteByDocument`，仿 [KnowledgeEmbeddingMapper](../../backend/src/main/java/com/superprogrammer/knowledge/mapper/KnowledgeEmbeddingMapper.java)，`#{halfvec}::halfvec` + `ON CONFLICT(document_id)`）。
2. `dto/RagQueryRow.java` 加 `L1RecallRow{documentId, title, cosineDistance}`。
3. [RagRetrievalQueryMapper](../../backend/src/main/java/com/superprogrammer/knowledge/mapper/RagRetrievalQueryMapper.java) 加 `denseRecallL1`（FROM knowledge_doc_embeddings_doubao JOIN knowledge_documents，无 n.level 过滤，`&lt;=` 转义）。
4. `entity/KnowledgeIndexJob.java` 加 `documentId`；[KnowledgeNodeWriter](../../backend/src/main/java/com/superprogrammer/knowledge/service/KnowledgeNodeWriter.java) 加 `buildL1UpsertJob(doc,kbId)`（job_type=`UPSERT_L1`，document_id=doc.id，idempotency_key=sha256(docId:l1hash:UPSERT_L1)），writeNodes 末尾插。
5. [IndexJobTxService](../../backend/src/main/java/com/superprogrammer/knowledge/service/internal/IndexJobTxService.java)：`claimBatch` 的 `.in(jobType)` 加 `UPSERT_L1`；`markDocIndexedIfDone`/`countPendingRunningByDoc` 含 UPSERT_L1（否则 doc 提前 INDEXED）；加 `completeUpsertL1`（reverify knowledge_documents，upsert doc 向量表）。
6. [IndexJobWorker.process](../../backend/src/main/java/com/superprogrammer/knowledge/service/IndexJobWorker.java) 按 job_type 分支：`UPSERT_L1` 读 l1_metadata → 拼 L1 文本（`summary+"；"+join(outline,"；")+"；"+join(importantRules,"；")`，复用 `loadL1` 同款 join）→ embed → completeUpsertL1。
7. [RagRetrievalService](../../backend/src/main/java/com/superprogrammer/knowledge/service/RagRetrievalService.java) `gatherL2Candidates` + 多 KB 循环：每 qVec 调 `denseRecallL1`，命中 doc 喂进 top-D 扩展；用 `RrfFusion` 跨通道（L0 向量+L1 向量+jieba-BM25）融合，L1 命中按 documentId 去重再展开子节点。
8. 删 doc 路径加 `knowledgeDocEmbeddingMapper.deleteByDocument`；重解析触发 UPSERT_L1（l1_hash 变→新 job 接管）。
9. 测：`RagRetrievalQueryMapperIT`（denseRecallL1 在 L0 sim<0.5 改写下命中）；端到端 IT 重放"我的系统"断言 SUPPORTED 且 L1 通道有贡献。

### 验证
L1 向量含"安装部署"→ 高 L1 余弦 → RRF 抬该 doc 子节点 → 精排过 soft → SUPPORTED。trace 候选含 L1 扩展 doc 节点。

---

## 续做指引

- 下次说"**继续 Phase3**"或"继续 RAG 召回升级"→ 从本文件 Phase3 起（L1 向量通道，迁移 V36）。Phase2 已 ✅ 落地验证。
- 每阶段独立可验：playwright `/knowledge` 检索调试面板 + PG 查 `rag_retrieval_logs` 最近 trace（crag_verdict/candidates_l0/l2_lexical_fallback）。
- backend 当前 = 自打 fat jar **PID 5500**（`java -jar targets/agent-platform-0.1.0-SNAPSHOT.jar`）。续做前 `mvn package -DskipTests` 重打 jar（须先 kill 运行中 PID 释文件锁）、kill 旧 PID、`java -jar` 重启（或用户 IDE 重启）。
- 跑法约束：H2 跑不了 halfvec/tsvector，Phase2/3 的 mapper 测走 IT profile 真 PG（`agent_platform_it` + Redis，`mvn test -Dsurefire.excludedGroups= -Dtest=...IT`）。
- halfvec `<=>` 等裸 `<` 在 MyBatis `<script>` 内必 `&lt;` 转义，否则 SAXParseException 炸全 context（mock 单测验不出，复发多次）。
- IT 中文坑：Maven 默认 GBK 源码编码 → SQL 字面量中文 mojibake。**所有中文走 `?` 参数绑定**，fixture 标识用 ASCII；KeyHolder 在 PG 返全行 → 改 name 查询取 id。
