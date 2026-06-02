# Coding and Version Control Workflow Checklist

Use this checklist after completing every step of `workflow/coding_version_control_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: Git工作流选型与演进

- [ ] Git工作流选型决策已记录（ADR格式），选型与团队规模/发布频率匹配
- [ ] main分支已配置保护规则（PR+审查+CI通过）
- [ ] Feature Toggle基础设施已部署（OpenFeature SDK）
- [ ] 《Feature Toggle管理规范》已输出（命名规则/生命周期/清理策略）

## Step 2: AI辅助Code Review流程

- [ ] AI审查响应时间<2分钟
- [ ] PR规模≤400行（超800已拆分）
- [ ] Review SLA已配置：P0≤30min/P1≤4h/P2≤24h
- [ ] 审查清单分层：L1通用+L2模块+L3专项

## Step 3: Clean as You Code质量门禁

- [ ] 四级门禁全部配置：L1 IDE实时/L2 Pre-commit/L3 CI/L4定期全量
- [ ] SonarQube四大维度（Reliability/Security/Security Review/Maintainability）质量门通过
- [ ] 新代码覆盖率≥80%，无Critical/Bug
- [ ] 代码异味密度≤10/KLOC

## Step 4: 规范即代码与AI Rules

- [ ] AI Rules文件已配置（.cursorrules/CLAUDE.md/.customInstructions）
- [ ] 规范合规率≥88%
- [ ] Spec-driven Development流程已建立
- [ ] 编码规范/架构约束/安全约束/性能约束已文档化

## Step 5: 四层技术文档金字塔

- [ ] ≥1个ADR已创建
- [ ] ≥1个Design Doc已输出
- [ ] 文档遵循"读者无需作者在场也能理解"原则
- [ ] DORA四大指标可自动采集
- [ ] SPACE框架度量已配置

## Overall

- [ ] All 5 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.
