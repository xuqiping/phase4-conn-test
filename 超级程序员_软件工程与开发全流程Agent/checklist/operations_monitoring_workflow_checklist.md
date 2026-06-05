# Operations Monitoring and Incident Response Workflow Checklist

Use this checklist after completing every step of `workflow/operations_monitoring_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: OpenTelemetry可观测性三支柱建设

- [ ] OpenTelemetry Collector已部署
- [ ] Metrics/Logs/Traces三支柱数据统一采集
- [ ] Grafana（Mimir+Loki+Tempo）关联查询可用
- [ ] 数据生命周期管理已实施（Metrics降采样/Logs分层/Traces采样）

## Step 2: SLO驱动告警与告警疲劳治理

- [ ] SLO/SLI/Error Budget已定义
- [ ] 告警分级P1-P4运行正常
- [ ] 30-60-90天治理路线图执行中
- [ ] on-call轮值机制建立（Primary+Secondary）
- [ ] 每周告警<10个、夜间唤醒<2次

## Step 3: START故障排查SOP

- [ ] START模型已培训（Signal-Triage-Analyze-Resolve-Track）
- [ ] 故障排查Runbook覆盖Top 10故障场景
- [ ] RCA模板已建立（5-Why/鱼骨图/FTA）
- [ ] Blameless文化已推行
- [ ] P0故障24小时内完成Post-Mortem初稿

## Step 4: FinOps容量规划与成本优化

- [ ] 容量规划四步法运行正常（基准→预测→缓冲→扩容）
 [ ] USL拐点已识别
- [ ] FinOps三层漏斗实施（消除浪费→提高利用率→架构优化）
- [ ] 云成本降低20-40%

## Step 5: 混沌工程韧性验证

- [ ] 混沌工程实验库已建立
- [ ] 故障五维度（计算/网络/存储/应用/依赖）覆盖
- [ ] 安全网机制生效（终止开关/自动停止/on-call确认）
- [ ] Chaos Mesh已部署
- [ ] 每月执行1次混沌工程实验

## Overall

- [ ] All 5 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.
