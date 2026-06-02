# AGENTS.md — Task Routing Table

## Agent: 超级程序员_运维工程与系统架构Agent

本文件是任务路由的唯一真相来源。当用户向本子Agent提出请求时，读取此表以确定加载哪个细粒度工作流。

## Routing Table

| 任务关键词 / 意图 | 工作流文件 | 描述 |
|------------------|-----------|------|
| Linux运维,SRE,DevOps,内核调优,Kernel,systemd,文件系统,XFS,ext4,ZFS,cgroup,namespace,容器运行时,进程管理,内存调优,磁盘IO,NVMe,io_uring,网络栈,eBPF,XDP,BBR,QUIC,Shell脚本,Python运维,Ansible,Prometheus,监控,可观测性,OpenTelemetry,性能调优,USE,RED,Golden Signals,OODA,psutil,paramiko,asyncio,k8s运维,节点管理,Pod调度,Cilium,零信任,Falco,Tetragon,SELinux,安全加固 | workflow/linux_operations_workflow.md | Linux运维全栈：内核调优5步法/EEVDF+sched_ext/io_uring/cgroup v2三层slice/文件系统选型/网络栈XDP+eBPF+BBR v3/Shell/Python工程化脚本/性能调优OODA循环/USE+RED+Golden Signals监控/容器与K8s运维 |
| Windows运维,C盘清理,系统优化,BitLocker,Defender,补丁管理,WSUS,Intune,启动优化,服务精简,内存管理,Pagefile,磁盘IO,ReFS,NTFS,网络栈,SMB,QUIC,DoH,电源管理,域控,AD,Active Directory,Entra ID,Azure AD,组策略,GPO,RODC,FSMO,Kerberos,LDAP,信创,统信,UOS,宁盾,零信任,MFA,PIM,Autopilot,Endpoint Manager,SCCM,镜像部署,补丁管理 | workflow/windows_operations_workflow.md | Windows系统运维：C盘清理四步法/终端安全五层防线/启动优化(<30秒)/服务精简(180→110)/内存+磁盘+网络+电源六大子系统调优/AD域控四大支柱+FSMO+Tier Model/Entra ID混合云/Intune现代化管理/信创替换三段式 |
| 机房,数据中心,组网,网络架构,SD-WAN,MPLS,VPN,IPSec,SSL VPN,SASE,ZTNA,零信任,专线,广域网,局域网,三层架构,Leaf-Spine,VLAN,M-LAG,vPC,OSPF,BGP,Wi-Fi 7,SD-LAN,EVPN,VXLAN,RDMA,机柜,UPS,PDU,液冷,风冷,PUE,配电,布线,TIA-606,接地,防雷,DCIM,容量规划,收敛比 | workflow/datacenter_networking_workflow.md | 机房与组网架构：SD-WAN全面替代MPLS(年省40-60%)/SASE融合/ZTNA五要素/三层架构演进Leaf-Spine/M-LAG+vPC/Wi-Fi 7/SD-LAN/零信任NAC/服务器机柜选型+冷热通道+液冷(<8kW风冷→>80kW浸没)/配电三级+布线TIA-606-C |

## Notes

- 本子Agent处理所有与Linux运维、Windows运维、域控管理、数据中心、网络架构、SD-WAN、机房部署相关的请求。
- 关键词覆盖范围要足够广以捕获同义/转述请求。
- 新增能力时在此表添加新行，并创建对应 workflow + checklist 文件对。

## Cross-Module Dependencies

- `linux_operations_workflow.md` Step 2 中的K8s运维可能引用 [参考: Agents知识库/0_超级编程行业知识库/04_云计算与云原生.md > 容器技术]（K8s/容器编排/Cilium/Service Mesh）
- `linux_operations_workflow.md` Step 3 中的监控体系可能引用 [参考: Agents知识库/0_超级编程行业知识库/02_后端架构与中间件.md > 服务治理]（RED指标/限流熔断/全链路追踪）
- `windows_operations_workflow.md` Step 3 中的Entra ID和Intune可能引用 [参考: Agents知识库/0_超级编程行业知识库/04_云计算与云原生.md > 云原生生态]（云身份/SSO/条件访问）
- `datacenter_networking_workflow.md` Step 1 中的SASE/ZTNA可能引用 [参考: Agents知识库/0_超级编程行业知识库/07_网络安全与信息安全.md > 网络基础安全]（零信任/SASE/边界防护）
- `datacenter_networking_workflow.md` Step 2 中的SD-LAN和Leaf-Spine可能引用 [参考: Agents知识库/0_超级编程行业知识库/02_后端架构与中间件.md > 微服务架构]（网络拓扑/服务发现/负载均衡）
