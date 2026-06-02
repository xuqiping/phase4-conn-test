# Operations Monitoring and Incident Response Workflow

## Purpose

基于运维监控与故障响应知识框架，提供从可观测性建设到混沌工程的标准化执行路径，覆盖OpenTelemetry可观测性三支柱、SLO驱动告警、START故障排查SOP、FinOps容量规划、混沌工程韧性验证五大核心能力。

## Prerequisites

- 已确定技术栈（K8s/VM/Serverless/混合）
- 已确定监控基础设施（自建/云厂商托管/混合）

## Steps

### Step 1: OpenTelemetry可观测性三支柱建设

**Goal**: 建立基于OpenTelemetry的统一可观测性体系，实现Metrics/Logs/Traces三支柱关联
**Completion criterion**: OpenTelemetry Collector已部署，三支柱数据统一采集，Grafana（Mimir+Loki+Tempo）关联查询可用，数据生命周期管理策略已实施

依据可观测性框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 运维监控与故障响应]：
1. Metrics（指标）：
   - RED方法设计服务指标：Rate（请求速率）/ Errors（错误率）/ Duration（延迟分布）
   - USE方法设计资源指标：Utilization（利用率）/ Saturation（饱和度）/ Errors（错误数）
   - 采集：OTel SDK自动埋点 + Prometheus Exporter + Custom Metrics
2. Logs（日志）：
   - 结构化JSON格式：timestamp/level/service/trace_id/span_id/message/attributes
   - trace_id关联：将分布式追踪ID注入每条日志，实现跨服务日志关联
   - 采集：OTel SDK/Fluent Bit/Filebeat → OTel Collector → Loki/Elasticsearch
3. Traces（链路追踪）：
   - W3C Trace Context标准跨服务传播：traceparent/tracestate Header
   - 采样策略：Head-Based（固定比例）+ Tail-Based（只保留错误和慢请求）
   - 采集：OTel SDK自动埋点 → OTel Collector → Tempo/Jaeger
4. OTel Collector统一网关：
   - Receiver：接收各种格式（OTLP/Prometheus/Zipkin/Jaeger）
   - Processor：批处理/过滤/丰富元数据
   - Exporter：输出到后端（Mimir/Loki/Tempo/CloudWatch/Datadog）
5. 三支柱关联：
   - Exemplar：Metrics数据点关联Trace ID
   - Logs注入trace_id/span_id
   - Grafana统一查询：从Metrics Drill-down到Trace，从Trace跳转到Logs
6. 数据生命周期管理：
   - Metrics：降采样（1m→5m→1h），保留15天+1年聚合
   - Logs：热7天（SSD）/温90天（SATA）/冷180天+（对象存储）
   - Traces：Tail-Based Sampling保留错误和慢请求，保留7天
执行：
- 部署OTel Collector（DaemonSet或Sidecar模式）
- 配置三支柱Exporter到Grafana Stack（Mimir+Loki+Tempo）
- 输出《可观测性配置手册》：采集规则 + 采样策略 + 存储策略 + Dashboard模板

### Step 2: SLO驱动告警与告警疲劳治理

**Goal**: 建立基于SLO的告警体系，实现告警分级与疲劳治理
**Completion criterion**: SLO/SLI/Error Budget已定义，告警分级P1-P4运行，30-60-90天治理路线图执行，on-call轮值机制建立

依据SLO驱动告警框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > SLO驱动告警]：
1. SLO/SLI/Error Budget定义：
   - SLI（服务等级指标）：如"HTTP 200请求占比""P99延迟<200ms"
   - SLO（服务等级目标）：如"99.9%的请求在200ms内返回"
   - Error Budget = 1 - SLO（如0.1%的错误预算）
   - Burn Rate：Error Budget消耗速度，决定告警紧急度
2. 告警分级标准：
   - P1：服务完全不可用，15分钟响应，立即on-call
   - P2：核心功能受损，1小时响应，工作时间内处理
   - P3：非核心功能异常，4小时响应，下一个工作日处理
   - P4：轻微异常/预警，24小时响应，计划内处理
