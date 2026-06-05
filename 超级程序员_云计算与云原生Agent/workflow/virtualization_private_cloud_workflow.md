# Virtualization & Private Cloud Workflow

## Purpose

基于虚拟化与私有云知识体系，为用户提供VMware替代方案评估、KVM虚拟化架构、OpenStack私有云搭建、HCI超融合选型、混合云组网（MPLS/SD-WAN/Transit Gateway）或信创全栈私有云的技术支持。覆盖vSphere/ESXi、Proxmox、深信服/SmartX、鲲鹏+麒麟+国产DB全栈、机密计算（TDX/SEV-SNP）等核心技术。

## Prerequisites

- 用户已明确虚拟化/私有云场景或问题
- 知识库文件 `04_前端开发与用户交互.md` 及子目录文件可访问

## Steps

### Step 1: 识别虚拟化/私有云需求场景

**Goal**: 明确用户的虚拟化/私有云需求类型、现有基础设施和驱动因素
**Completion criterion**: 已确定场景标签、现有虚拟化平台、规模和驱动因素

1. 读取用户消息，提取以下信息：
   - 场景类型：VMware替代评估 / KVM虚拟化 / 私有云搭建 / HCI超融合选型 / 混合云组网 / 信创全栈 / 机密计算
   - 现有虚拟化平台：VMware vSphere（版本、规模、许可到期时间）/ Hyper-V / 无虚拟化（物理机）/ 其他
   - 规模：虚拟机数量（<50 / 50-200 / 200-1000 / 1000+）、物理服务器数量、数据中心数量
   - 驱动因素：VMware Broadcom涨价3-10x / 信创政策要求 / 数据主权（不出域）/ 成本优化 / 性能瓶颈
   - 行业属性：金融（等保四级+国密）/ 政企政务（信创2+8+N）/ 运营商（云网融合）/ 制造业（边缘计算）
   - 特殊要求：GPU虚拟化（AI训练推理）/ 边缘节点管理 / 跨地域灾备 / 国密算法全栈

2. 对照知识库中的技术决策路径初步判断：
   - VMware替代 → KVM/Proxmox/OpenStack（2025-2028迁移黄金窗口）
   - 信创合规 → 鲲鹏/海光+麒麟+国产DB+国密全栈+华为云Stack/ZStack
   - 混合云互联 → MPLS专线+SD-WAN备份+Transit Gateway跨云路由+零信任
   - AI+虚拟化 → GPU虚拟化（MIG/vGPU）+ Kubevirt（K8s统一调度VM和容器）
   - 安全增强 → 机密计算（Intel TDX/AMD SEV-SNP/海光CSV）

3. 如有信息缺失，向用户追问不超过2个澄清问题。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 虚拟化与私有云]
- [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 各L2摘要 > 虚拟化与私有云]

### Step 2: 输出虚拟化/私有云方案

**Goal**: 产出针对性的虚拟化替代、私有云搭建或混合云组网方案
**Completion criterion**: 输出包含技术栈选型、架构设计、迁移路径、成本估算

根据Step 1确定的场景，按以下分支处理：

**分支A — VMware替代评估与迁移**：
1. 输出替代方案对比：
   - KVM+QEMU+Libvirt：开源免费、性能接近原生、云厂商底层均采用、但管理功能弱于vCenter
   - Proxmox VE：开源、Web管理界面、Ceph集成、社区活跃、适合中小规模
   - Nutanix AHV：企业级、超融合、与Nutanix生态深度集成、许可成本较高
   - 深信服aCloud：国内厂商、超融合、信创适配好、本地化服务强
   - 华为FusionCompute：企业级、与华为云Stack无缝集成、信创全栈支持
