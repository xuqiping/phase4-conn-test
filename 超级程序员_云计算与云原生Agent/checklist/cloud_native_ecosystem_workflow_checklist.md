# Cloud Native Ecosystem Workflow Checklist

在完成 `workflow/cloud_native_ecosystem_workflow.md` 的每一步后，使用此检查清单进行交叉验证。每个项目必须回答**是**才算完成。如果有任何项目回答**否**，修复输出并重新验证。

## Step 1: 识别云原生生态需求场景

- [ ] 已明确场景类型（CI/CD流水线设计/GitOps运维/服务网格接入/云原生安全加固/FinOps成本优化/全链路云原生改造）
- [ ] 已识别当前DevOps成熟度（手动部署/脚本自动化/CI/CD流水线/GitOps/渐进式交付）
- [ ] 已识别现有技术栈（GitHub/GitLab/Jenkins/Argo/已有K8s/Istio/Linkerd）
- [ ] 已提取关键痛点（发布频率低/故障恢复慢/变更失败率高/安全漏洞频发/成本不可控）
- [ ] 已提取团队规模（开发人员数/运维人员数/是否有platform engineering团队）
- [ ] 已提取特殊要求（多集群管理/多租户隔离/合规审计/边缘K8s）
- [ ] 已对照知识库技术决策路径完成初步判断（CI/CD/GitOps/Istio/安全/FinOps）
- [ ] 如有信息缺失，已向用户追问不超过2个澄清问题

## Step 2: 输出云原生生态方案

- [ ] 如为CI/CD流水线设计，工具选型对比已输出（GitHub Actions/GitLab CI/Argo Workflows/Tekton）
- [ ] 如为CI/CD流水线设计，流水线设计规范已覆盖（CI阶段：代码拉取→测试→安全扫描→构建镜像→签名→推送；CD阶段：部署→集成测试→渐进式发布→验证→监控）
- [ ] 如为CI/CD流水线设计，质量门禁已说明（覆盖率阈值/CVE限制/代码规范/性能基准）
- [ ] 如为CI/CD流水线设计，DORA四指标度量已输出（部署频率/变更前置时间/变更失败率/服务恢复时间）
- [ ] 如为CI/CD流水线设计，渐进式交付策略已覆盖（蓝绿部署/金丝雀发布/滚动更新/Feature Flag）
- [ ] 如为GitOps运维，工具选型已对比（Argo CD Application/ApplicationSet/SyncWave vs Flux CD GOTK）
- [ ] 如为GitOps运维，架构设计已说明（单仓库/多仓库/App-of-Apps模式）
- [ ] 如为GitOps运维，密钥管理方案已覆盖（Sealed Secrets/External Secrets Operator/SOPS）
- [ ] 如为GitOps运维，漂移检测与自愈已说明（Auto-sync/Self-heal/手动同步策略）
- [ ] 如为服务网格Istio，架构选型已覆盖（Sidecar模式 vs Ambient Mesh节点级代理）
- [ ] 如为服务网格Istio，流量管理已输出（灰度发布VirtualService权重/Header路由/熔断DestinationRule/超时重试）
- [ ] 如为服务网格Istio，安全传输已覆盖（mTLS自动加密/AuthorizationPolicy访问控制/PeerAuthentication模式）
- [ ] 如为服务网格Istio，可观测性集成已说明（Envoy Metrics/Access Log/Trace）
- [ ] 如为云原生安全，供应链安全已覆盖（Trivy镜像扫描/Cosign签名/SBOM/SLSA）
- [ ] 如为云原生安全，运行时安全已输出（Falco规则检测/Tetragon eBPF执行/Kyverno策略即代码）
- [ ] 如为云原生安全，网络安全已说明（Cilium微分段/NetworkPolicy隔离）
- [ ] 如为云原生安全，密钥与身份管理已覆盖（Vault动态密钥/ServiceAccount RBAC/Pod Security Standards）
- [ ] 如为FinOps，成本可视化方案已说明（Kubecost/云厂商成本管家/OpenCost）
- [ ] 如为FinOps，资源优化策略已覆盖（VPA/KEDA/Karpenter/Spot实例）
- [ ] 如为FinOps，成本优化检查清单已给出（利用率<30%识别/闲置PVC清理/开发测试定时关停/镜像缓存共享）
- [ ] 如为FinOps，成本分摊与责任制已设计（Namespace/团队/项目标签分摊/月度报告/KPI/ResourceQuota）
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
