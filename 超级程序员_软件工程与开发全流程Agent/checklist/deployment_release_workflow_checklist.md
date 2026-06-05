# Deployment and Release Management Workflow Checklist

Use this checklist after completing every step of `workflow/deployment_release_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 环境分层与基础设施即代码

- [ ] 四层环境（Dev/Test/Staging/Prod）全部IaC化
- [ ] 临时环境（Ephemeral）每个PR自动创建
- [ ] 非生产环境自动启停节省50-70%成本
- [ ] Staging"四同"标准已实施（同配置/同版本/同数据/同流程）

## Step 2: 部署策略选型与实施

- [ ] 部署策略已选定（金丝雀/蓝绿/滚动/暗发布）
- [ ] 决策树与系统风险等级匹配
- [ ] Argo Rollouts已配置（Canary/BlueGreen AnalysisTemplate）
- [ ] Prometheus监控指标作为发布门禁（错误率/P99/CPU/内存）

## Step 3: Feature Toggle全生命周期管理

- [ ] Toggle管理后台上线
- [ ] 四类Toggle（Release/Ops/Experiment/Permission）分类管理
- [ ] 过期告警机制运行
- [ ] 每Sprint预留20%时间清理过期Toggle
- [ ] 灰度五步流程已配置（0%→1-5%→20-30%→50-70%→100%）

## Step 4: 数据库迁移即代码

- [ ] Flyway/Liquibase已配置，迁移脚本与代码同仓库同PR
- [ ] 大表DDL（>100万行）用gh-ost/pt-osc在线变更
- [ ] 三层回滚体系覆盖应用-配置-数据
- [ ] CI验证：SQL语法+回滚可行性+危险SQL审核

## Step 5: SemVer版本管理与发布工程

- [ ] SemVer规范已定义（MAJOR.MINOR.PATCH）
- [ ] Changelog自动生成（基于Conventional Commits）
- [ ] 发布产物（镜像/二进制/文档）已版本化
- [ ] 发布审批流程建立（Dev自动/Test自动/Staging半自动/Prod手动）

## Overall

- [ ] All 5 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.
