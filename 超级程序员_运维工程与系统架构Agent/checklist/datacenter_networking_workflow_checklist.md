# 机房与组网架构 Workflow Checklist

Use this checklist after completing every step of `workflow/datacenter_networking_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 广域网与SD-WAN架构

- [ ] 广域网选型矩阵覆盖五维：业务关键性/预算/地理/合规/运维
- [ ] SD-WAN四组件配置：控制器(HA+异地灾备)/Edge网关/Cloud Gateway/Analytics
- [ ] 分支ZTP上线测试通过（15分钟部署）
- [ ] 选路策略：DPI识别+SLA探测+权重计算+动态切换
- [ ] VPN技术选型匹配规模：IPSec(小规模)/DMVPN(大规模多分支)/WireGuard(云互联)
- [ ] SASE评估五维完成：网络/安全/集成/合规/5年TCO
- [ ] ZTNA五要素落地：身份/设备/应用/位置时间行为/数据敏感性
- [ ] 加密算法匹配场景：国际AES-256/国密SM4/后量子混合
- [ ] 跨国组网合规：数据出境评估提前6-12个月
- [ ] 控制器灾备：HA集群+异地灾备+灰度切换+回退方案
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位

## Step 2: 局域网与数据中心网络

- [ ] 局域网架构选型：三层/二合一/Leaf-Spine，匹配园区规模
- [ ] VLAN规划表完整：编号规范/业务隔离/<500终端/预留50%扩展
- [ ] VLAN安全：Native VLAN非1/Allowed list严格/802.1X动态分配
- [ ] M-LAG/vPC配置完成，链路利用率100%，切换<200ms
- [ ] 路由选型匹配规模：静态+VRRP/OSPF单区域/OSPF多区域+BGP/纯L3+EVPN
- [ ] OSPF安全：MD5/SHA256认证，area0连通，Stub area划分合理
- [ ] Wi-Fi 7部署：勘测完成/200-300m²每AP/6GHz优先/802.11kvr漫游
- [ ] 零信任准入：Radius双机/EAP-TLS/MAB/NAC+EDR+SIEM闭环
- [ ] 数据中心Leaf-Spine：收敛比1:1~2:1，RDMA无损三件套(PFC+ECN+ETS)
- [ ] SD-LAN Underlay+Overlay设计：纯L3+VXLAN+EVPN+SGT策略

## Step 3: 服务器机柜与数据中心部署

- [ ] 机柜选型三维度匹配：标准(19/21英寸)/深度/承重
- [ ] 散热方案决策匹配功率密度：<8kW风冷/8-25kW列间/25-80kW冷板液冷/>80kW浸没
- [ ] CFD气流仿真前置验证（ANSYS Icepak/6SigmaDCX）
- [ ] PUE目标设定：风冷1.4-1.6/冷板1.10-1.15/浸没1.05-1.10
- [ ] 配电三级架构：市电→UPS→PDU，A级2N冗余，UPS容量=负载×1.3
- [ ] U位规划：重型底部/轻型顶部/预留10U+/强弱电分离/盲板100%封堵
- [ ] 布线规范：填充率<60%/跳线选型匹配速率/标签TIA-606-C四级编码
- [ ] 接地防雷：总接地电阻<1ohm/SPD三级/防静电地板/湿度40-60%RH
- [ ] 物理安全：三重门禁3因子/机柜智能锁/4K视频180天/VESDA+气体灭火
- [ ] 液冷安全：漏液监测多级/应急止水阀/冷却液认证产品/风冷冗余保留
- [ ] DCIM平台部署：NetBox/Schneider/华为NetEco，AI故障预测+能耗优化
- [ ] 运维巡检：日检温湿度/周检PDU+UPS/月检电池+防雷/季检消防演练

## Overall

- [ ] All steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位。
