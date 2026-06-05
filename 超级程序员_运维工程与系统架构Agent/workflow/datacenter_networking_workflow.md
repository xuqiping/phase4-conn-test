# 机房与组网架构 Workflow

## Purpose

为企业数据中心和网络基础设施提供从广域网选型、SD-WAN部署、局域网架构设计到服务器机柜部署、液冷散热、配电布线和DCIM运维的完整执行路径。覆盖物理基础设施全链路标准。

## Prerequisites

- 用户已明确规模（单机房/多数据中心/多分支/跨国）
- 用户已明确预算等级（SMB/企业级/超大规模/AI智算）
- 用户已明确合规要求（等保/国密/ISO27001/TIA-942）

## Steps

### Step 1: 广域网与SD-WAN架构

**Goal**: 完成广域网技术选型、SD-WAN部署或SASE融合架构上线，实现分支到总部/云的安全高效互联。
**Completion criterion**: 广域网架构评审通过，SD-WAN/SASE部署完成，分支上线ZTP（零接触部署），链路切换测试通过。

**A. 广域网选型决策矩阵**
- 评估五维：业务关键性（A/B/C级）× 预算约束 × 地理范围 × 合规要求 × 运维能力
- 选型决策：
  - A级关键业务（银行核心/证券交易）→ MPLS/专线兜底 + SD-WAN备用
  - B级企业业务（办公/OA/ERP）→ SD-WAN主链路（多Internet+4G/5G备）
  - C级一般业务（访客/IoT）→ Internet + IPSec VPN
  - 跨国企业 → SASE+SD-WAN替代IEPL，全球POP接入
  - 信创/政企 → 华为/H3C国产方案 + 国密SM4加密

**B. SD-WAN架构四组件**
- 控制器：集中管理策略、监控、告警，必须HA集群+异地灾备
- Edge网关：分支CPE设备，支持ZTP（15分钟上线）
- Cloud Gateway：云端POP节点，优化云/SaaS访问路径
- Analytics：应用识别、链路质量、流量分析、合规报告
- 选路策略：DPI应用识别→SLA实时探测（延迟/丢包/抖动）→权重计算→阈值动态切换
- 案例：某全国连锁餐饮3000店，Fortinet Secure SD-WAN+SASE，年节省1600万（-42%），运维从25人降至8人

**C. VPN技术选型**
- IPSec VPN：强算法（AES-256-GCM+SHA-384）+ 证书认证 + 硬件加速
- DMVPN/ADVPN：动态多点隧道，大规模多分支首选，节省总部带宽60-80%
- WireGuard：轻量高性能，现代加密（Curve25519+ChaCha20+Poly1305），适合云-云互联
- 隧道MTU设1400避免分片，证书集中管理+自动续期

**D. SASE融合架构**
- 评估五维：网络能力（SD-WAN+全球POP）× 安全能力（SWG/ZTNA/CASB/FWaaS/DLP）× 集成能力 × 合规能力 × 5年TCO
- 产品：Cloudflare One / Zscaler / Palo Alto Prisma / 华为iMaster NCE
- 趋势：2026年60%新SD-WAN纳入SASE

**E. ZTNA零信任网络访问**
- 五要素：身份(who) + 设备(what) + 应用(where to) + 位置/时间/行为(when/where) + 数据敏感性(data class)
- 迁移路径：Stage1传统（边界防御+VPN）→ Stage2过渡（MFA+EDR+NAC）→ Stage3集成（ZTNA+SDP+UEBA）→ Stage4持续（AI驱动+自适应评分）
- 产品：Cloudflare Access / Tailscale / 深信服aTrust

**F. 加密算法与合规**
- 国际场景：AES-256-GCM + SHA-384 + ECDSA P-384
- 国密场景：SM4 + SM3 + SM2全栈（GB/T 39786-2021/GM/T 0023-0024）
- 后量子混合：ECDSA+ML-DSA + X25519+ML-KEM（NIST后量子标准）

**G. 跨国组网**
- 三层架构：Tier1全球骨干（IPLC/海底光缆）→ Tier2区域中心 → Tier3边缘加速（CDN）
- 合规优先：跨境项目必须法务+合规预审，数据出境评估提前6-12个月
- 关键风险：控制器必须HA+异地灾备；切换须灰度+并行+回退方案

