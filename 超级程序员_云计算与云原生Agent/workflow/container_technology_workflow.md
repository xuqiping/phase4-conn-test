# Container Technology Workflow

## Purpose

基于容器技术知识体系，为用户提供容器化方案（Docker/Kubernetes）、镜像管理（Harbor/OCI）、Serverless容器（Knative/KEDA/Karpenter）或容器安全加固的技术支持。覆盖namespace+cgroups隔离、OverlayFS分层镜像、声明式API、Helm包管理、供应链安全（Trivy/Cosign/SBOM）等核心技术。

## Prerequisites

- 用户已明确容器技术场景或问题
- 知识库文件 `04_前端开发与用户交互.md` 及子目录文件可访问

## Steps

### Step 1: 识别容器技术需求场景

**Goal**: 明确用户的容器化需求类型、当前容器化阶段和规模预期
**Completion criterion**: 已确定场景标签、容器化阶段、集群规模和特殊要求

1. 读取用户消息，提取以下信息：
   - 场景类型：容器化改造 / K8s架构设计 / 镜像管理优化 / Serverless容器 / 容器安全加固 / 容器化迁移
   - 当前容器化阶段：无容器（虚拟机/物理机） / 已有Docker（单机/Compose） / 已有K8s（需要优化/升级） / 未知
   - 集群规模：节点数（<10 / 10-50 / 50-200 / 200+）、Pod数量预期、Namespace数量
   - 工作负载类型：无状态Web服务 / 有状态数据库 / 批处理任务 / AI推理（GPU） / 混合
   - 特殊要求：多租户隔离 / GPU虚拟化 / 边缘部署 / 离线环境（无公网） / 信创ARM适配
   - 团队成熟度：是否有K8s运维经验、是否使用托管K8s（ACK/EKS/GKE/TKE）

2. 对照知识库中的技术栈全景初步判断：
   - 无容器→全新容器化 → Docker化 → K8s编排 → GitOps运维
   - 已有Docker→上K8s → Deployment/Service/Ingress配置 → Helm包管理 → HPA弹性
   - 需要极致弹性+成本优化 → Serverless容器（Knative/KEDA/Karpenter）
   - 大规模+安全要求高 → 镜像供应链安全（Trivy扫描+Cosign签名+SBOM）+ 运行时安全（Falco+eBPF）

3. 如有信息缺失，向用户追问不超过2个澄清问题。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 容器技术]
- [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 各L2摘要 > 容器技术]

### Step 2: 输出容器技术方案

**Goal**: 产出针对性的容器化、K8s架构或Serverless容器方案
**Completion criterion**: 输出包含容器化策略、K8s架构、镜像管理、Serverless方案、安全加固

根据Step 1确定的场景，按以下分支处理：

**分支A — 容器化改造**：
1. 输出容器化评估框架：
   - 应用适配度：有状态/无状态、配置外置化、日志标准化、健康检查接口
   - 依赖梳理：外部服务（数据库/缓存/MQ）、共享存储、网络端口、环境变量
   - 数据持久化：有状态应用的数据卷方案（PVC/ConfigMap/Secret）
2. 给出Dockerfile最佳实践：
   - 多阶段构建（编译阶段+运行阶段分离，镜像体积最小化）
   - 基础镜像选型（Alpine/Distroless/官方精简版）
   - 安全加固（非root运行、只读文件系统、删除shell工具、签名验证）
   - 镜像瘦身（.dockerignore、层合并、清理缓存）
3. 输出容器化迁移路径：单体应用→Docker化→Docker Compose本地验证→K8s YAML编写→集群部署→监控接入。

**分支B — K8s架构设计**：
1. 输出集群架构：
   - 控制平面：高可用设计（3节点etcd+3节点API Server）、托管vs自建选型
   - 工作节点：节点池划分（通用计算/内存优化/GPU/ARM信创）、污点与容忍度设计
   - 网络：CNI选型（Calico/Flannel/Cilium）、Service类型（ClusterIP/NodePort/LoadBalancer/Ingress）、网络策略（微分段隔离）
   - 存储：CSI插件选型（云厂商CSI/Rook-Ceph/NFS）、StorageClass动态供给
2. 给出工作负载设计：
   - 无状态：Deployment（滚动更新策略、PodDisruptionBudget）
   - 有状态：StatefulSet（有序部署、稳定网络标识、持久化存储）
   - 守护进程：DaemonSet（日志采集/监控Agent/网络代理）
   - 批处理：Job/CronJob（并行度控制、失败重试、资源限制）
3. 输出弹性策略：HPA（CPU/内存/自定义指标自动伸缩）、VPA（垂直Pod自动调优request/limit）、Cluster Autoscaler（节点级弹性伸缩）。
4. 附Helm包管理规范：Chart目录结构、values.yaml配置化、模板函数、依赖管理、版本发布流程。

**分支C — 镜像管理**：
1. 输出镜像仓库选型：Harbor（企业级、RBAC、复制、高可用）vs 云厂商镜像服务（ACK镜像仓库/ECR/ACR）vs 自建Registry。
2. 给出供应链安全方案：
   - 扫描：Trivy（漏洞扫描/密钥检测/Misconfiguration）集成CI流水线
   - 签名：Cosign（Sigstore生态、密钥less签名、透明日志验证）
   - SBOM：Syft生成软件物料清单、SLSA供应链完整性框架
   - 准入控制：Kyverno/OPA策略（只允许签名镜像、禁止高危CVE、强制标签规范）
3. 输出镜像分发策略：
   - 多Registry复制（开发/测试/生产隔离）
   - 镜像预热（Dragonfly/P2P分发、边缘节点镜像缓存）
   - 镜像保留策略（自动清理旧版本、Tag保留规则）

**分支D — Serverless容器**：
1. 输出Serverless容器选型对比：
   - Knative：请求驱动（Request→激活容器→处理→缩到零）、Serving（自动伸缩、流量分割、灰度发布）+ Eventing（事件源绑定、触发器）
   - KEDA：事件驱动弹性（Kafka/RabbitMQ/SQS等事件源→自动扩缩Pod→缩到零）、与HPA协同
   - Karpenter：极速节点供给（直接对接云厂商API、无节点组预配置、按Pod需求自动选实例类型）
2. 给出适用场景：
   - Knative：API服务、Webhook、事件处理、不常访问的后台服务（缩到零省成本）
   - KEDA：消息队列消费者、定时批处理、事件流处理（按事件量弹性）
   - Karpenter：大规模集群、多样化工作负载、Spot实例混合、成本敏感场景
3. 输出成本对比：Serverless容器 vs 常驻Pod vs Spot实例，附TCO计算模型。

将结果保存到 `output/container_architecture.md` 或直接在对话中呈现。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 各L2摘要 > 容器技术]
- [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 关键技术决策路径 > 新项目基础设施/微服务架构]

### Step 3: 验证与交付

**Goal**: 确保容器方案在生产环境可行、与知识库一致
**Completion criterion**: 已通过 checklist 逐项核对

1. 读取 `checklist/container_technology_workflow_checklist.md`。
2. 逐项核对输出是否覆盖要求的知识点。
3. 确认所有关键论断均能在知识库中找到支撑。
4. 如有遗漏或偏差，补充修正。
5. 向用户交付最终答案。

## Post-Workflow

1. 记录完成状态到 `task/current_task.md`。
2. 如需深入某一具体技术（如"Knative Serving自动扩缩配置"、"Harbor高可用双活架构"），在当前 Agent 内继续追问并输出。