3. SLO驱动告警规则：
   - 基于Error Budget消耗率而非静态阈值
   - 快速消耗（1小时burn 2%）→ P1告警
   - 慢速消耗（3天burn 5%）→ P2告警
   - 每个告警必须附带Runbook链接和上下文（最近变更/相关指标/相似历史事件）
4. 告警疲劳治理30-60-90路线图：
   - 30天：识别Top 10高频告警，分析根因（误报/重复/低价值）
   - 60天：配置grouping（同类告警聚合）/inhibition（高优抑制低优）/依赖抑制规则
   - 90天：建立月度告警审查机制，告警量降低50%
5. on-call管理：
   - Primary + Secondary双层轮值
   - 每周告警<10个、夜间唤醒<2次为健康指标
   - 轮值表自动轮换（PagerDuty/OpsGenie/自研）
执行：
- 输出《SLO定义手册》：每个服务的SLI/SLO/Error Budget/Burn Rate告警规则
- 配置Alertmanager/PagerDuty告警路由与抑制规则
- 输出《on-call手册》：轮值规则/响应SLA/升级路径/交接流程

### Step 3: START故障排查SOP

**Goal**: 建立标准化的故障排查流程，实现5分钟内确认-评估-启动响应
**Completion criterion**: START模型已培训，故障排查Runbook覆盖Top 10故障场景，RCA模板已建立，Blameless文化已推行

依据START故障排查框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > START故障排查SOP]：
1. START五步法：
   - S（Signal）：接收告警/用户反馈/监控异常，确认故障发生
   - T（Triage）：5分钟内完成确认-评估-启动响应，判断影响范围与紧急度
   - A（Analyze）：排查策略用二分法思维将O(N)变为O(logN)，逐层排除（应用层→服务层→基础设施层→网络层）
   - R（Resolve）：执行修复动作（回滚/重启/扩容/限流/切流），验证恢复
   - T（Track）：记录故障时间线、根因、修复措施、改进项
2. 排查工具箱：
   - 应用层：日志检索（Loki/Kibana）、链路追踪（Tempo/Jaeger）、性能剖析（Pyroscope/Parca）
   - 服务层：服务网格监控（Istio/Kiali）、API网关日志、依赖健康检查
   - 基础设施层：K8s事件（kubectl describe/events）、容器资源（kubectl top）、节点状态
   - 网络层：DNS解析（dig/nslookup）、连通性（ping/curl/tcpdump）、路由追踪（traceroute）
3. RCA方法选择：
   - 5-Why：简单线性故障，30分钟完成
   - 鱼骨图（Ishikawa）：多因素故障，1-2小时完成
   - FTA故障树：复杂系统性故障，2-4小时完成
   - 2025年多Agent协作RCA：LLM+MCTS根因分析准确率提升33%-43%
4. Post-Mortem文化：
   - Blameless原则：关注系统改进而非个人追责
   - 时限：P0故障24小时内完成初稿，48小时内复盘会
   - 模板：时间线/影响范围/根因/修复措施/改进项/责任人/完成时间
   - 公开：全员可查阅，改进项纳入Backlog跟踪
执行：
- 输出《故障排查Runbook》：Top 10故障场景 × 诊断决策树（if-else）× 命令 × 预期输出 × 异常处理
- 输出《RCA模板》：5-Why/鱼骨图/FTA三种模板
- 建立故障知识库：Post-Mortem → SECI外化 → 新人培训素材

### Step 4: FinOps容量规划与成本优化

**Goal**: 建立容量规划体系与FinOps成本优化机制，实现资源利用率最大化
**Completion criterion**: 容量规划四步法运行正常，USL拐点已识别，FinOps三层漏斗实施，云成本降低20-40%

依据容量规划框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > FinOps容量规划]：
1. 容量规划四步法：
   - 基准测量：当前QPS/延迟/资源利用率基线
   - 增长预测：基于业务增长模型（线性/指数/季节性）预测未来3-6-12个月
   - 峰值缓冲：预留20-30%余量应对突发流量
   - 采购扩容：基于预测制定采购计划（云资源/硬件）