**输出物**: `广域网架构设计文档_组织名.md`（含选型矩阵、SD-WAN拓扑、加密策略、合规方案、上线计划）。

**[参考: Agents知识库/0_超级编程行业知识库/运维工程与系统架构/机房与组网架构.md > 专线与VPN架构]`

---

### Step 2: 局域网与数据中心网络

**Goal**: 完成局域网架构设计（三层/Leaf-Spine/SD-LAN）、VLAN规划、高可用配置和无线覆盖。
**Completion criterion**: 网络拓扑设计评审通过，VLAN规划表产出，M-LAG/vPC配置完成，Wi-Fi覆盖勘测达标。

**A. 局域网架构演进**
- 经典三层：接入-汇聚-核心（适合传统园区）
- 中小园区：汇聚核心二合一（简化管理）
- 大型园区/数据中心：Leaf-Spine扁平架构（任意两节点只跨3跳）
- 容量规划：接入端口=终端×2（冗余）；汇聚带宽=接入×0.3；核心带宽=汇聚之和×0.5

**B. VLAN设计**
- 原则：业务隔离+安全合规+每VLAN<500终端+规范命名+预留50%扩展
- 编号规范：
  - VLAN1：保留不用
  - 10-99：办公
  - 100-199：服务器
  - 200-299：IoT/OT
  - 300-399：访客/Guest
  - 4000+：管理/Mgmt
- 安全：Trunk Native VLAN设非1的闲置VLAN；严格按需配置Allowed VLAN list
- 动态分配：802.1X+RADIUS实现基于身份动态VLAN分配

**C. 二层高可用**
- M-LAG/vPC替代STP成为标配：
  - 链路利用率从50%→100%
  - 故障切换<200ms
  - Peer Link用40G/100G + Keepalive走带外管理 + 双主检测防脑裂
- 厂商方案：Cisco vPC / 华为M-LAG / Aruba VSX

**D. 三层路由选型**
- 小型（<10台L3）：静态+VRRP
- 中型（10-100台）：OSPF单区域+VRRP
- 大型（>100台）：OSPF多区域+BGP核心
- 超大数据中心：纯L3+BGP EVPN
- 安全：所有OSPF邻接必须MD5/SHA256认证；area0必须连通

**E. SD-LAN软件定义园区网**
- 核心原则：Underlay求稳（纯L3+OSPF/ISIS）→ Overlay求活（VXLAN+EVPN）
- 策略模型：SGT（Security Group Tag）替代传统IP/VLAN ACL，基于角色做策略
- 统一管控：iMaster NCE-Campus / Cisco DNA Center / Aruba Central
- 案例：某互联网公司3000人新园区，华为CloudEngine+iMaster NCE-Campus，运维从8人降至3人，5年TCO降低42%

**F. Wi-Fi 7部署**
- 标准：IEEE 802.11be，最高46Gbps，延迟<5ms
- 规划方法论：先勘测后部署，开放办公每200-300m²一台AP
- 信道：2.4G只开1/6/11三信道，大场景建议关闭2.4G只开5G+6GHz
- 漫游：开启802.11k/v/r三剑客，漫游阈值-70dBm
- SSID控制：3-5个（每多1个广播开销+10%）
- 安全：WPA3-Enterprise + EAP-TLS证书认证

**G. 数据中心Leaf-Spine**
- 任意两节点只跨3跳
- 收敛比：1:1（无收敛，AI/HPC）/ 1.5:1（高质量）/ 2:1（经济型）
- RDMA无损三件套：PFC（Priority Flow Control）+ ECN（Explicit Congestion Notification）+ ETS（Enhanced Transmission Selection）
- 产品：Cisco Nexus / 华为CloudEngine / Arista

**H. 零信任准入**
- Radius双机+EAP-TLS证书认证
- 未认证设备分配Guest VLAN（限速+隔离）
- MAB（MAC Authentication Bypass）处理不支持802.1X的终端（打印机/摄像头/IoT）
- NAC+EDR+SIEM闭环联动：准入后持续评估设备健康度，异常自动隔离

**输出物**: `局域网设计文档_组织名.md`（含拓扑图、VLAN规划表、IP地址规划、路由策略、Wi-Fi勘测报告、设备清单）。

**[参考: Agents知识库/0_超级编程行业知识库/运维工程与系统架构/机房与组网架构.md > 局域网组网]`

---

### Step 3: 服务器机柜与数据中心部署

