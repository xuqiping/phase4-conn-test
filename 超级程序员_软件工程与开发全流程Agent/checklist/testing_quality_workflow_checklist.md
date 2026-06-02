# Testing and Quality Assurance Workflow Checklist

Use this checklist after completing every step of `workflow/testing_quality_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 测试策略模型选型

- [ ] 《测试策略决策记录》已输出（ADR格式）
- [ ] 测试金字塔形状与各层比例已明确
- [ ] 风险驱动测试（RBT）覆盖核心业务流程
- [ ] 敏捷测试四象限（Lisa Crispin）已映射到实际测试类型

## Step 2: 四级测试体系建立

- [ ] 单元测试覆盖率≥80%
- [ ] 集成测试通过率100%（Testcontainers已部署）
- [ ] CI/CD分层触发配置完成：PR<5min/合并<15min/Nightly<60min
- [ ] 验收测试依据PRD的BDD Gherkin验收条件逐条验证

## Step 3: 自动化测试工程化

- [ ] Playwright工程化配置完成（多浏览器+自动等待+Trace Viewer）
- [ ] Page Object Model覆盖全部E2E页面
- [ ] Flaky Rate<2%
- [ ] Flaky Test Registry已建立

## Step 4: 性能压测全链路

- [ ] 《性能测试计划》已输出（六大类型：基准/负载/压力/稳定/峰值/容量）
- [ ] k6/Grafana已部署
- [ ] 全链路压测通过（影子库+流量标记+MQ隔离）
- [ ] 性能基线已建立（P50/P95/P99/QPS/资源利用率）

## Step 5: 质量度量与DORA指标

- [ ] DORA四大指标可自动采集（部署频率/变更前置时间/变更失败率/MTTR）
- [ ] 度量Dashboard上线
- [ ] 团队理解"度量用于改进而非考核"
- [ ] 月度度量回顾会议已建立

## Overall

- [ ] All 5 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.
