# 企业级精准知识库 RAG · P4 多证据、引用与回答计划

> 父计划：[企业级精准知识库RAG.plan.md](企业级精准知识库RAG.plan.md)。只含伪代码。

## 技术坑点预判

| 坑点 | 规避 | 验证 |
|---|---|---|
| Top-K 固定导致 5～10 条问题漏项 | 按 queryType/coverage 动态预算并补检索 | 多证据黄金题 Coverage≥90% |
| 邻居扩展越权/跨版本 | 每次扩展复用权限、版本、Hash 校验 | 邻居不可见时不进入上下文 |
| 分批归纳丢引用 | 中间事实携带 citation ids，合并不可脱钩 | 每个最终 claim 可反查原文 |
| 单文档挤满上下文 | 多样性上限，精确唯一命中才解除 | 比较题双方都有证据 |

## 实现步骤

- [x] **Step 1：Coverage Key 与动态证据预算**
  - **对应需求**：RAG-FR-05
  - **目标**：按单点、条款、流程、比较、列表动态选择 2～20 条证据。
  - **动作**：从 QueryPlan/Ranking 输出 coverageKey；以相关性、覆盖、非重复、版本正确性选择；单文档默认不超过一半。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/context/CoverageSelector.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/context/EvidenceBudget.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/dto/EvidenceResult.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/context/CoverageSelectorTest.java`
  - **依赖/并行**：依赖 P3 Ranking 输出。
  - **安全检查**：选择器只能处理已授权候选。
  - **验证**：单点、5～10 条流程、20 条列表、比较双方、重复和唯一文档边界。

- [x] **Step 2：缺项检测与最多两轮补检索**
  - **对应需求**：RAG-FR-05、RAG-FR-08
  - **目标**：发现覆盖缺口后生成子查询补齐，普通最多 1 轮、高精度最多 2 轮。
  - **动作**：Coverage Verifier 返回 missing keys；调用同一 Retrieval Router 并带 parent run/round；合并去重；失败返回部分覆盖并标记不完整。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/context/CoverageVerifier.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/retrieval/RetrievalRouter.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/RagRetrievalService.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/context/CoverageVerifierTest.java`
  - **依赖/并行**：依赖 Step 1。
  - **安全检查**：子查询不扩大 KB/ACL/版本范围；轮数、成本和 token 有硬上限。
  - **验证**：缺 0/1/多主题、补回重复、补检索失败、轮数边界和 Trace 关联。

- [x] **Step 3：邻居扩展、去重和 Token 预算组装**
  - **对应需求**：RAG-FR-05、RAG-FR-06
  - **目标**：按流程/条款/表格/代码类型补充必要上下文，不跨文档、权限或版本边界。
  - **动作**：Context Builder 先冲突检测，再 PG 复核、邻居扩展、去重、多样性和 token 裁剪；比较题保证各方配额。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/context/ContextBuilder.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/context/NeighborExpander.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/util/TokenEstimator.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/context/ContextBuilderTest.java`
  - **依赖/并行**：依赖 Step 2。
  - **安全检查**：PG 最终复核权限、版本、Hash、撤销状态；竞态失败即剔除。
  - **验证**：条款条件/例外、流程邻步、表头、跨权限、跨版本、token 超限和比较配额。

- [x] **Step 4：精确 Citation 与 Claim 校验**
  - **对应需求**：RAG-FR-06
  - **目标**：每个事实 Claim 绑定页码/条款/Sheet/Cell/bbox 等可定位 Citation。
  - **动作**：扩展 Citation DTO；答案先输出结构化 claim+citation；Verifier 检查引用存在、Hash/权限/版本一致和文本支持，不支持则删除或降为不确定。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/service/internal/CitationChecker.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/citation/CitationVerifier.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/dto/RagRetrieveVO.java`
    - `frontend/src/api/knowledge.ts`
    - `frontend/src/components/knowledge/RagAskPanel.vue`
    - `frontend/src/components/knowledge/RetrievalDebugPanel.vue`
    - `backend/src/test/java/com/superprogrammer/knowledge/service/internal/CitationCheckerTest.java`
  - **依赖/并行**：依赖 Step 3。
  - **安全检查**：引用下载/预览端点继续执行 KB 读权限；bbox/文件路径不暴露存储内部路径。
  - **验证**：页码、条款、Sheet/Cell、bbox 定位；伪造 citation、Hash 变化、撤权和旧版本引用。

- [ ] **Step 5：分批归纳、置信状态机与校准拒答**
  - **对应需求**：RAG-FR-05、RAG-FR-08
  - **目标**：长证据分批生成事实再合并；拒答不再用全局固定 0.30/0.45。
  - **动作**：答案生成走显式模型；批次事实携带引用；检测版本冲突；置信状态输出 6 种状态；阈值从评测配置读取，未标定时使用保守拒答而非旧常量。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/answer/GroundedAnswerService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/answer/ConfidenceEvaluator.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/RagRetrievalService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeAskController.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/answer/GroundedAnswerServiceTest.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/answer/ConfidenceEvaluatorTest.java`
  - **依赖/并行**：依赖 Step 4。
  - **安全检查**：模型只能看到已校验证据；文档中的命令文本仅作证据；生成失败可返回证据列表。
  - **验证**：支持/部分支持/冲突/证据不足/越界/检索失败；分批引用不丢；模型缺失明确报错。

## 功能联动与验证

- 用户切换 KB/模式/高精度 → QueryPlan、缓存、证据预算和时间线同步刷新；取消请求停止前端等待但不污染服务端 run。
- 引用点击 → 定位页码/Sheet/区域；无权限/已撤销 → 明确不可访问，不展示旧缓存正文。
- `cd backend; mvn -Dtest=CoverageSelectorTest,CoverageVerifierTest,ContextBuilderTest,CitationCheckerTest,GroundedAnswerServiceTest,ConfidenceEvaluatorTest test`
- `cd frontend; npm run test -- --run RagAskPanel RetrievalDebugPanel`

