# Linux运维 Workflow Checklist

Use this checklist after completing every step of `workflow/linux_operations_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 系统选型与基础部署

- [ ] 发行版选型有明确理由：RHEL（企业级）/Ubuntu（云原生）/openEuler（国产化）
- [ ] 内核版本确认，LTS优先，硬件适配（ARM/RISC-V/LoongArch）已验证
- [ ] 文件系统选型遵循五因素决策框架（工作负载/可靠性/复杂度/性能/生态）
- [ ] 分区方案包含LVM三层结构（PV→VG→LV），RAID选型匹配场景
- [ ] 挂载选项优化：noatime/nodiratime已配置
- [ ] systemd服务Unit模板配置完整：Type=notify+Restart=on-failure+cgroup v2限制+安全沙箱
- [ ] SSH安全加固完成：禁用root+仅密钥+限制用户+改端口+fail2ban
- [ ] auditd审计规则配置，记录特权命令
- [ ] 基础监控agent（node_exporter/Prometheus）运行正常
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位

## Step 2: 资源隔离与容器化运维

- [ ] cgroup v2三层slice模型运行：user.slice/system.slice/machine.slice
- [ ] 资源分配4维矩阵覆盖场景：硬限/软限 × 独占/共享
- [ ] K8s QoS三级映射生效：Guaranteed/Burstable/BestEffort
- [ ] PSI监控配置：cpu.pressure/memory.pressure/io.pressure阈值告警
- [ ] containerd配置完成：snapshotter/镜像加速/GPU支持
- [ ] 容器安全沙箱：seccomp/AppArmor/capabilities/无根容器至少3项启用
- [ ] Cilium替代kube-proxy配置运行，支持NetworkPolicy
- [ ] kubelet预留资源配置：kube-reserved/system-reserved/eviction-hard
- [ ] 运行时安全：Falco/Tetragon/Tracee至少1项部署，规则运行正常
- [ ] SELinux enforcing + auditd + eBPF LSM检测运行

## Step 3: 自动化脚本与监控体系

- [ ] Shell脚本遵循六原则：严格模式/模块化/配置外部化/防御式/幂等/结构化日志
- [ ] Python运维分层架构完整：CLI→业务逻辑→数据访问→通知
- [ ] 质量保障：pydantic+structlog+mypy+pytest
- [ ] Ansible Playbook角色化，变量分层（group_vars/host_vars），Vault加密敏感数据
- [ ] GitOps（ArgoCD）部署配置运行，支持蓝绿/金丝雀/回滚
- [ ] 监控三套方法论融合：USE（基础设施）+ RED（微服务）+ Golden Signals（业务SLO）
- [ ] 监控分层四层覆盖：基础设施/中间件/应用/业务
- [ ] 告警分级：P0立即/P1 1h/P2 4h，规则已配置并测试
- [ ] 日志结构化：JSON/OTLP格式，分级采样（DEBUG 7d/INFO 30d/WARN+ERROR 1y）
- [ ] trace_id全链路透传，OpenTelemetry Context传播正常

## Step 4: 性能调优与容量管理

- [ ] CPU调优：调度器（EEVDF/sched_ext）/绑核/NUMA本地化/核隔离/SMT决策
- [ ] 内存调优：swappiness/dirty_ratio/min_free_kbytes/THP/HugePages配置完成
- [ ] 磁盘IO调优：NVMe scheduler=none/io_uring/XFS挂载选项/fstrim
- [ ] 网络栈调优：BBR v3/somaxconn=65535/tcp_tw_reuse/rmem+wmem=16MB
- [ ] eBPF工具链部署：bpftrace/Pixie/Parca至少1项运行，开销<1% CPU
- [ ] 端到端高并发优化：Web(Nginx+HTTP3)→DB(io_uring+连接池)→缓存(Redis LRU)→MQ(Kafka batch)
- [ ] 四大原则落地：批量化/异步化/本地化/缓存化
- [ ] 容量规划报告产出，基于历史数据趋势分析，提前6周扩容
- [ ] 成本优化：按需/预留/Spot实例策略匹配负载特征
- [ ] 性能基线达标，P99延迟/吞吐量/错误率满足SLA

## Overall

- [ ] All steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位。
