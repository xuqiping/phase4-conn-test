# 企业级精准知识库 RAG · P1 文档版本、解析与分块计划

> 父计划：[企业级精准知识库RAG.plan.md](企业级精准知识库RAG.plan.md)。只含伪代码。

## 技术坑点预判

| 坑点 | 规避 | 验证 |
|---|---|---|
| 直接改写现有 document 会丢版本 | Canonical Document 与不可变 version 分离，指针切换生效版本 | 更新后 v1/v2 均可追溯 |
| 字符硬切破坏条款、表头、步骤 | Parser 产结构树，Chunker 按文档类型策略分派 | 条款/表格/流程不从逻辑单元中间切断 |
| 重试重复生成 Chunk | `version+parser+chunker+contentHash` 幂等键 | 同任务重放无重复数据 |
| 大文件 OOM | 解析产物流式写对象存储，DB 只存定位和摘要 | 大 PDF/Excel 内存曲线受控 |

## 实现步骤

- [x] **Step 1：Canonical Document 与版本状态机**
  - **对应需求**：RAG-FR-01、RAG-FR-09
  - **目标**：支持主文档、不可变版本、生效/废止/撤销/替代和历史查询。
  - **动作**：兼容迁移现有 `knowledge_documents`；新增 canonical/version 字段或新表；服务层以事务完成创建版本、切换当前版本、冲突检测和撤销事件。
  - **文件（≤20）**：
    - `backend/src/main/resources/db/migration/V103__rag_document_version_governance.sql`
    - `backend/src/main/java/com/superprogrammer/knowledge/entity/CanonicalDocument.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/entity/KnowledgeDocumentVersion.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/KnowledgeDocumentService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeDocumentController.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/dto/KnowledgeDocumentVO.java`
    - `frontend/src/api/knowledge.ts`
    - `frontend/src/components/knowledge/DocumentManager.vue`
  - **依赖/并行**：依赖 P0；可与 P2 OpenSearch 客户端基座并行。
  - **安全检查**：版本操作继承 KB 管理权；撤销/生效写审计；冲突不泄漏无权文档信息。
  - **验证**：版本状态机、并发更新、历史查询、两个 EFFECTIVE 冲突、撤销传播事件测试。

- [x] **Step 2：权威等级、生效期、密级和元数据治理**
  - **对应需求**：RAG-FR-01、RAG-FR-03
  - **目标**：检索前能按时间、状态、权威和密级过滤，冲突时可解释。
  - **动作**：扩展文档表单与 API；校验时间区间；记录 owner/source/sourceUpdatedAt/authority/confidentiality/tags；提供当前有效版本查询。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/dto/KnowledgeDocumentUpdateRequest.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/KnowledgeDocumentService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/mapper/KnowledgeDocumentMapper.java`
    - `frontend/src/components/knowledge/DocumentOptionsModal.vue`
    - `frontend/src/components/knowledge/DocumentManager.vue`
  - **依赖/并行**：依赖 Step 1。
  - **安全检查**：密级只能由授权角色提升/降低；标签长度和数量限制。
  - **验证**：时区边界、生效/过期、权威冲突、批量更新和取消操作。

- [x] **Step 3：结构化解析产物协议与精确定位**
  - **对应需求**：RAG-FR-02、RAG-FR-06
  - **目标**：统一表达标题树、条款、Sheet/Cell、页码、阅读顺序、bbox 和区域证据。
  - **动作**：扩展 `ExtractedDocument/Section` 为结构化节点协议；解析产物大对象写文件存储；保留 parser version 和 source hash；Word/Markdown/PDF/Excel/图片分别映射定位字段。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/service/internal/ExtractedDocument.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/internal/Section.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/DocumentParserService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/internal/ExcelSheetExtractor.java`
    - `backend/src/main/java/com/superprogrammer/file/service/FileStorageService.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/service/ExcelDocumentParseIT.java`
  - **依赖/并行**：依赖 Step 1；与 Step 2 文件有交集，串行。
  - **安全检查**：解析器禁执行宏/脚本/外部链接；压缩炸弹、页数、行数、字符数限额。
  - **验证**：多类型 fixture 的页码、Sheet/Cell、bbox、跨页关系和恶意文件拒绝测试。

- [x] **Step 4：按文档类型生成 D0/S1/C2/E3 Chunk**
  - **对应需求**：RAG-FR-02、RAG-FR-06
  - **目标**：C2 成为 300～600 token 的事实主单元，保留完整逻辑边界和父子邻居。
  - **动作**：把 `KnowledgeNodeWriter.splitL2()` 替换为策略注册表；实现普通文档、条款、FAQ、表格、PDF/视觉策略；保存 titlePath、ordinal、locator、parent/neighbor 和 token 数。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/service/KnowledgeNodeWriter.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/chunk/ChunkStrategy.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/chunk/ChunkFactory.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/chunk/`：按类型新增策略类。
    - `backend/src/main/java/com/superprogrammer/knowledge/entity/KnowledgeNode.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/service/KnowledgeNodeServiceTest.java`
  - **依赖/并行**：依赖 Step 3。
  - **安全检查**：Chunk 继承 tenant/KB/version/ACL/密级，禁止生成无归属节点。
  - **验证**：token 上下界、overlap、列表/条款/表格原子性、父子邻居和定位回放测试。

- [x] **Step 5：Contextual Content 与幂等索引任务**
  - **对应需求**：RAG-FR-02、RAG-FR-09
  - **目标**：为每个 C2 生成稳定的上下文化文本，并可靠进入后续双写。
  - **动作**：拼装标题、版本、路径、背景和原文；计算 content/context hash；索引任务携带 version/parser/chunker/embedding/pipeline 版本；重试保持幂等。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/service/Contextualizer.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/IndexJobWorker.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/internal/IndexJobTxService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/entity/KnowledgeIndexJob.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/service/internal/IndexJobTxServiceTest.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/service/IndexJobWorkerTest.java`
  - **依赖/并行**：依赖 Step 4；P2 双写从本步汇合。
  - **安全检查**：上下文化文本不加入用户无权元数据；任务错误信息脱敏。
  - **验证**：同输入 Hash 稳定、版本变化触发新任务、重复消费无重复、失败可重试。

## 运维与验证

- **做**：解析/分块版本、任务耗时、失败类型、大文件限制、手工重试入口。
- **做**：撤销/删除事件；对象产物保留期限和删除 SLA。
- `cd backend; mvn -Dtest=KnowledgeDocumentServiceTest,KnowledgeNodeServiceTest,ExcelDocumentParseIT,IndexJobWorkerTest test`

