# Windows系统运维 Workflow

## Purpose

为企业Windows终端和服务器提供从C盘清理、系统安全加固、底层性能调优到AD域控管理、Entra ID云身份和信创替换的完整执行路径。覆盖单机健康→系统性能→组织管控三级运维体系。

## Prerequisites

- 用户已明确规模（单台/数十台/数千台/数万台终端）
- 用户已明确Windows版本（Win10/Win11/Server 2022/Server 2025）
- 用户已明确管理工具（SCCM/Intune/AD/组策略/第三方MDM）

## Steps

### Step 1: 终端健康与安全管理

**Goal**: 完成C盘空间治理、终端安全五层防线部署、补丁管理和备份策略。
**Completion criterion**: 终端可用空间达标（≥30%剩余）、Defender运行正常、BitLocker加密启用、补丁更新≤14天、备份策略执行通过恢复演练。

**A. C盘空间治理四步法**
- 诊断定位：WizTree直读NTFS MFT（1TB扫描<10秒）或TreeSize按大小排序识别TOP占用
- 系统级清理：
  - `powercfg /h off` 关闭休眠（释放hiberfil.sys，通常数GB）
  - `DISM /Online /Cleanup-Image /StartComponentCleanup` 清理WinSxS组件存储
  - `cleanmgr` 清理Windows.old、临时文件、回收站
  - 关闭Reserved Storage（ReservedStorageState=0）
- 用户级清理：
  - AppData三大子目录（Local/LocalLow/Roaming）清理应用缓存
  - 浏览器/IDE/IM缓存清理
  - `%temp%` / Prefetch / SoftwareDistribution清理
- 持续维护：
  - Storage Sense GPO自动策略：临时文件7天/回收站14天/Downloads 30天自动清理
  - PowerShell计划任务定期执行清理脚本
  - 案例：某互联网公司5000终端，平均可用空间从8GB→65GB，C盘满工单从30+/月降至2/月

**B. 终端安全五层防线**
- 层1 杀毒：Microsoft Defender + Tamper Protection（2026年AV-Test与卡巴斯基持平，零成本足够）
- 层2 加密：BitLocker XTS-AES-256 + TPM 2.0，Win11 OOBE自动开启，恢复密钥备份至MS账号+AD+纸质三处
- 层3 补丁：Intune Update Rings / WSUS / Autopatch，Patch Tuesday后14天内完成，高危48小时紧急通道
- 层4 备份：3-2-1法则（3份/2介质/1异地），OneDrive + Macrium Reflect系统映像 + Azure Backup
- 层5 零信任：Controlled Folder Access + ASR规则（防勒索最后一道防线），企业用MDE Plan 2 + Sentinel SIEM
- **严禁360/腾讯电脑管家**：与Defender冲突、引入广告、增加攻击面

**C. 补丁管理**
- 四环灰度：试点环（7天）→ 第一环（14天）→ 快速环 → 广泛环
- WSUS（进入维护模式）→ 迁移到WUfB / Intune Update Rings / Autopatch
- 高危漏洞48小时紧急通道，关键系统禁自动重启（业务时间外维护窗口）
- 补丁合规报告：每月导出未打补丁终端清单，追踪到责任人

**D. 备份与恢复**
- 系统映像：Macrium Reflect / Veeam Agent，每月完整备份+每日增量
- 文件同步：OneDrive/SharePoint已知文件夹重定向（桌面/文档/图片）
- 恢复演练：每半年至少1次完整恢复演练，验证备份可用性
- 风险：BitLocker恢复密钥丢失致数据永久不可读（致命级，必须三处备份）

**输出物**: `终端健康检查报告_组织名.md`（含空间分析、安全状态、补丁合规率、备份状态、风险清单）。