2. USL模型（Universal Scalability Law）：
   - 揭示系统吞吐量拐点（由于 contention 和 coherency 开销）
   - 用于预测扩容收益与瓶颈点
3. 性能调优ROI优先级：
   - ★★★★★ 缓存优化、索引优化（收益最高）
   - ★★★★ 异步化、批处理、连接池优化
   - ★★★ 代码算法优化、数据结构优化
   - ★★ 硬件升级（最后手段）
4. JVM调优（Java系统）：
   - ZGC（JDK21+）：亚毫秒停顿，低延迟首选
   - G1GC：平衡吞吐与延迟
   - 配置：堆大小/元空间/线程栈/GC日志
5. FinOps三层漏斗：
   - L1 消除浪费（10-20%）：闲置资源清理/自动启停/Right-sizing
   - L2 提高利用率（20-40%）：Spot实例/预留实例/自动伸缩
   - L3 架构优化（30-70%）：Serverless替代常驻/缓存减少计算/CDN减少带宽
执行：
- 输出《容量规划报告》：基准数据 + 增长预测 + 扩容计划 + USL分析
- 部署FinOps工具：Kubecost（K8s成本）/ CloudHealth（多云）/ 自研Dashboard
- 输出《成本优化路线图》：消除浪费→提高利用率→架构优化，每季度目标

### Step 5: 混沌工程韧性验证

**Goal**: 建立混沌工程体系，通过可控故障注入验证系统韧性
**Completion criterion**: 混沌工程实验库已建立，涵盖故障五维度，安全网机制生效，Chaos Mesh已部署

依据混沌工程框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 混沌工程韧性验证]：
1. 混沌工程成熟度模型：
   - L0：无混沌工程，依赖自然故障学习
   - L1：手动执行简单故障注入
   - L2：自动化实验，固定时间窗口运行
   - L3：生产环境运行，自动验证恢复
   - L4：持续混沌，故障注入成为CI/CD一部分
2. 实验设计ABCD法：
   - A（Assumption）：假设（如"单节点故障不影响服务可用性"）
   - B（Baseline）：基线（正常状态指标）
   - C（Control）：对照组（不注入故障的相同环境）
   - D（Delta）：差异指标（故障注入后的指标变化）
3. 故障五维度：
   - 计算：CPU满载/内存耗尽/进程杀死/容器OOM
   - 网络：延迟/丢包/DNS故障/分区
   - 存储：磁盘满/IO高延迟/文件系统损坏
   - 应用：依赖超时/依赖错误/异常抛出
   - 依赖：第三方服务故障/数据库主从切换/缓存失效
4. 安全网机制：
   - 终止开关：实验随时可停止
   - 自动停止条件：错误率>5%或P99>基线×3或可用性<99%
   - on-call确认：生产环境实验需值班人员确认
   - 时间窗口限制：仅在工作时间运行
   - 小范围开始：先单个Pod/单节点，再扩展
5. 创业团队"五个必做测试"：
   - 关机：随机关闭一个节点
   - 数据库重启：主库重启验证自动切换
   - 缓存失效：Redis/Memcached全部清空
   - 第三方超时：模拟外部API 10秒超时
   - 磁盘满：日志分区打满验证日志轮转
执行：
- 部署Chaos Mesh（CNCF孵化，K8s首选）或Gremlin
- 输出《混沌工程实验库》：假设 + 基线 + 故障类型 + 验证标准
- 建立《安全网配置》：自动停止条件 + 告警规则 + 回滚策略
- 每月执行1次混沌工程实验，输出《韧性评估报告》

## Post-Workflow

1. 读取 `checklist/operations_monitoring_workflow_checklist.md`
2. 交叉验证每个SLO定义、每次故障响应记录、每个混沌实验结果
3. 全部通过后输出《运维监控与故障响应总纲》并归档
