# 企业级精准知识库 RAG · P5 评测、迁移与运维验收计划

> 父计划：[企业级精准知识库RAG.plan.md](企业级精准知识库RAG.plan.md)。只含伪代码。

## 技术坑点预判

| 坑点 | 规避 | 验证 |
|---|---|---|
| 黄金集只存标准答案，无法测召回 | 保存相关/禁止 Chunk、版本、主题、Claim-Citation | 可分别算 retrieval 与 answer 指标 |
| 影子检索重复调 LLM 成本失控 | 可配置采样，只对选定 KB/问题类型启用 | 成本预算超限自动停影子 |
| 灰度按随机请求导致用户结果漂移 | 按 KB/用户稳定分桶 | 同用户持续落在同一组 |
| 迁移后急删旧数据无法回滚 | 停写观察期后再独立审批删除 | alias/route 可立即切回 |

## 实现步骤

- [x] **Step 1：黄金问题集、样本导入和指标引擎**
  - **对应需求**：RAG-FR-07
  - **目标**：覆盖精确、版本、多证据、比较、表格/视觉、无答案、无权限和 Hard Negative。
  - **动作**：新增 dataset/case/run/result 表；支持 JSONL 导入导出；计算 Recall@K、MRR、nDCG、Version/Coverage/Citation/Faithfulness/Abstention 等。
  - **文件（≤20）**：
    - `backend/src/main/resources/db/migration/V104__rag_evaluation_center.sql`
    - `backend/src/main/java/com/superprogrammer/knowledge/evaluation/EvaluationService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/evaluation/RagMetricsCalculator.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeEvaluationController.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/evaluation/RagMetricsCalculatorTest.java`
  - **依赖/并行**：依赖 P4 完整输出协议。
  - **安全检查**：数据集按 tenant/KB 授权；导出脱敏；禁止把无权原文带出。
  - **验证**：手算小样本与指标一致；空集、禁止候选、多证据和无答案边界。

- [x] **Step 2：Champion/Challenger、影子检索与发布门禁**
  - **对应需求**：RAG-FR-07、RAG-FR-09
  - **目标**：任何 Pipeline 变化必须评测；未达门槛不能切生产。
  - **动作**：Pipeline 绑定评测 run；旧链路 Champion、新链路 Challenger；影子只记结果；门槛采用规格数值并允许更严格覆盖；失败锁定切换按钮。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/evaluation/ReleaseGateService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/retrieval/ShadowRetrievalService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/RagModeResolver.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/evaluation/ReleaseGateServiceTest.java`
  - **依赖/并行**：依赖 Step 1。
  - **安全检查**：影子复用相同 ACL；诊断结果保留期限；不得影响用户答案。
  - **验证**：门槛达标/未达标、影子超时、成本上限、旧链路结果不受影响。

- [x] **Step 3：评测与影子对比管理界面**
  - **对应需求**：RAG-FR-07、RAG-FR-08
  - **目标**：管理员能维护样本、启动评测、比较 Pipeline 和定位退化案例。
  - **动作**：新增评测页；展示总体指标、按 queryType 切片、退化样本、Trace 跳转和门禁状态；操作有确认/取消/进度。
  - **文件（≤20）**：
    - `frontend/src/api/knowledge.ts`
    - `frontend/src/views/KnowledgeEvaluationView.vue`
    - `frontend/src/components/knowledge/EvaluationRunPanel.vue`
    - `frontend/src/components/knowledge/ShadowComparisonPanel.vue`
    - `frontend/src/router/index.ts`
  - **依赖/并行**：依赖 Step 2。
  - **安全检查**：路由、菜单和接口三层权限；敏感样本正文按权限隐藏。
  - **验证**：导入、启动、取消、失败、对比、Trace 跳转、移动端和键盘可达性。

- [x] **Step 4：按 KB/用户稳定灰度、切换与回滚**
  - **对应需求**：RAG-FR-01、RAG-FR-09
  - **目标**：按 5%→20%→50%→100% 切新链路，出现异常立即回退。
  - **动作**：稳定 hash 分桶；路由配置版本化；切换前校验门禁、索引健康和对账；回滚同时切 read alias/route 并失效新快照缓存；旧 PG 写入在观察期继续。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/service/RagModeResolver.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/migration/RagRolloutService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeAdminController.java`
    - `frontend/src/components/knowledge/IndexOperationsPanel.vue`
    - `backend/src/test/java/com/superprogrammer/knowledge/migration/RagRolloutServiceTest.java`
  - **依赖/并行**：依赖 Step 2、P2 alias 和 P0 cache version。
  - **安全检查**：仅管理员；二次确认；配置版本和操作者审计；禁止直接输入物理索引名。
  - **验证**：稳定分桶、各比例、门禁拦截、OpenSearch 故障、缓存失效和一键回滚。

- [x] **Step 5：在线反馈、运维指标、告警和最终文档**
  - **对应需求**：RAG-FR-07、RAG-FR-08、RAG-FR-09
  - **目标**：建立人工确认的反馈回灌和上线后可运维能力。
  - **动作**：反馈分类进入待审核队列，禁止直接改排序；埋召回/重排/覆盖/降级/删除 SLA/成本指标；补充 feature-map、用户操作、测试方案、快速启动和旧文档差异导航，但不修改旧 14/15/16 正文。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/evaluation/FeedbackReviewService.java`
    - `backend/src/main/java/com/superprogrammer/common/metrics/BizMetrics.java`
    - `frontend/src/components/knowledge/RagAskPanel.vue`
    - `workflow_output/docs/feature-map/企业级精准知识库RAG.feature-map.md`
    - `workflow_output/docs/user-ops/企业级精准知识库RAG用户操作手册.md`
    - `workflow_output/docs/测试方案/企业级精准知识库RAG测试方案.md`
    - `workflow_output/开发进度/企业级精准知识库RAG/README.md`
    - `workflow_output/docs/file_structure.md`
  - **依赖/并行**：依赖 Step 3～4。
  - **安全检查**：反馈正文脱敏；监控标签禁止 userId/traceId/KB 名等高基数值；日志不含完整 Prompt/Chunk/密钥。
  - **验证**：反馈需人工确认后才能进黄金集；Prometheus 指标基数检查；告警阈值和处置入口演练。

## Phase 4 验收清单

- [ ] 后端全测、OpenSearch 集成测试、前端 build 通过。
- [ ] E2E：上传→版本生效→解析→双写→检索→LLM 重排→5～10 条覆盖→引用→撤销/删除。
- [ ] 安全：跨用户、权限竞态、缓存污染、Prompt Injection、日志泄密，权限泄漏率 0。
- [ ] 指标：Recall@20≥92%、nDCG@10≥80%、精确编号≥98%、版本≥98%、引用≥95%、Coverage≥90%、无依据≤2%。
- [ ] 性能：无 LLM 检索 P95≤1 秒；LLM 单轮重排目标 P95≤4 秒；故障降级有明确提示和 Trace。
- [ ] 运维：影子、灰度、回滚、对账、删除 SLA、告警和诊断追溯实测。

## 术语表

| 术语 | 大白话 | 案例 |
|---|---|---|
| 黄金集 | 有标准证据的固定考试题 | 每次换模型都跑同一批题 |
| Hard Negative | 很像答案但其实错误的候选 | 同关键词的过期制度 |
| 稳定分桶 | 同一个用户不会一会新一会旧 | hash(userId) 固定落在 20% 组 |

