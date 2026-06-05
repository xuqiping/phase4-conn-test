# Container Technology Workflow Checklist

在完成 `workflow/container_technology_workflow.md` 的每一步后，使用此检查清单进行交叉验证。每个项目必须回答**是**才算完成。如果有任何项目回答**否**，修复输出并重新验证。

## Step 1: 识别容器技术需求场景

- [ ] 已明确场景类型（容器化改造/K8s架构设计/镜像管理优化/Serverless容器/容器安全加固/容器化迁移）
- [ ] 已识别当前容器化阶段（无容器/已有Docker/已有K8s/未知）
- [ ] 已提取集群规模（节点数/Pod数量预期/Namespace数量）
- [ ] 已提取工作负载类型（无状态Web/有状态数据库/批处理/AI推理/混合）
- [ ] 已提取特殊要求（多租户隔离/GPU虚拟化/边缘部署/离线环境/信创ARM）
- [ ] 已识别团队成熟度和是否使用托管K8s
- [ ] 已对照知识库技术栈全景完成初步判断（无容器→Docker→K8s/已有Docker→K8s/需要弹性→Serverless/安全要求高→供应链安全）
- [ ] 如有信息缺失，已向用户追问不超过2个澄清问题

## Step 2: 输出容器技术方案

- [ ] 如为容器化改造，容器化评估框架已输出（应用适配度/依赖梳理/数据持久化）
- [ ] 如为容器化改造，Dockerfile最佳实践已覆盖（多阶段构建/基础镜像选型/安全加固/镜像瘦身）
- [ ] 如为容器化改造，容器化迁移路径已给出（单体→Docker化→Compose验证→K8s YAML→集群部署→监控接入）
- [ ] 如为K8s架构设计，集群架构已覆盖（控制平面高可用/工作节点池划分/网络CNI/存储CSI）
- [ ] 如为K8s架构设计，工作负载设计已说明（Deployment/StatefulSet/DaemonSet/Job/CronJob）
- [ ] 如为K8s架构设计，弹性策略已输出（HPA/VPA/Cluster Autoscaler）
- [ ] 如为K8s架构设计，Helm包管理规范已给出（Chart结构/values配置/模板函数/依赖管理）
- [ ] 如为镜像管理，镜像仓库选型已对比（Harbor/云厂商镜像服务/自建Registry）
- [ ] 如为镜像管理，供应链安全方案已覆盖（Trivy扫描/Cosign签名/SBOM/Kyverno准入控制）
- [ ] 如为镜像管理，镜像分发策略已说明（多Registry复制/镜像预热/保留策略）
- [ ] 如为Serverless容器，选型对比已输出（Knative/KEDA/Karpenter）
- [ ] 如为Serverless容器，适用场景已给出（Knative→API服务/KEDA→事件处理/Karpenter→大规模混合负载）
- [ ] 如为Serverless容器，成本对比已说明（Serverless vs 常驻Pod vs Spot实例）
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
