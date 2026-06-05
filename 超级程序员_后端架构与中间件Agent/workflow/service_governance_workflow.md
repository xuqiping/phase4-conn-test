# Service Governance & Observability Workflow

## Purpose

基于服务治理与观测知识体系，为用户提供可观测性三支柱（Metrics/Logs/Traces）体系建设、OpenTelemetry统一采集、Prometheus监控告警、链路追踪、APM性能观测等技术方案支持。覆盖RED/USE方法、SLO/Error Budget、eBPF无侵入采集等核心方法论。

## Prerequisites

- 用户已明确服务治理/观测场景或问题
- 知识库文件 `02_网络架构与中间件.md` 及子目录文件可访问

## Steps

### Step 1: 识别服务治理/观测需求场景

**Goal**: 明确用户的观测需求类型、系统规模和当前观测成熟度
**Completion criterion**: 已确定场景标签、规模等级、技术栈和预算约束

1. 读取用户消息，提取以下信息：
   - 场景类型：观测体系搭建 / 监控告警优化 / 链路追踪接入 / APM性能分析 / 日志中台建设 / 全栈观测
   - 系统规模：服务数量（<10 / 10-50 / 50+）、Pod/节点数、日日志量（GB/TB级）
   - 当前技术栈：已有Prometheus/Grafana / 云厂商监控 / 无观测基础
   - 关键痛点：故障发现慢 / 定位难 / 告警泛滥 / 性能瓶颈不明 / 日志散乱
   - 预算约束：开源优先 / 可接受商业方案（Datadog/Dynatrace等）

2. 对照知识库中的方法论体系初步判断：
   - 无观测基础 → 建议从OpenTelemetry统一采集起步，三支柱同步建设
   - 已有Prometheus但告警混乱 → 建议引入SLO/Error Budget方法论，优化告警策略
   - 性能瓶颈定位难 → 建议引入eBPF无侵入采集+Continuous Profiling
   - 日志量巨大且查询慢 → 建议冷热分层+结构化日志+TraceID注入

3. 如有信息缺失，向用户追问不超过2个澄清问题。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/02_网络架构与中间件.md > 服务治理与观测 > 三支柱协同]
- [参考: Agents知识库/0_超级编程行业知识库/02_网络架构与中间件.md > 服务治理与观测 > 方法论体系]

### Step 2: 输出服务治理/观测方案

**Goal**: 产出针对性的可观测性体系建设或优化方案
**Completion criterion**: 输出包含技术栈选型、架构设计、实施步骤、关键配置

根据Step 1确定的场景，按以下分支处理：

**分支A — 可观测性体系搭建**：
1. 输出三支柱协同架构设计：
   - **Metrics（指标）**：Prometheus/VictoriaMetrics采集 + Grafana可视化 + RED方法（Rate/Errors/Duration）或USE方法（Utilization/Saturation/Errors）定义黄金指标。
   - **Traces（链路）**：OpenTelemetry SDK自动埋点 + W3C Trace Context跨语言传播 + Jaeger/Tempo存储 + TraceID一键关联Metrics和Logs。
   - **Logs（日志）**：Fluent Bit/Vector采集 + 强制JSON结构化 + TraceID注入 + ELK/Loki/ClickHouse存储 + 冷热分层。
   - **Profiling（剖析）**：eBPF（Beyla/Hubble）零侵入采集 + Continuous Profiling（Pyroscope/Parca）火焰图分析。
2. 输出四层解耦架构：采集层（Agent/SDK/eBPF）→ 传输层（OTLP/gRPC/Kafka）→ 存储层（时序DB/日志DB/TraceDB）→ 展示层（Grafana统一大盘）。
3. 给出Cardinality安全线：Label组合数<10万为TSDB安全线。

**分支B — 监控告警优化**：
1. 输出SLO/Error Budget驱动告警设计：
   - 定义SLO（如P99延迟<200ms、错误率<0.1%、可用性>99.99%）。
   - 计算Error Budget（月度允许故障时间）。
   - 设计多级告警：P0（立即响应）/ P1（30分钟内）/ P2（工作时间内）。
2. 输出告警降噪策略：基于Error Budget的告警抑制（预算耗尽前不重复告警）、告警合并（同一根因合并发送）、on-call轮值机制。
3. 给出Prometheus告警规则模板：CPU/内存/磁盘/网络/应用黄金指标/业务指标。

**分支C — 链路追踪接入**：
1. 输出OpenTelemetry接入方案：
   - 自动埋点：Java（Java Agent）/ Go（otelhttp/otelgrpc）/ Node.js（@opentelemetry/auto-instrumentations-node）。
   - 手动埋点：关键业务逻辑自定义Span、Baggage传递业务上下文。
   - 采样策略：头部采样（固定比例）/ 尾部采样（基于延迟/错误）/ 自适应采样。
2. 给出TraceID全链路传播规范：从网关→服务→MQ→数据库→下游服务，确保TraceID不丢失。
3. 输出链路分析场景：慢请求定位（长尾延迟分析）、错误传播路径（根因服务识别）、依赖拓扑可视化。

**分支D — APM性能观测**：
1. 输出eBPF零侵入采集方案：
   - 工具选型：Beyla（应用层HTTP/gRPC）/ Hubble（Cilium网络层）/ Pixie（全栈自动采集）。
   - 部署模式：DaemonSet全节点部署、特权容器权限、内核版本要求（≥4.16）。
   - 数据输出：OTLP格式直送存储、与手动埋点数据统一关联。
2. 输出Continuous Profiling方案：
   - 工具选型：Pyroscope（多语言）/ Parca（云原生）/ pprof（Go原生）。
   - 采集策略：生产环境低频率持续采集（如10秒/分钟）、问题发生时高频率采集。
   - 分析方法：火焰图对比（正常vs异常）、Top函数CPU消耗、锁竞争分析。

将结果保存到 `output/observability_architecture.md` 或直接在对话中呈现。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/02_网络架构与中间件.md > 服务治理与观测 > 三支柱协同]
- [参考: Agents知识库/0_超级编程行业知识库/02_网络架构与中间件.md > 服务治理与观测 > 技术栈核心]
- [参考: Agents知识库/0_超级编程行业知识库/02_网络架构与中间件.md > 服务治理与观测 > 方法论体系]

### Step 3: 验证与交付

**Goal**: 确保观测方案完整可落地、与知识库一致
**Completion criterion**: 已通过 checklist 逐项核对

1. 读取 `checklist/service_governance_workflow_checklist.md`。
2. 逐项核对输出是否覆盖要求的知识点。
3. 确认所有关键论断均能在知识库中找到支撑。
4. 如有遗漏或偏差，补充修正。
5. 向用户交付最终答案。

## Post-Workflow

1. 记录完成状态到 `task/current_task.md`。
2. 如需深入某一具体技术（如"eBPF Beyla部署实战"、"OpenTelemetry采样策略调优"），在当前 Agent 内继续追问并输出。
