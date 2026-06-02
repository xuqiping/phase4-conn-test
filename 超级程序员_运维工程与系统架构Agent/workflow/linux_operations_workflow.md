# Linux运维 Workflow

## Purpose

为Linux服务器运维提供从系统选型部署、内核调优、文件系统规划、进程隔离、网络栈优化到自动化脚本开发和性能调优的完整执行路径。覆盖SRE/DevOps/平台工程师核心技能栈。

## Prerequisites

- 用户已明确服务器用途（Web/DB/Cache/MQ/K8s节点/AI推理/大数据）
- 用户已明确硬件规格（CPU核心数/内存大小/磁盘类型/网络带宽）
- 用户已明确发行版偏好（RHEL/Ubuntu/openEuler/国产化）

## Steps

### Step 1: 系统选型与基础部署

**Goal**: 完成Linux发行版选型、内核配置、文件系统规划和基础服务初始化。
**Completion criterion**: 系统部署完成，内核版本确认，文件系统挂载，基础监控agent运行，ssh安全配置完成。

**A. 发行版与内核选型**
- 内核选型四必问：
  - LTS还是主线？生产环境优先LTS（Kernel 6.12 LTS或发行版自带）
  - 发行版还是自编译？通用场景用发行版包，云厂商定制内核用自编译
  - 国产化压力？openEuler 24.03 LTS（华为/鲲鹏/昇腾适配）/ 银河麒麟 / 龙蜥
  - 硬件适配？ARM/RISC-V/LoongArch需确认内核架构支持
- 发行版矩阵：
  - RHEL 10 / Rocky Linux 10 / AlmaLinux 10：企业级，长期支持，SELinux默认 enforcing
  - Ubuntu 26.04 LTS：云原生友好，Snap生态，社区活跃
  - openEuler 24.03 LTS：国产化首选，全场景支持，EulerBuilder自定义

**B. 内核调优5步法**
- 基线采集：`vmstat 1 10` / `mpstat -P ALL 1` / `iostat -x 1 10` / `perf stat -a sleep 10`
- 瓶颈定位：USE方法（Utilization/Saturation/Errors）
  - CPU：us/sy/id/wa/st列，us高→用户态计算，sy高→内核态开销，wa高→IO等待
  - 内存：free/buff/cache/available，si/so交换活动
  - 磁盘：await/svctm/%util，await>50ms说明磁盘饱和
  - 网络：rxkB/s/txkB/s/re-drop，drop>0说明网卡/队列溢出
- 假设验证：单点修改+A/B对照（修改前记录基准，修改后对比）
- 灰度推广：1台→10%→50%→100%，每阶段观测≥24小时
- 长效观测：Prometheus + node_exporter + ebpf-exporter持续采集

**C. 文件系统选型**
- 决策五因素：
  - 工作负载：OLTP→XFS / OLAP→ZFS / 通用→ext4 / 容器存储→XFS+io_uring
  - 可靠性需求：金融核心→ZFS CoW+校验 / 一般业务→XFS日志
  - 运维复杂度：ext4最简（无额外工具链）/ ZFS需学习池管理
  - 性能特性：NVMe+io_uring→XFS（大文件高吞吐）/ 小文件密集→ext4
  - 生态兼容：RHEL默认XFS / Ubuntu默认ext4
- 挂载选项：`noatime`（减少元数据写）/`nodiratime` / `nobarrier`（电池备份BBU时）
- LVM三层结构：PV（物理卷）→ VG（卷组）→ LV（逻辑卷），便于动态扩容
- RAID选型：数据库→RAID10（读写均衡）/ 冷数据→RAID6（容量优先）

**D. systemd服务管理**
- Unit模板配置：`Type=notify` + `Restart=on-failure` + `cgroup v2资源限制` + `安全沙箱`（PrivateTmp=yes, NoNewPrivileges=yes）
- 启动优化：`systemd-analyze blame`定位慢启动服务，`systemd-analyze critical-chain`分析依赖链
- Timer替代cron：更精确的时间表达式，日志自动归集到journald
- journald配置：持久化存储（Storage=persistent）、压缩（Compress=yes）、大小限制（SystemMaxUse=500M）

**E. SSH安全加固**
- 禁用root登录（PermitRootLogin=no）
- 仅密钥认证（PasswordAuthentication=no）
- 限制用户（AllowUsers/AllowGroups）
- 修改默认端口（Port 2222等非标准端口）
- 使用fail2ban自动封禁暴力破解IP
- auditd审计：记录所有特权命令执行

