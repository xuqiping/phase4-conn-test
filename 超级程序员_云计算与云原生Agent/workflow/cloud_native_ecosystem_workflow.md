# Cloud Native Ecosystem Workflow

## Purpose

基于云原生生态知识体系，为用户提供CI/CD流水线（GitHub Actions/Argo Workflows）、GitOps（Argo CD/Flux）、服务网格Istio（Ambient Mesh）、云原生安全（Trivy/Falco/Kyverno/Vault）、FinOps成本优化等技术方案支持。覆盖DORA四指标、渐进式交付、Sealed Secrets、mTLS、Spot实例降本等核心实践。

## Prerequisites

- 用户已明确云原生生态场景或问题
- 知识库文件 `04_前端开发与用户交互.md` 及子目录文件可访问

## Steps

### Step 1: 识别云原生生态需求场景

**Goal**: 明确用户的云原生需求类型、DevOps成熟度和关键痛点
**Completion criterion**: 已确定场景标签、当前成熟度、技术栈和痛点优先级

1. 读取用户消息，提取以下信息：
   - 场景类型：CI/CD流水线设计 / GitOps运维 / 服务网格接入 / 云原生安全加固 / FinOps成本优化 / 全链路云原生改造
   - 当前DevOps成熟度：手动部署 / 脚本自动化 / CI/CD流水线 / GitOps / 渐进式交付（蓝绿/金丝雀）
   - 现有技术栈：GitHub/GitLab/其他、Jenkins/Argo/其他、已有K8s集群/无、Istio/Linkerd/无服务网格
   - 关键痛点：发布频率低（周级/月级） / 故障恢复慢（小时级） / 变更失败率高 / 安全漏洞频发 / 成本不可控
   - 团队规模：开发人员数、运维人员数、是否 platform engineering 团队
   - 特殊要求：多集群管理 / 多租户隔离 / 合规审计 / 边缘K8s

2. 对照知识库中的技术决策路径初步判断：
   - 发布频率低+变更失败率高 → CI/CD流水线（GitHub Actions/Argo Workflows）+ DORA四指标度量
   - 配置漂移+回滚困难 → GitOps（Argo CD/Flux）+ 声明式管理 + Sealed Secrets
   - 微服务流量管理复杂+安全传输需求 → Istio服务网格（VirtualService灰度 + mTLS安全）
   - 安全漏洞频发+合规要求高 → 云原生安全全链路（供应链扫描+运行时监控+策略即代码+密钥管理）
   - 成本不可控+资源浪费 → FinOps（VPA+KEDA+Spot+Kubecost可视化）

3. 如有信息缺失，向用户追问不超过2个澄清问题。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 云原生生态]
- [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 各L2摘要 > 云原生生态]

### Step 2: 输出云原生生态方案

**Goal**: 产出针对性的云原生工具链方案
**Completion criterion**: 输出包含技术栈选型、架构设计、实施步骤、度量指标

根据Step 1确定的场景，按以下分支处理：

**分支A — CI/CD流水线设计**：
1. 输出CI/CD工具选型对比：
   - GitHub Actions（GitHub生态、Actions Marketplace丰富、自托管Runner）
   - GitLab CI（一体化DevOps平台、内置Registry/监控/安全扫描）
   - Argo Workflows（K8s原生、DAG工作流、批处理场景、事件驱动）
   - Tekton（云原生Pipeline标准、CRD定义、可组合性强）
2. 给出流水线设计规范：
   - CI阶段：代码拉取 → 单元测试 → 安全扫描（Trivy/Snyk） → 构建镜像 → 镜像签名（Cosign） → 推送到Registry
   - CD阶段：镜像部署 → 集成测试 → 渐进式发布（蓝绿/金丝雀/滚动） → 生产验证 → 监控告警确认
   - 质量门禁：测试覆盖率阈值、CVE漏洞等级限制、代码规范检查、性能基准回归
3. 输出DORA四指标度量：部署频率（每日/每周）、变更前置时间（小时/天）、变更失败率（%）、服务恢复时间（MTTR）。
4. 附渐进式交付策略：
   - 蓝绿部署：两套环境并行、即时切换、快速回滚、资源成本高
   - 金丝雀发布：小流量验证、指标监控、自动扩量、自动回滚
   - 滚动更新：逐批替换、资源平滑、回滚慢、适合无状态服务
   - Feature Flag：功能开关控制、A/B测试、灰度放量、与发布解耦

**分支B — GitOps运维**：
1. 输出GitOps工具选型：
   - Argo CD：Application CRD、ApplicationSet多集群、SyncWave有序同步、Resource Hook生命周期控制
   - Flux CD：GOTK组件（Source/Kustomize/Helm/Image/Notification Controller）、GitOps Toolkit可组合
2. 给出GitOps架构设计：
   - 单仓库模式（Mono-repo）：所有环境配置在同一仓库、目录隔离、权限控制
   - 多仓库模式（Multi-repo）：应用代码与配置分离、团队自治、减少冲突
   - App-of-Apps模式：根Application管理子Application、层级关系清晰
