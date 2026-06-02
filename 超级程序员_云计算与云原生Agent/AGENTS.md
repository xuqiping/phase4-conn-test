# AGENTS.md — Task Routing Table

## Agent: 超级程序员_云计算与云原生Agent

本文件是任务路由的唯一真相来源。当用户向本子Agent提出请求时，读取此表以确定加载哪个细粒度工作流。

## Routing Table

| 任务关键词 / 意图 | 工作流文件 | 描述 |
|------------------|-----------|------|
| 阿里云,腾讯云,华为云,百度云,运营商云,天翼云,公有云,云厂商选型,云架构,Landing Zone,Well-Architected,成本优化,FinOps,迁移上云,出海,云网融合 | workflow/public_cloud_ecosystem_workflow.md | 公有云厂商生态：选型（阿里云/腾讯云/华为云/百度智能云/运营商云）、架构设计、Landing Zone治理、FinOps成本优化、迁移上云 |
| Docker,K8s,Kubernetes,容器,容器化,镜像,Harbor,Helm,Serverless容器,Knative,KEDA,Karpenter,容器安全,供应链安全,Trivy,Cosign,SBOM | workflow/container_technology_workflow.md | 容器技术：Docker/K8s架构设计、Helm包管理、镜像管理（Harbor/供应链安全）、Serverless容器（Knative/KEDA/Karpenter） |
| CI/CD,GitHub Actions,GitLab CI,Argo Workflows,Tekton,GitOps,Argo CD,Flux,服务网格,Istio,Ambient Mesh,云原生安全,Falco,Tetragon,Kyverno,Vault,mTLS,FinOps,Kubecost | workflow/cloud_native_ecosystem_workflow.md | 云原生生态：CI/CD流水线、GitOps运维、Istio服务网格（Ambient Mesh）、云原生安全全链路、FinOps成本优化 |
| VMware,虚拟化,KVM,Proxmox,OpenStack,私有云,HCI,超融合,深信服,SmartX,Nutanix,华为FusionCube,混合云,SD-WAN,MPLS,信创,鲲鹏,麒麟,国密,机密计算,TDX,SEV,Kubevirt | workflow/virtualization_private_cloud_workflow.md | 虚拟化与私有云：VMware替代（KVM/Proxmox）、OpenStack搭建、HCI超融合（深信服/SmartX/Nutanix/华为）、信创全栈、混合云组网、机密计算、Kubevirt |

## Notes

- 本子Agent处理所有与云计算、云原生、容器、虚拟化、私有云相关的请求。
- 关键词覆盖范围要足够广以捕获同义/转述请求。
- 新增能力时在此表添加新行，并创建对应 workflow + checklist 文件对。

## Cross-Module Dependencies

- `public_cloud_ecosystem_workflow.md` Step 2 中的容器化架构可能引用 [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 容器技术 > Kubernetes]（ACK/TKE/CCE托管K8s）
- `container_technology_workflow.md` Step 2 中的镜像扫描可能引用 [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 云原生生态 > 供应链安全]（Trivy/Cosign/SLSA）
- `cloud_native_ecosystem_workflow.md` Step 2 中的CI/CD构建镜像可能引用 [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 容器技术 > 镜像管理]（Harbor/OCI Spec）
- `virtualization_private_cloud_workflow.md` Step 2 中的Kubevirt GPU虚拟化可能引用 [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 容器技术 > Serverless容器]（Karpenter GPU节点供给）