**[参考: Agents知识库/0_超级编程行业知识库/运维工程与系统架构/Windows系统运维.md > C盘清理与系统安全]`

---

### Step 2: Windows底层性能优化

**Goal**: 完成启动加速、服务精简、内存管理、磁盘IO、网络栈和电源管理六大子系统调优。
**Completion criterion**: 启动时间<30秒，服务数精简至110个以内，内存管理优化完成，磁盘IO基准测试达标，网络吞吐达标。

**A. 启动加速**
- 启动链分析：POST→Boot Manager→OS Loader→Kernel Init→Services→Winlogon→Shell
- 工具链：WPR抓ETL trace → WPA分析 → Autoruns检查启动项
- 企业方案：MDT镜像优化（精简预装软件）+ GPO启动项管理 + SSD升级 + Fast Startup
- 关键指标：从开机到可用<30秒
- 案例：某金融企业5000终端，WPR定位GPO处理60秒+自启动项200+→精简至30个核心→启动从180秒降至25秒

**B. 服务与进程精简**
- Win11 25H2默认约180个服务，可精简至110个
- 决策矩阵：必备（不可关）/ 安全（慎重关）/ 可选（按业务关）/ 冗余（可关）
- **BITS必须开**（Windows Update依赖），**Defender仅在企业EDR替代下关**
- 批量管理：PowerShell DSC + Group Policy + Intune Settings Catalog
- 工具：`Get-Service` / `Set-Service` / `Autoruns`（Sysinternals）

**C. 内存管理**
- 核心计数器：Available MB、Pages/sec、% Committed Bytes In Use
- 工具：RAMMap（物理内存分布）、VMMap（进程内存）
- Pagefile建议：小内存动态、大内存固定=物理内存×1.0、SQL Server必开Lock Pages in Memory + Large Pages
- 诊断：`resmon`实时查看内存使用，`perfmon`长期采集基线

**D. 磁盘IO优化**
- 文件系统选型：系统盘NTFS（成熟）/ 数据盘ReFS（自我修复）
- NVMe Gen 5普及（14000MB/s），IO队列深度从默认32调到128+
- DirectStorage 1.2：绕过CPU直接加载到GPU（游戏/AI场景）
- BitLocker在硬件加密SSD上损耗<3%
- 工具：`diskspd`基准测试 / `perfmon`磁盘计数器

**E. 网络栈优化**
- SMB 3.1.1：默认AES-256-GCM加密+强制签名
- QUIC/HTTP3：Edge浏览器默认开启
- DoH（DNS over HTTPS）：默认开启，防止DNS劫持
- **强制禁用SMBv1**：防勒索底线
- 调优：`netsh int tcp set global autotuninglevel=normal`

**F. 电源管理**
- 四方案：节能→平衡→高性能→卓越性能
- 卓越性能方案GUID：`e9a42b02-d5df-448d-aa00-03f14749eb61`（`powercfg -duplicatescheme`激活）
- AI/游戏工作站：卓越性能+CPU最小频率100%
- 企业笔记本：GPO默认平衡+Modern Standby
- 诊断：`powercfg /energy`能效报告、`/SLEEPSTUDY`睡眠分析

**G. 注册表与GPO批量调优**
- PowerShell DSC + GPO批量管理，取代手动regedit
- 关键分类：性能（20+项）、安全（40+项）、隐私（15+项）、UI体验（30+项）
- **修改前必`reg export`备份**
- 性能分析工具链：`Task Manager`→`Resource Monitor`→`PerfMon`→`WPR/WPA`（深度）

**输出物**: `Windows优化配置包_组织名.zip`（含PowerShell DSC脚本、GPO模板、注册表备份、性能基线报告）。

**[参考: Agents知识库/0_超级编程行业知识库/运维工程与系统架构/Windows系统运维.md > Windows底层优化]`

---

### Step 3: 域控与企业桌面管理

**Goal**: 完成AD域控架构设计、Entra ID混合云配置、Intune现代化管理和信创替换规划。
**Completion criterion**: AD架构评审通过，Entra Connect同步运行，Intune策略下发成功，信创替换方案（如适用）经管理层确认。

**A. AD域控核心架构**
- 四大支柱：AD DS（目录服务）/ AD CS（证书服务）/ AD FS（联合身份）/ AD RMS（权限管理）
- FSMO五大角色分布策略：
  - Schema Master + Domain Naming：全林唯一，放PDC所在DC
  - RID + PDC Emulator + Infrastructure：每域唯一，分布在不同DC
- 三层Tier Model防横向移动：
  - Tier 0（DC/PKI/KMS核心）：最高防护，独立管理账号
  - Tier 1（应用服务器/数据库）：中等防护
  - Tier 2（用户工作站）：基础防护，禁止跨层使用账号
