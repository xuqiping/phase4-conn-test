# Service Governance Workflow Checklist

在完成 `workflow/service_governance_workflow.md` 的每一步后，使用此检查清单进行交叉验证。每个项目必须回答**是**才算完成。如果有任何项目回答**否**，修复输出并重新验证。

## Step 1: 识别服务治理/观测需求场景

- [ ] 已明确场景类型（观测体系搭建/监控告警优化/链路追踪接入/APM性能分析/日志中台建设/全栈观测）
- [ ] 已提取系统规模（服务数量/Pod数/日日志量）
- [ ] 已识别当前技术栈（已有Prometheus/云厂商监控/无观测基础）
- [ ] 已提取关键痛点（故障发现慢/定位难/告警泛滥/性能瓶颈不明/日志散乱）
- [ ] 已提取预算约束（开源优先/可接受商业方案）
- [ ] 已对照知识库方法论体系完成初步判断（无基础→OpenTelemetry起步/告警混乱→SLO/Error Budget/性能瓶颈→eBPF+Profiling/日志量大→冷热分层）
- [ ] 如有信息缺失，已向用户追问不超过2个澄清问题

## Step 2: 输出服务治理/观测方案

- [ ] 如为观测体系搭建，Metrics支柱已覆盖（Prometheus/VictoriaMetrics + RED/USE方法 + Grafana可视化）
- [ ] 如为观测体系搭建，Traces支柱已覆盖（OpenTelemetry SDK + W3C Trace Context + Jaeger/Tempo）
- [ ] 如为观测体系搭建，Logs支柱已覆盖（Fluent Bit/Vector + JSON结构化 + TraceID注入 + ELK/Loki/ClickHouse）
- [ ] 如为观测体系搭建，Profiling支柱已覆盖（eBPF Beyla/Hubble + Continuous Profiling Pyroscope/Parca）
- [ ] 如为观测体系搭建，四层解耦架构已说明（采集→传输→存储→展示）
- [ ] 如为观测体系搭建，Cardinality安全线已强调（Label组合数<10万）
- [ ] 如为监控告警优化，SLO/Error Budget驱动告警设计已输出（SLO定义、Error Budget计算、多级告警P0/P1/P2）
- [ ] 如为监控告警优化，告警降噪策略已覆盖（Error Budget抑制、告警合并、on-call轮值）
- [ ] 如为监控告警优化，Prometheus告警规则模板已给出（CPU/内存/磁盘/网络/应用黄金指标/业务指标）
- [ ] 如为链路追踪接入，OpenTelemetry接入方案已覆盖（自动埋点Java Agent/Go otelhttp/Node.js auto-instrumentations）
- [ ] 如为链路追踪接入，采样策略已说明（头部采样/尾部采样/自适应采样）
- [ ] 如为链路追踪接入，TraceID全链路传播规范已给出（网关→服务→MQ→数据库→下游服务）
- [ ] 如为APM性能观测，eBPF零侵入采集方案已覆盖（Beyla/Hubble/Pixie选型、DaemonSet部署、内核版本≥4.16）
- [ ] 如为APM性能观测，Continuous Profiling方案已说明（Pyroscope/Parca/pprof选型、采集策略、火焰图分析）
- [ ] 所有核心论断均能在知识库中找到支撑来源

## Step 3: 验证与交付

- [ ] 已读取对应 checklist 并逐项核对
- [ ] 输出内容无知识性错误
- [ ] 已向用户交付最终答案

## Overall

- [ ] 工作流中的所有步骤已按顺序执行，没有跳过
- [ ] 每一步都已与其检查清单部分进行交叉验证
- [ ] 没有在任何检查清单部分通过前提前进入下一步
- [ ] `task/current_task.md` 已更新完成记录
- [ ] 所有 `[参考: ...]` 标注均指向存在的知识库文件