**输出物**: `系统部署文档_主机名.md`（含发行版/内核版本、分区方案、挂载选项、systemd服务清单、SSH配置、audit规则）。

**[参考: Agents知识库/0_超级编程行业知识库/运维工程与系统架构/Linux运维.md > Linux系统精通]`

---

### Step 2: 资源隔离与容器化运维

**Goal**: 建立进程资源隔离体系，完成容器运行时配置和K8s节点优化。
**Completion criterion**: cgroup v2三层slice模型运行，容器运行时配置完成，K8s节点QoS分级生效，PSI监控正常。

**A. cgroup v2三层slice模型**
- user.slice：用户会话进程，默认资源分配
- system.slice：系统服务（systemd管理），按服务分级资源
- machine.slice：容器/虚拟机，独立资源配额
- 资源分配4维矩阵：
  - 硬限/软限 × 独占/共享
  - 金融核心：硬限+独占（cpu.max/memory.max/io.max严格限制）
  - 多租户SaaS：软限+共享（cpu.weight/memory.low弹性分配）
  - AI训练：GPU+内存硬限，CPU权重动态调整
  - Web集群：均衡权重，突发允许
- 工具：`systemd-cgls`查看层级 / `cgget`获取配额 / `systemctl set-property`动态调整

**B. 进程隔离与QoS**
- K8s QoS三级映射cgroup v2：
  - Guaranteed：requests=limits，最高优先级，OOM最后kill
  - Burstable：requests<limits，中等优先级
  - BestEffort：无requests/limits，最低优先级，OOM最先kill
- PSI（Pressure Stall Information）精准识别瓶颈：
  - `some`：部分任务因资源不足停滞
  - `full`：全部任务因资源不足停滞
  - 阈值：cpu.pressure>80%→扩容 / memory.pressure>60%→排查泄漏 / io.pressure>70%→优化IO
- oomd（Facebook开源）基于PSI替代OOM Killer，误杀率降90%

**C. 容器运行时配置**
- containerd配置：
  - snapshotter：overlayfs（通用）/ zfs（ZFS后端）/ stargz（延迟拉取）
  - 镜像加速： Harbor私有仓库 + Dragonfly P2P分发
  - GPU支持：NVIDIA Container Toolkit / AMD ROCm
- 安全沙箱：
  - seccomp：限制系统调用，默认docker-default profile
  - AppArmor/SELinux：强制访问控制
  -  capabilities：最小权限（drop ALL，按需add）
  - 无根容器（rootless）：UID/GID映射，降低攻击面

**D. K8s节点优化**
- kubelet配置：
  - 预留资源：--kube-reserved（kubelet/systemd用） / --system-reserved（OS用） / --eviction-hard（驱逐阈值）
  - CPU Manager：static策略绑核（Guaranteed Pod独享核心）
  - Topology Manager：single-numa-node策略，NUMA亲和
- Cilium替代kube-proxy：eBPF实现Service负载均衡，性能提升40%+，支持1万节点集群
- 网络策略：Cilium NetworkPolicy（L3/L4/L7），零信任网络分段

**E. 安全加固**
- SELinux enforcing + auditd审计 + eBPF LSM运行时检测（Falco/Tetragon）
- Sigstore + SLSA供应链安全：镜像签名验证（cosign）、SBOM生成（syft）、SBOM签名（in-toto）
- 运行时安全：
  - Falco：规则引擎检测异常行为（如特权容器、敏感文件访问）
  - Tetragon：Cilium团队，eBPF LSM，细粒度进程/网络/文件事件
  - Tracee：Aqua Security，签名事件检测

**输出物**: `容器化运维配置_集群名.md`（含cgroup v2 slice配置、containerd配置、kubelet参数、Cilium策略、安全规则）。

**[参考: Agents知识库/0_超级编程行业知识库/运维工程与系统架构/Linux运维.md > Linux系统精通 > 进程隔离]`
**[参考: Agents知识库/0_超级编程行业知识库/运维工程与系统架构/Linux运维.md > 服务器性能调优]`

---

### Step 3: 自动化脚本与监控体系

**Goal**: 建立自动化运维脚本体系和可观测性监控平台，实现故障自愈和性能基线管理。
**Completion criterion**: 自动化脚本仓库运行，监控告警全覆盖，SLO定义完成，日志体系结构化。

**A. Shell工程化脚本**
- 六原则：
  1. 严格模式：`set -euo pipefail`（出错即退出、未定义变量报错、管道失败传递）
  2. 模块化函数：单一职责+local限域，函数长度<50行
  3. 配置外部化：.env/YAML文件存储可变配置，代码只读
  4. 防御式编程：`"${var}"`包裹变量、输入校验、路径存在检查
  5. 幂等性：check后act，重复执行结果一致
  6. 结构化日志：五级（DEBUG/INFO/WARN/ERROR/FATAL），JSON格式，包含timestamp/level/message/fields
- 安全：`flock`防并发、密钥存Vault运行时获取、cron用绝对路径+source .env
- 工具链：shellcheck静态检查、bats-core单元测试

**B. Python运维分层架构**
- CLI层：click/typer构建命令行接口
- 业务逻辑层：核心业务封装
- 数据访问层：SSH（paramiko/asyncssh）、API（httpx/aiohttp）、DB（asyncpg/aiomysql）
- 通知层：企业微信/钉钉/邮件/短信
- 质量保障：pydantic校验 + structlog日志 + mypy类型检查 + pytest测试
- asyncio异步并发：asyncio+asyncssh并发SSH连接300+台服务器，全量巡检从15分钟降至20秒

**C. 批量部署与GitOps**
- Ansible（Agentless+幂等）：Playbook角色化、变量分层（group_vars/host_vars）、Vault加密敏感数据
- GitOps（ArgoCD为云原生标准）：Git仓库为唯一真相源，ArgoCD自动同步，支持蓝绿/金丝雀/回滚
- 配置分层：默认→环境（dev/staging/prod）→主机→运行时，越上层优先级越高
- K8s Operator：Python+kopf开发自定义Operator，实现Pod自愈、智能HPA、ConfigMap热更新

**D. 监控与可观测性体系**
- 三套方法论融合：
  - USE（Utilization/Saturation/Errors）：排查基础设施瓶颈
  - RED（Rate/Errors/Duration）：监控微服务健康
  - Golden Signals（Latency/Traffic/Errors/Saturation）：业务SLO汇报
- 监控分层模型：
  - 基础设施层：Prometheus + node_exporter + ebpf-exporter（CPU/内存/磁盘/网络）
  - 中间件层：Kafka Exporter / Redis Exporter / PostgreSQL Exporter
  - 应用层：OpenTelemetry SDK自动埋点（Metrics/Logs/Traces）
  - 业务层：自定义业务指标（订单量/支付成功率/用户活跃度）
- 告警规则：
  - P0（立即处理）：服务完全不可用、错误率>5%、P99>3倍基线
  - P1（1小时内）：节点离线、磁盘>85%、内存>90%
  - P2（4小时内）：证书30天内过期、备份失败、非核心服务异常
- 可视化：Grafana仪表盘，按层级/团队/业务线组织

**E. 日志与追踪**
- 结构化日志：JSON/OTLP格式，字段包含timestamp/level/service/trace_id/span_id/message
- 采集端轻量：Promtail / Fluent Bit / Vector
- 服务端智能：Loki（轻量日志存储）/ Elasticsearch（全文检索）/ ClickHouse（OLAP分析）
- 分级采样：DEBUG 7天 / INFO 30天 / WARN+ERROR 1年
- trace_id全链路透传：OpenTelemetry Context传播，跨服务调用链追踪
- 冷热分层：热数据SSD（7天）→温数据SATA（30天）→冷数据对象存储（1年+）

**输出物**: `自动化运维仓库_项目名/`（含Shell/Python脚本、Ansible Playbook、监控配置、Grafana Dashboard JSON、告警规则YAML）。

**[参考: Agents知识库/0_超级编程行业知识库/运维工程与系统架构/Linux运维.md > Shell/Python运维脚本]`
**[参考: Agents知识库/0_超级编程行业知识库/运维工程与系统架构/Linux运维.md > 服务器性能调优 > 三大方法融合]`

---

### Step 4: 性能调优与容量管理

**Goal**: 完成四大子系统（CPU/内存/磁盘/网络）调优，建立容量评估模型和端到端高并发优化。
**Completion criterion**: 性能基线达标（P99延迟/吞吐量/错误率），容量规划报告产出，高并发场景验证通过。

**A. CPU调优**
- 调度器：Kernel 7.0 EEVDF替代CFS，更公平更响应；sched_ext自定义调度策略（Meta/字节已生产部署）
- 绑核：`taskset`（进程级）/ `numactl`（NUMA级），数据库/低延迟交易必须NUMA绑定
- 核隔离：`isolcpus` + `rcu_nocbs` + `nohz_full`， dedicates核心给关键进程
- 节能策略：`performance` governor（性能敏感）/ `powersave`（节能场景）
- SMT决策：CPU bound（计算密集型）关HT（超线程），IO bound（IO密集型）开HT
- 工具：`perf`火焰图定位热点函数 / `turbostat`查看CPU频率和C-state

**B. 内存调优**
- swappiness：数据库1-10（减少交换）、Redis 0（禁用交换）、通用30
- dirty_ratio/dirty_background_ratio：高IO场景设5/3（尽早刷盘）、通用20/10
- min_free_kbytes：128GB机器设2-4GB，确保紧急分配有内存
- THP（透明大页）：延迟敏感型关闭（`never`），吞吐型`madvise`
- HugePages：Oracle/PG/JVM/DPDK推荐，减少TLB miss
- 工具：`vmstat` / `free` / `numastat` / `slabtop` / `RAMMap`（Windows）

**C. 磁盘IO调优**
- NVMe scheduler=none（NVMe不需要调度器）+ `nr_requests=1024`（增加队列深度）
- io_uring替代libaio：PG17/Redis8+/Nginx1.27+已支持，延迟降40%+
- XFS挂载：`noatime` + `nobarrier`（BBU时） + `logbufs=8` + `logbsize=256k`
- SSD维护：`fstrim`每周运行（ discard/trim ）
- 工具：`fio`基准测试 / `iostat -x`实时监控 / `blktrace`深度分析

**D. 网络栈调优**
- TCP关键参数：
  - `net.ipv4.tcp_congestion_control=bbr`（BBR v3适合WAN，LAN用cubic）
  - `net.core.somaxconn=65535`（高并发连接队列）
  - `net.ipv4.tcp_tw_reuse=1`（TIME_WAIT复用）
  - `net.core.rmem_max/wmem_max=16MB`（socket缓冲区）
- 多队列：`ethtool -L eth0 combined 16`（RSS多队列）+ RPS/RSS（软中断均衡）
- 硬件卸载：GRO/GSO/TSO offloading
- 极致场景：DPDK（用户态网络）/ XDP（eBPF驱动层包处理）/ RDMA（零拷贝远程内存访问）
- 工具：`iperf3`测带宽 / `ethtool`看网卡统计 / `tcpdump`抓包分析

**E. eBPF零侵入分析**
- 学习路径：bcc（入门）→ bpftrace（ad hoc诊断）→ libbpf+CO-RE（生产级）→ sched_ext（调度定制）
- 工具：
  - bpftrace单行脚本：`bpftrace -e 'kprobe:do_sys_open { printf("%s opened %s\n", comm, str(arg1)); }'`
  - Pixie：零侵入APM，自动采集HTTP/gRPC/Redis/MySQL指标
  - Parca：持续Profiling，7×24h CPU和内存剖析
  - biolatency：磁盘IO延迟分布直方图
  - tcpconnect：实时显示新建TCP连接
- 开销：eBPF程序<1% CPU，ring buffer高效传递数据

**F. 端到端高并发优化**
- Web层：Nginx `reuseport`（多进程监听同端口）+ HTTP3/QUIC + `worker_processes=auto` + `worker_connections=65535`
- DB层：buffer pool=70%内存 + `io_uring` + 连接池（PGBouncer/ProxySQL）
- 缓存层：Redis `allkeys-lru` + 关THP + `io-threads`多线程IO
- MQ层：Kafka batch 64KB-1MB + lz4压缩 + `io.threads` = CPU核心数
- 四大原则：批量化（减少单次开销）、异步化（解耦和削峰）、本地化（NUMA亲和减少跨节点访问）、缓存化（读写分离，热点前置）

**G. 容量规划**
- USE+RED+Golden Signals统一模型构建容量基线
- 预测：基于历史数据（Prometheus）趋势分析，提前6周扩容
- 成本控制：按需扩容（云）/ 预留实例（长期稳定负载）/ Spot实例（容错批处理）
- 案例对标：某电商双11，提前6周扩容5000台ECS，峰值错误率<0.001%，P99<150ms，成本降18%

**输出物**: `性能调优报告_主机名.md`（含基线数据、调优参数、验证结果、容量规划、成本分析）。

**[参考: Agents知识库/0_超级编程行业知识库/运维工程与系统架构/Linux运维.md > 服务器性能调优]`
**[参考: Agents知识库/0_超级编程行业知识库/运维工程与系统架构/Linux运维.md > 本领域知识速查表]`

---

## Post-Workflow

1. Read `checklist/linux_operations_workflow_checklist.md`.
2. Cross-validate every output against every checklist item.
3. Only proceed to the next workflow or notify the user of completion after all checklist items pass.