- AD红线：DC禁装非必要软件、禁上互联网、禁用域管账号日常运维、禁用RC4加密、禁启用LM Hash

**B. 多站点部署**
- Site = 物理位置 + 高速网络，站点间走专线/VPN
- RODC（只读域控）：分支部署实现本地缓存认证，总部DC宕机仍可登录
- Site Link设置Cost与Schedule，KCC自动计算复制拓扑
- DNS Netmask Ordering确保客户端自动找最近DC
- Tombstone Lifetime 180天，DC离线超时不能重入复制

**C. GPO体系治理**
- 处理顺序：L-S-D-OU（Local→Site→Domain→OU）
- 设计原则：少而精（同类策略合并为1个GPO），命名规范`{域}_{类别}_{对象}_{编号}`
- >500个GPO导致登录慢，定期用`Get-GPO -All`识别废弃策略合并归档
- 迁移趋势：新需求走Intune Settings Catalog（5000+策略项），存量GPO逐步退役

**D. Entra ID混合云身份**
- 最紧迫任务：Entra Connect Sync v2.5.79.0需在2026年9月前升级
- 条件访问六维度：用户/组 × 应用 × 设备 × 位置 × 客户端 × 登录风险，策略控制在50个以内
- PIM特权治理：管理员角色JIT激活，MFA+审批，默认8小时有效
- ADFS退役：迁移到Entra ID直接联邦，老旧SAML应用用企业应用模板对接
- Kerberos安全升级：Server 2025默认禁用RC4，要求AES128/256，堵死Kerberoasting攻击
- dMSA替代gMSA：实现自动密码轮换

**E. Intune现代化桌面管理**
- Autopilot v2：零接触部署，开机30分钟完成全套合规策略下发
- Settings Catalog：5000+策略项替代旧Administrative Templates
- EPM（端点特权管理）：标准用户按需提权，替代本地管理员滥用
- Co-Management：SCCM + Intune工作负载逐个滑块迁移，24个月完成过渡
- 镜像策略：Thin Image + 应用层叠加（灵活）优于 Thick Image（笨重）
- Image as Code：镜像构建脚本化、Git版本管理、可重现可回滚

**F. 零信任与SASE**
- 四原则落地路线（4年规划）：
  - 第1年：MFA全员 + 关键应用SSO
  - 第2年：PAM + AD安全监控
  - 第3年：ZTNA替代VPN
  - 第4年：完整SASE（SWG/ZTNA/CASB/FWaaS/DLP）
- ZTNA产品：Cloudflare Access / Tailscale / 深信服aTrust

**G. 信创替换（党政央国企）**
- 三条路线并行：本地AD域控（存量）、Entra ID云身份（新建）、国产信创域管（党政央国企）
- 国产域管：统信集中域管（UOS主战场）、宁盾NDS（多平台并行）、联软XCAD（无感平滑过渡）
- 三段式过渡：**并行**（双向同步3月）→ **灰度**（按月切换20%，6月）→ **收敛**（AD转只读后下线，3月）
- **每个阶段必准备回滚方案**
- 案例：某省级税务局1.8万人AD信创替换，成为全国首批完成AD替换的省级税务单位

**H. 架构选型矩阵**
| 企业规模 | 推荐方案 | 关键组件 |
|---|---|---|
| <200人 | Entra ID + Intune全云 | M365 Business Premium，零本地DC |
| 200-2000人 | 混合架构 | 1-2台本地DC + Entra Connect + Intune |
| 2000+人 | 多林多域+区域DC+RODC | SCCM + Intune Co-Management |
| 党政央国企 | 信创替换 | 统信/宁盾 + 国密SM2/SM3/SM4 |

**输出物**: `域控架构设计文档_组织名.md`（含AD拓扑图、FSMO分布、GPO清单、Entra配置、Intune策略、信创替换计划）。

**[参考: Agents知识库/0_超级编程行业知识库/运维工程与系统架构/Windows系统运维.md > 域控与企业桌面运维]`
**[参考: Agents知识库/0_超级编程行业知识库/运维工程与系统架构/Windows系统运维.md > 架构选型决策矩阵]`

---

## Post-Workflow

1. Read `checklist/windows_operations_workflow_checklist.md`.
2. Cross-validate every output against every checklist item.
3. Only proceed to the next workflow or notify the user of completion after all checklist items pass.