**Goal**: 完成机柜选型、散热设计、配电架构、布线规范和物理安全部署。
**Completion criterion**: 机柜部署方案评审通过，PUE目标设定，U位规划完成，布线标签规范，物理安全三重门禁配置完成。

**A. 机柜选型三维度**
- 标准：EIA-310 19英寸（通用）/ OCP V3 21英寸（AI集群，537mm宽）
- 深度：1100mm（通用）/ 1200mm（高密）/ 1300mm（液冷）
- 承重：1500kg（传统）/ 2000-3000kg（液冷AI）
- AI集群选OCP 21英寸+1200mm深+3000kg承重

**B. 散热方案决策**
- <8kW/柜：风冷（传统精密空调）
- 8-25kW/柜：列间空调（近端制冷）
- 25-80kW/柜：冷板液冷（CDU+冷板+快接头）
- >80kW/柜：浸没液冷（单相/两相，氟化液/油类）
- PUE目标：风冷1.4-1.6 / 冷板液冷1.10-1.15 / 浸没液冷1.05-1.10
- CFD气流仿真：ANSYS Icepak / 6SigmaDCX，前置验证散热设计
- 案例：阿里张北浸没液冷智算集群，1000台NVIDIA H100，PUE 1.08，年节电>400万度

**C. 配电三级架构**
- 市电→UPS→PDU→服务器
- A级机房：2N双路UPS+柴发（同时维护）
- B级机房：N+1 UPS
- C级机房：N UPS
- AI集群：OCP 48V DC集中供电节电5-8%
- 智能PDU：每路计量+远程开关+温湿度监测
- UPS容量 = 总负载 × 1.3倍

**D. U位规划**
- 原则：重型设备放底部（稳重心）/ 轻型放顶部（便维护）/ 每柜预留10U+空位
- 强弱电分离：左强电右弱电（或前弱电后强电）
- 空U位100%盲板封堵（防止热风短路）
- 建立"U位模板库"统一标准

**E. 布线规范**
- 走线槽填充率<60%
- 跳线选型：
  - 1G：超六类
  - 10G：七类或OM3多模光纤
  - 25-100G：OM4多模或OS2单模
  - >100G：OS2单模，400G/800G用MPO多芯连接器
- 标签遵循TIA-606-C四级编码：`机柜号-U位-端口-类型`
- 弯曲半径：单模光纤>30mm，铜缆>4倍线径

**F. 接地防雷**
- 共用接地系统等电位连接
- 总接地电阻<1ohm（A级机房）
- SPD三级分级：总配电（I类）→楼层（II类）→机柜（III类）
- 防静电地板+手腕带+湿度40-60%RH

**G. 物理安全**
- A级机房三重门禁：园区→建筑→机房，至少3因子认证
- 机柜智能门锁：蓝牙+RFID+审计日志
- AI视频监控：4K全覆盖，留存180天
- VESDA极早期火警+七氟丙烷气体灭火联动
- 漏水检测：地板下+空调周围+液冷管路

**H. 模块化与DCIM**
- 整机柜交付：出厂预装7天上线（vs 散柜30天），AI集群主流
- MDC微模块：16-30柜+独立UPS+空调组成弹性扩展单元
- DCIM平台：Schneider / 华为NetEco / 开源NetBox
  - AI故障预测+能耗优化
  - 巡检频率：日检温湿度/周检PDU+UPS/月检电池+防雷/季检消防演练
- CMDB：NetBox（开源）起步，记录设备全生命周期

**I. 容量规划**
- 选柜前必查土建承重报告，AI集群液冷柜需楼面加固
- 配电占CAPEX 25-35%
- 制冷占OPEX 30-40%（液冷可降至10-15%）

**输出物**: `数据中心部署手册_机房名.md`（含机柜布局图、散热CFD报告、配电单线图、布线路径图、U位分配表、物理安全方案、DCIM配置）。

**[参考: Agents知识库/0_超级编程行业知识库/运维工程与系统架构/机房与组网架构.md > 服务器机柜部署规范]`
**[参考: Agents知识库/0_超级编程行业知识库/运维工程与系统架构/机房与组网架构.md > 本领域知识速查表]`

---

## Post-Workflow

1. Read `checklist/datacenter_networking_workflow_checklist.md`.
2. Cross-validate every output against every checklist item.
3. Only proceed to the next workflow or notify the user of completion after all checklist items pass.