3. 输出密钥管理方案：Sealed Secrets（Bitnami、kubeseal加密、集群内自动解密）vs External Secrets Operator（对接Vault/AWS Secrets Manager）vs SOPS（Mozilla、Age/GPG加密）。
4. 附漂移检测与自愈：Argo CD自动同步（Auto-sync）、自愈合（Self-heal）、手动同步策略（仅告警不自动修复）。

**分支C — 服务网格Istio**：
1. 输出Istio架构选型：
   - Sidecar模式（传统）：每Pod一个Envoy代理、功能完整但资源开销大（内存~100MB/实例）、启动延迟
   - Ambient Mesh（趋势）：无Sidecar、节点级ztunnel（L4安全传输）+waypoint proxy（L7策略）、资源节省50%+、2025-2026年关键趋势
2. 给出流量管理方案：
   - 灰度发布：VirtualService权重分流（5%→25%→50%→100%）、Header-based路由（内测用户/地域）
   - 熔断降级：DestinationRule连接池限制、异常检测自动驱逐、优雅降级策略
   - 超时重试：VirtualService per-try超时、重试次数、退避策略
3. 输出安全传输方案：
   - mTLS自动加密（Sidecar/ztunnel自动证书管理、双向认证）
   - AuthorizationPolicy（L4/L7访问控制、基于JWT/ServiceAccount/Namespace的细粒度授权）
   - PeerAuthentication（严格mTLS/宽容模式/关闭）
4. 附可观测性集成：Envoy Metrics（Prometheus指标）、Envoy Access Log（结构化日志）、Envoy Trace（Zipkin/Jaeger链路）。

**分支D — 云原生安全加固**：
1. 输出供应链安全方案：
   - 镜像扫描：Trivy（CVE/OS漏洞/语言包漏洞/Misconfiguration）集成CI流水线、阻断高危漏洞
   - 镜像签名：Cosign（Sigstore无密钥签名、Rekor透明日志验证）+ SLSA供应链完整性框架
   - SBOM管理：Syft生成、Grype扫描、Archivist存档、合规审计
2. 给出运行时安全方案：
   - Falco：基于规则的异常检测（系统调用监控、文件访问、网络连接、容器逃逸）
   - Tetragon：eBPF驱动的实时安全执行（进程级粒度的Kill/Override/Notify策略）
   - 策略即代码：Kyverno（K8s原生CRD策略、资源验证/变更/生成）+ OPA/Gatekeeper（Rego语言、灵活策略）
3. 输出网络安全方案：
   - Cilium（eBPF网络策略、L3-L7微分段、ClusterMesh跨集群连接、Hubble可视化）
   - 网络策略（NetworkPolicy）：Namespace隔离、Pod选择器、端口白名单、Egress控制
4. 附密钥与身份管理：
   - Vault（动态密钥、自动轮转、PKI证书管理、数据库凭据自动生成）
   - ServiceAccount+RBAC（最小权限原则、PodIdentity/IRSA云身份集成）
   - Pod Security Standards（Restricted/Baseline/Privileged准入策略）

**分支E — FinOps成本优化**：
1. 输出成本可视化方案：Kubecost（K8s成本分摊、Namespace/Deployment粒度、预算告警）/ 云厂商成本管家 / OpenCost（开源标准）。
2. 给出资源优化策略：
   - VPA（Vertical Pod Autoscaler）：自动分析历史用量、推荐最优request/limit、自动更新或仅建议模式
   - KEDA（Kubernetes Event-driven Autoscaling）：基于事件量（Kafka Lag/RabbitMQ队列深度/HTTP请求数）弹性伸缩、缩到零
   - Karpenter：极速节点供给、按需实例类型选择（Spot/On-Demand混合）、节点自动清理
   - Spot实例策略：中断容忍工作负载标记、Spot中断处理（Node Termination Handler）、Spot与On-Demand混合比例
3. 输出成本优化检查清单：
   - 资源利用率<30%的Deployment识别与优化
   - 闲置PVC/PV自动清理
   - 开发/测试环境定时关停（如非工作时间自动缩到零）
   - 镜像缓存共享减少拉取时间和流量费
4. 附成本分摊与责任制：按Namespace/团队/项目标签分摊、月度成本报告、成本优化KPI、资源配额（ResourceQuota）强制执行。

将结果保存到 `output/cloud_native_architecture.md` 或直接在对话中呈现。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 各L2摘要 > 云原生生态]
- [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 关键技术决策路径 > 微服务架构/安全合规/成本优化]
- [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 2025-2026年关键趋势 > 服务网格Ambient化/FinOps成为标配]

### Step 3: 验证与交付

**Goal**: 确保云原生方案完整可落地、与知识库一致
**Completion criterion**: 已通过 checklist 逐项核对

1. 读取 `checklist/cloud_native_ecosystem_workflow_checklist.md`。
2. 逐项核对输出是否覆盖要求的知识点。
3. 确认所有关键论断均能在知识库中找到支撑。
4. 如有遗漏或偏差，补充修正。
5. 向用户交付最终答案。

## Post-Workflow

1. 记录完成状态到 `task/current_task.md`。
2. 如需深入某一具体技术（如"Argo CD ApplicationSet多集群部署"、"Istio Ambient Mesh waypoint配置"），在当前 Agent 内继续追问并输出。