2. 给出迁移评估框架：
   - 兼容性评估：VMware VM格式（VMDK）→ 目标平台格式（QCOW2/RAW）转换、驱动兼容性（Virtio/IDE/SCSI）
   - 性能基准测试：相同负载下CPU/内存/磁盘/网络性能对比、IOPS延迟对比
   - 功能差距分析：vMotion/live migration、DRS/DPM自动调度、vSAN存储、NSX网络虚拟化
   - 运维流程适配：备份策略调整、监控告警迁移、变更流程重建
3. 输出迁移路径：
   - 评估阶段（1-2月）：PoC测试、关键业务验证、性能基准、回滚验证
   - 试点阶段（1-2月）：非核心业务批量迁移、运维流程磨合、监控完善
   - 批量阶段（3-6月）：按业务优先级排序迁移、双跑并行验证、灰度切换
   - 收尾阶段（1月）：VMware下线、许可终止、团队培训、文档归档
4. 附回滚预案：迁移失败时的快速回切方案、数据同步保持、DNS/IP快速切换。

**分支B — OpenStack私有云搭建**：
1. 输出OpenStack架构设计：
   - 核心组件：Nova（计算）、Neutron（网络）、Cinder（块存储）、Swift（对象存储）、Keystone（身份）、Glance（镜像）、Horizon（Dashboard）
   - 高可用设计：控制节点3节点集群（MariaDB Galera+RabbitMQ集群+HAProxy负载均衡）
   - 计算节点：KVM虚拟化、OVS/OVN网络、Ceph分布式存储后端
   - 部署方式：Kolla-Ansible（容器化部署、推荐）/ TripleO / 手动部署
2. 给出存储方案选型：
   - Ceph（通用、高可用、性能中等）：RBD块存储+RGW对象存储+CephFS文件存储
   - vSAN替代：SmartX（国内超融合、性能优化）/ 深信服aSAN
   - 商业存储：EMC/华为OceanStor/NetApp（企业级、高性能、高成本）
3. 输出网络架构：
   - VLAN模式：简单、适合小规模、二层隔离
   - VXLAN模式：Overlay网络、适合大规模、跨机架迁移
   - OVN：OVS进化版、原生支持L3路由、ACL防火墙、NAT、负载均衡
4. 附升级策略：SLURP（Skip Level Upgrade Release Process）跨版本升级、滚动升级、数据备份验证。

**分支C — HCI超融合与信创全栈**：
1. 输出HCI选型对比：
   - 深信服aCloud：国内领先、超融合+云管平台、信创适配（鲲鹏/海光/飞腾+麒麟）、医疗/教育/政府客户多
   - SmartX（志凌海纳）：金融/电信行业多、自研分布式存储ZBS、性能优化强、K8s原生支持
   - Nutanix：国际领先、AHV+AOS、 prism管理、成本高但生态成熟
   - 华为FusionCube：与华为云Stack集成、鲲鹏+昇腾+GaussDB全栈、政企市场强
2. 给出四象限选型法：
   - 象限1（信创+性能）：华为FusionCube / SmartX（金融场景）
   - 象限2（信创+成本）：深信服aCloud / 青云QingCloud
   - 象限3（通用+性能）：Nutanix / VMware vSAN（非信创场景）
   - 象限4（通用+成本）：Proxmox+Ceph / OpenStack（自建、技术能力强）
3. 输出信创全栈方案：
   - 芯片：鲲鹏（ARM/华为）/ 海光（x86/AMD授权）/ 飞腾（ARM/国防科大）/ 龙芯（MIPS/LoongArch）
   - 操作系统：麒麟（中标/银河）、统信UOS、 openEuler
   - 数据库：达梦、人大金仓、OceanBase、GaussDB、TiDB
   - 中间件：东方通TongWeb、金蝶Apusic、宝兰德BES
   - 国密算法：SM2/SM3/SM4全栈支持、SSL证书国密改造、密钥管理HSM
4. 附合规 checklist：等保测评、密码测评、信创适配测试、供应链安全审查。

