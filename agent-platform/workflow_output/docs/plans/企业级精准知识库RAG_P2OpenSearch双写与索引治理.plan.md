# 企业级精准知识库 RAG · P2 OpenSearch 双写与索引治理计划

> 父计划：[企业级精准知识库RAG.plan.md](企业级精准知识库RAG.plan.md)。只含伪代码。

## 技术坑点预判

| 坑点 | 规避 | 验证 |
|---|---|---|
| Dense 维度与 mapping 不一致 | 每个物理索引绑定 embedding model/version/dimension | 错维度在建索引前失败 |
| ACL 后过滤造成泄漏 | OpenSearch bool filter 先过滤，PG 再复核 | 跨用户候选为 0 |
| Bulk 部分失败被当全成功 | 逐项解析响应，失败项重入队 | 混合成功/失败测试 |
| alias 切换与写入竞态 | read/write alias 分离，原子 alias action | 切换期间无空窗 |

## 实现步骤

- [ ] **Step 1：引入 OpenSearch 客户端、配置与健康检查**
  - **对应需求**：RAG-FR-02、RAG-FR-09
  - **目标**：提供可关闭、可测试、fail-closed 的连接基座。
  - **动作**：加入官方 Java Client；环境变量配置 URL/认证/TLS/超时；健康检查区分 disabled/down/up；测试用 Testcontainers 或独立 profile。
  - **文件（≤20）**：
    - `backend/pom.xml`
    - `backend/src/main/java/com/superprogrammer/knowledge/config/OpenSearchProperties.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/config/OpenSearchConfig.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/config/OpenSearchHealthIndicator.java`
    - `backend/src/main/resources/application.yml`
    - `backend/src/test/java/com/superprogrammer/knowledge/opensearch/OpenSearchContainerSupport.java`
  - **依赖/并行**：依赖 P0；可与 P1 Step 1～4 并行，文件无交集。
  - **安全检查**：密码仅环境变量；TLS 校验默认开启；禁止记录 Authorization。
  - **验证**：禁用、认证错误、TLS 错误、超时和成功健康状态。

- [ ] **Step 2：版本化索引模板与 read/write alias 管理**
  - **对应需求**：RAG-FR-02、RAG-FR-09
  - **目标**：创建包含 C2 Dense/Sparse/Metadata/ACL 的物理索引并原子切换别名。
  - **动作**：mapping 保存规格要求字段；Analyzer/RRF 权重属于 Pipeline；索引名不含硬编码模型昵称；实现 create/validate/switch/rollback。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/opensearch/KnowledgeIndexSchema.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/opensearch/KnowledgeIndexManager.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/opensearch/IndexAliasService.java`
    - `backend/src/main/resources/opensearch/kb-chunk-index-template.json`
    - `backend/src/test/java/com/superprogrammer/knowledge/opensearch/KnowledgeIndexManagerIT.java`
  - **依赖/并行**：依赖 Step 1 和 P0 Pipeline 版本。
  - **安全检查**：索引 mapping 强制 tenant/kb/acl/status/version 字段；管理 API 不直接暴露凭证。
  - **验证**：mapping、维度、别名原子切换、回滚、不同 embedding 空间不混用。

- [ ] **Step 3：C2 Dense/Sparse 双写与批量消费**
  - **对应需求**：RAG-FR-02、RAG-FR-03
  - **目标**：索引任务一次生成 embedding，并把权威元数据写入 OpenSearch 检索副本。
  - **动作**：扩展 IndexJob 为 PG legacy + OpenSearch sink 状态；批量 embed 和 bulk upsert；逐项确认；旧链路继续写入直到灰度结束。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/service/IndexJobWorker.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/internal/IndexJobTxService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/opensearch/OpenSearchChunkWriter.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/opensearch/OpenSearchChunkDocument.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/service/IndexJobWorkerTest.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/opensearch/OpenSearchChunkWriterIT.java`
  - **依赖/并行**：依赖 Step 2 和 P1 Step 5；串行汇合。
  - **安全检查**：只写 ACL token，不写密钥/无关个人信息；失败日志只含 ID/Hash。
  - **验证**：bulk 部分失败、重试幂等、ACL/Hash/版本字段一致、旧 PG 写入不回归。

- [ ] **Step 4：PG↔OpenSearch 对账、重建与删除传播**
  - **对应需求**：RAG-FR-09
  - **目标**：索引可完全重建，删除/撤销/权限变化在 SLA 内传播。
  - **动作**：对账文档数、Chunk 数、Hash、ACL、alias；差异生成修复任务；删除传播到索引、缓存、解析产物；提供 dry-run 和分页重建。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/service/ReconciliationWorker.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/internal/ReconciliationTxService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/opensearch/OpenSearchReconciliationService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/event/VisibilityInvalidationListener.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/service/ReconciliationWorkerTest.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/service/internal/ReconciliationIT.java`
  - **依赖/并行**：依赖 Step 3。
  - **安全检查**：重建/删除/修复仅管理员；所有动作审计；批量目标需 tenant/KB 限定。
  - **验证**：孤儿、缺失、Hash/ACL 漂移、删除失败重试、缓存失效和对象删除测试。

- [ ] **Step 5：索引运维 API 与蓝绿控制面**
  - **对应需求**：RAG-FR-09、RAG-FR-08
  - **目标**：管理员可看快照、对账、重建、切换和回滚，不需直接操作 OpenSearch。
  - **动作**：后端提供只读状态、启动重建、切换/回滚接口；前端显示进度和风险确认；所有写操作审计。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeAdminController.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/dto/KnowledgeIndexStatusVO.java`
    - `frontend/src/api/knowledge.ts`
    - `frontend/src/components/knowledge/IndexOperationsPanel.vue`
    - `frontend/src/views/KnowledgeView.vue`
  - **依赖/并行**：依赖 Step 4。
  - **安全检查**：切换需二次确认和权限；输入只接受已登记 snapshot id。
  - **验证**：进度刷新、失败重试、取消、切换、回滚和越权测试。

## 运维与验证

- **做**：bulk 成功率/延迟、积压、对账漂移、删除 SLA、alias 版本指标和告警。
- **做**：Dense/Sparse 单路故障降级开关；连接超时/熔断；分页重建避免大查询。
- `cd backend; mvn -Dtest=KnowledgeIndexManagerIT,OpenSearchChunkWriterIT,ReconciliationIT test`

