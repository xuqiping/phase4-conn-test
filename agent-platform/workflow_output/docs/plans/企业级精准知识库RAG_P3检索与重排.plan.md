# 企业级精准知识库 RAG · P3 QueryPlan、检索与重排计划

> 父计划：[企业级精准知识库RAG.plan.md](企业级精准知识库RAG.plan.md)。只含伪代码。

## 技术坑点预判

| 坑点 | 规避 | 验证 |
|---|---|---|
| 所有 query 都调用 LLM | 规则优先，只有语义不确定时才走显式模型 | 编号/日期问题零 Query LLM 调用 |
| 多通道分数不可直接比较 | 统一候选协议 + 加权 RRF，不混算原始分 | 通道顺序变化结果稳定 |
| LLM 输出伪造 candidate id | JSON Schema + 输入 ID 白名单 | 注入未知 ID 被拒绝 |
| LLM 批次排序不可比 | 批内筛选后再做胜出候选合并排序 | 30 候选分批结果稳定 |

## 实现步骤

- [x] **Step 1：QueryPlan 规则分类与可选 LLM 分析**
  - **对应需求**：RAG-FR-03、RAG-FR-05
  - **目标**：生成 queryType、answerShape、filter、strategies、exhaustive/multiHop 等结构化计划。
  - **动作**：先提取编号/日期/版本/引号短语；不确定时通过 KB 显式选择的 LLM；HyDE 仅语义低召回补强且永不作证据。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/query/QueryPlan.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/query/QueryPlanner.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/QueryExpansionService.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/service/QueryExpansionServiceTest.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/query/QueryPlannerTest.java`
  - **依赖/并行**：依赖 P0 配置/Trace 和 P2 索引。
  - **安全检查**：query LLM 仅接用户问题和允许元数据；Prompt Injection 文本不提升为系统指令。
  - **验证**：12 类 query、规则优先、历史版本过滤、HyDE 禁用边界、模型缺失报错。

- [ ] **Step 2：OpenSearch ACL/版本 Pre-filter 构造器**
  - **对应需求**：RAG-FR-03
  - **目标**：所有通道在搜索阶段过滤 tenant/KB/ACL/status/effective/version/metadata。
  - **动作**：把 QueryPlan filter 和用户权限版本编译为 bool filter；拒绝无 tenant/KB 范围请求；输出过滤摘要供 Trace。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/retrieval/RetrievalFilterBuilder.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/RagScopeResolver.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/VisibilitySetService.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/service/RagScopeResolverTest.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/retrieval/RetrievalFilterBuilderTest.java`
  - **依赖/并行**：依赖 Step 1。
  - **安全检查**：禁止 Java 后过滤替代 Pre-filter；PG 复核仍保留。
  - **验证**：跨用户/租户、权限竞态、过期/撤销/历史版本、部门/项目/密级过滤。

- [ ] **Step 3：Exact/Sparse/Dense/Entity 多通道召回与 RRF**
  - **对应需求**：RAG-FR-03
  - **目标**：各通道独立召回后统一去重、近邻折叠和动态 RRF 融合。
  - **动作**：定义 `RetrievalCandidate`；实现 Exact、BM25、C2/Section/Document Dense、Entity provider；Pipeline 配置候选数和权重；扩展现有 `RrfFusion`。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/retrieval/RetrievalCandidate.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/retrieval/Retriever.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/retrieval/OpenSearchRetrievers.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/internal/RrfFusion.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/RagRetrievalService.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/service/internal/RrfFusionTest.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/retrieval/OpenSearchRetrieversIT.java`
  - **依赖/并行**：依赖 Step 2。
  - **安全检查**：每个 Retriever 必须接受同一不可省略 FilterContext。
  - **验证**：通道故障降级、按 chunk 去重、同文档近邻折叠、RRF 稳定性和性能。

- [ ] **Step 4：统一 Ranking Engine 与 LLM 重排实现**
  - **对应需求**：RAG-FR-04
  - **目标**：替代 `rerankWithBoost()`，当前用可选 LLM 真正判断 Query-Chunk 相关性。
  - **动作**：RankingProvider 协议输出 candidate/score/provider/model/run；LLM 每批约 10 条、Schema 校验、温度最低、失败重试一次；按配置 fail closed/RRF/no answer。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/ranking/RankingEngine.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/ranking/RankingProvider.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/ranking/LlmRankingProvider.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/ranking/DisabledRankingProvider.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/ranking/RankingResult.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/RagRetrievalService.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/ranking/LlmRankingProviderTest.java`
  - **依赖/并行**：依赖 Step 3 和 P0 Step 2。
  - **安全检查**：候选 ID 白名单；文档内容作为 data，不得覆盖 system prompt；不向模型发送无权候选。
  - **验证**：有效/无效 JSON、未知 ID、重复 ID、超时、重试、三种 fallback、计费归户。

- [ ] **Step 5：预留专用 Rerank Provider 并完善 Trace/调试面板**
  - **对应需求**：RAG-FR-04、RAG-FR-08
  - **目标**：未来接入专用模型只新增 Provider；当前未配置时页面不可误选。
  - **动作**：实现 provider SPI 和 capability 校验，不接真实外部模型；记录 configured/effective mode、前后排序、批次、耗时、token/费用；前端显示 QueryPlan/RRF/Ranking 时间线。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/ranking/ModelRerankProvider.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/RagRetrievalLogService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/dto/RagRetrieveVO.java`
    - `frontend/src/api/knowledge.ts`
    - `frontend/src/components/knowledge/RetrievalDebugPanel.vue`
    - `frontend/src/components/knowledge/RetrievalAuditPanel.vue`
  - **依赖/并行**：依赖 Step 4。
  - **安全检查**：RERANK 无可用模型明确报错；不得自动使用 LLM，除非 fallback_policy 明确配置并记录。
  - **验证**：模式切换、配置不可用、Trace/usage/audit/Java 日志关联、DISABLED 明确标识。

## 运维与验证

- **做**：各通道耗时/候选数/错误、重排批次数/成本/降级、Query 类型分布。
- **做**：每 KB 限制 candidate/batch/token/cost/timeout；第三方超时、熔断和显式降级。
- `cd backend; mvn -Dtest=QueryPlannerTest,RrfFusionTest,LlmRankingProviderTest,RagRetrievalServiceTest test`