**分支D — 混合云组网与边缘架构**：
1. 输出混合云互联方案：
   - 专线：MPLS VPN（稳定、高成本、适合核心数据）/ 云专线（云厂商托管、弹性带宽）
   - SD-WAN：软件定义广域网（低成本、快速部署、智能选路、适合分支互联）
   - Transit Gateway：跨云路由（阿里云CEN/腾讯云CCN/华为云ER/云厂商互联产品）
   - VPN备份：IPSec/SSL VPN（低成本、易部署、作为主线路备份）
2. 给出数据同步策略：
   - 实时同步：CDC（Debezium/Canal/云厂商DTS）、消息队列（Kafka/RocketMQ）
   - 批量同步：ETL工具（DataX/SeaTunnel/云厂商DataWorks）、对象存储跨区域复制
   - 一致性保障：分布式事务（Seata/SAGA）、最终一致性校验、数据对账机制
3. 输出边缘-中心-云三层架构：
   - 边缘层：5G MEC边缘节点（工业网关/车载边缘/智能摄像头）、KubeEdge/K3s轻量K8s、本地推理（AI模型下沉）
   - 汇聚层：私有云/区域数据中心（数据汇聚、初步分析、实时决策）、OpenStack/K8s集群
   - 云端层：公有云（大数据分析、AI训练、长期存储、全局调度）、阿里云/腾讯云/华为云
4. 附零信任安全架构：Identity-Aware Proxy（IAP）、微分段（Cilium/NSX）、国密传输（SM2/SM4 VPN）、持续身份验证（mTLS+JWT）。

**分支E — 机密计算与Kubevirt**：
1. 输出机密计算方案：
   - Intel TDX（Trust Domain Extensions）：VM级TEE、内存加密、远程证明、Azure/阿里云支持
   - AMD SEV-SNP（Secure Encrypted Virtualization-Secure Nested Paging）：VM级加密、内存完整性保护、AWS/腾讯云支持
   - 海光CSV（China Secure Virtualization）：国产CPU机密计算、信创场景、等保四级增强
   - 应用场景：金融数据联合分析（隐私计算）、政务数据不出域计算、AI模型训练数据保护
2. 给出Kubevirt架构：
   - K8s统一调度VM和容器：VMI（VirtualMachineInstance）CRD、virt-launcher Pod、virt-handler DaemonSet
   - 适用场景：遗留系统无法容器化、需要完整OS内核、Windows工作负载、VM与容器共存
   - 生产验证：字节跳动/腾讯/其他大厂生产环境验证、与Cilium网络集成、与Rook-Ceph存储集成
   - 与容器对比：启动速度（VM分钟级 vs 容器秒级）、资源密度（VM重 vs 容器轻）、隔离级别（VM硬件级 vs 容器进程级）
3. 附GPU虚拟化：NVIDIA MIG（Multi-Instance GPU，A100/H100硬件分区）/ vGPU（软件虚拟化、时间片调度）/ Kubevirt GPU直通（PCIe透传）。

将结果保存到 `output/virtualization_architecture.md` 或直接在对话中呈现。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 各L2摘要 > 虚拟化与私有云]
- [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 关键技术决策路径 > 企业数据中心/信创合规/混合云互联]
- [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 2025-2026年关键趋势 > 去VMware化加速/信创私有云规模化/机密计算进入主流/Kubevirt融合VM与容器]

### Step 3: 验证与交付

**Goal**: 确保虚拟化/私有云方案安全可落地、与知识库一致
**Completion criterion**: 已通过 checklist 逐项核对

1. 读取 `checklist/virtualization_private_cloud_workflow_checklist.md`。
2. 逐项核对输出是否覆盖要求的知识点。
3. 确认所有关键论断均能在知识库中找到支撑。
4. 如有遗漏或偏差，补充修正。
5. 向用户交付最终答案。

## Post-Workflow

1. 记录完成状态到 `task/current_task.md`。
2. 如需深入某一具体技术（如"Kolla-Ansible部署OpenStack高可用控制节点"、"深信服aCloud信创适配清单"），在当前 Agent 内继续追问并输出。