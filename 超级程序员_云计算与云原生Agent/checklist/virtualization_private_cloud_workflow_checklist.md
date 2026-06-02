# Virtualization & Private Cloud Workflow Checklist

在完成 `workflow/virtualization_private_cloud_workflow.md` 的每一步后，使用此检查清单进行交叉验证。每个项目必须回答**是**才算完成。如果有任何项目回答**否**，修复输出并重新验证。

## Step 1: 识别虚拟化/私有云需求场景

- [ ] 已明确场景类型（VMware替代评估/KVM虚拟化/私有云搭建/HCI超融合选型/混合云组网/信创全栈/机密计算）
- [ ] 已识别现有虚拟化平台（VMware vSphere/Hyper-V/无虚拟化/其他）
- [ ] 已提取规模（虚拟机数量/物理服务器数量/数据中心数量）
- [ ] 已提取驱动因素（VMware涨价/信创政策/数据主权/成本优化/性能瓶颈）
- [ ] 已识别行业属性（金融/政企政务/运营商/制造业）
- [ ] 已提取特殊要求（GPU虚拟化/边缘节点管理/跨地域灾备/国密算法全栈）
- [ ] 已对照知识库技术决策路径完成初步判断（VMware替代→KVM/Proxmox/信创→鲲鹏+麒麟/混合云→MPLS+SD-WAN/AI+虚拟化→Kubevirt）
- [ ] 如有信息缺失，已向用户追问不超过2个澄清问题

## Step 2: 输出虚拟化/私有云方案

- [ ] 如为VMware替代，替代方案对比已输出（KVM+QEMU+Libvirt/Proxmox VE/Nutanix AHV/深信服aCloud/华为FusionCompute）
- [ ] 如为VMware替代，迁移评估框架已覆盖（兼容性/性能基准/功能差距/运维流程适配）
- [ ] 如为VMware替代，迁移路径已给出（评估→试点→批量→收尾）
- [ ] 如为VMware替代，回滚预案已说明（快速回切/数据同步保持/DNS/IP切换）
- [ ] 如为OpenStack私有云，架构设计已覆盖（Nova/Neutron/Cinder/Swift/Keystone/Glance/Horizon）
- [ ] 如为OpenStack私有云，高可用设计已说明（控制节点3节点集群/MariaDB Galera/RabbitMQ集群/HAProxy）
- [ ] 如为OpenStack私有云，存储方案已选型（Ceph/商业存储/SmartX/aSAN）
- [ ] 如为OpenStack私有云，网络架构已覆盖（VLAN/VXLAN/OVN）
- [ ] 如为OpenStack私有云，升级策略已给出（SLURP跨版本升级/滚动升级）
- [ ] 如为HCI超融合，选型对比已输出（深信服aCloud/SmartX/Nutanix/华为FusionCube）
- [ ] 如为HCI超融合，四象限选型法已应用（信创+性能/信创+成本/通用+性能/通用+成本）
- [ ] 如为信创全栈，芯片已覆盖（鲲鹏/海光/飞腾/龙芯）
- [ ] 如为信创全栈，操作系统已覆盖（麒麟/统信UOS/openEuler）
- [ ] 如为信创全栈，数据库已覆盖（达梦/人大金仓/OceanBase/GaussDB/TiDB）
- [ ] 如为信创全栈，国密算法已说明（SM2/SM3/SM4全栈支持/SSL国密改造/HSM密钥管理）
- [ ] 如为信创全栈，合规 checklist已给出（等保测评/密码测评/信创适配测试/供应链安全审查）
- [ ] 如为混合云组网，互联方案已覆盖（MPLS专线/SD-WAN/Transit Gateway/VPN备份）
- [ ] 如为混合云组网，数据同步策略已说明（CDC实时同步/批量ETL/一致性保障）
- [ ] 如为混合云组网，边缘-中心-云三层架构已设计（5G MEC边缘/KubeEdge/K3s→私有云汇聚→公有云分析）
- [ ] 如为混合云组网，零信任安全架构已输出（IAP/微分段/国密传输/持续身份验证）
- [ ] 如为机密计算，方案已覆盖（Intel TDX/AMD SEV-SNP/海光CSV）
- [ ] 如为机密计算，应用场景已说明（金融隐私计算/政务数据不出域/AI模型训练数据保护）
- [ ] 如为Kubevirt，架构已说明（VMI CRD/virt-launcher Pod/virt-handler DaemonSet/K8s统一调度）
- [ ] 如为Kubevirt，适用场景和与容器对比已给出（启动速度/资源密度/隔离级别）
- [ ] 如为GPU虚拟化，方案已覆盖（NVIDIA MIG/vGPU/PCIe直通）
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
