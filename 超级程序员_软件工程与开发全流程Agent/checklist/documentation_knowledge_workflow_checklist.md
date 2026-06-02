# Documentation and Knowledge Management Workflow Checklist

Use this checklist after completing every step of `workflow/documentation_knowledge_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 四层技术文档体系建设

- [ ] 四层文档体系已定义（L1战略/L2设计/L3实现/L4运维）
- [ ] ADR目录≥5个
- [ ] arc42 12个标准章节模板已配置
- [ ] 文档覆盖率≥90%

## Step 2: Docs as Code实践

- [ ] 文档仓库与代码仓库关联（同仓库或子模块）
- [ ] CI/CD文档流水线运行（pre-commit/PR/合并后全链路）
- [ ] pre-commit文档检查生效（markdownlint+prettier+cspell）
- [ ] 静态站生成器已部署（Docusaurus/MkDocs/Antora/VitePress）

## Step 3: API文档与开发者门户

- [ ] API文档P0要素齐全（认证/请求/响应/参数/示例/快速开始）
- [ ] OpenAPI自动化工具链运行（Spec→文档→SDK→Mock→测试）
- [ ] SDK自动生成≥3语言
- [ ] 开发者门户上线（API目录/文档/调试台/SDK下载）

## Step 4: SECI知识沉淀与团队知识管理

- [ ] SECI模型四阶段运行（社会化→外显化→组合化→内化）
- [ ] 巴士因子红色警报（<3）已识别
- [ ] 知识贡献纳入绩效考核（≥10%权重）
- [ ] RAG知识库已部署，新人问题50%由AI回答

## Step 5: Runbook标准化与运维SOP

- [ ] Runbook覆盖Top 20运维场景
- [ ] 标准化Runbook体系已实施（模板层/存储层/执行层/度量层）
- [ ] 故障排查Runbook含诊断决策树（if-else+命令+预期输出+异常处理）
- [ ] MTTR目标从45min降至12min，操作失误率从5%降至0.3%

## Overall

- [ ] All 5 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.
