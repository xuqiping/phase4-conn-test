# Windows系统运维 Workflow Checklist

Use this checklist after completing every step of `workflow/windows_operations_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 终端健康与安全管理

- [ ] C盘空间治理四步法执行：诊断定位→系统级清理→用户级清理→持续维护
- [ ] Storage Sense GPO自动策略配置：临时7天/回收站14天/Downloads 30天
- [ ] 终端安全五层防线全部启用：Defender+BitLocker+补丁+备份+零信任
- [ ] BitLocker恢复密钥备份三处：MS账号+AD+纸质
- [ ] 补丁四环灰度：试点(7d)→第一环(14d)→快速环→广泛环
- [ ] 高危漏洞48小时紧急通道，关键系统禁自动重启
- [ ] 备份3-2-1法则执行：3份/2介质/1异地，恢复演练每半年1次
- [ ] 严禁360/腾讯电脑管家，无第三方安全软件冲突
- [ ] 终端健康检查报告：空间/安全/补丁/备份/风险清单完整

## Step 2: Windows底层性能优化

- [ ] 启动时间<30秒，WPR/WPA分析定位瓶颈已解决
- [ ] 服务精简至≤110个（Win11默认180个），决策矩阵记录
- [ ] BITS未关闭（Update依赖），Defender仅在企业EDR替代下关闭
- [ ] 内存管理优化：Pagefile配置/RAMMap/VMMap分析完成
- [ ] 磁盘IO优化：NTFS/ReFS选型/NVMe队列深度/BitLocker损耗<3%
- [ ] 网络栈优化：SMB 3.1.1加密/SMBv1禁用/QUIC/DoH
- [ ] 电源管理方案匹配场景：工作站卓越性能/笔记本平衡
- [ ] 注册表修改前已`reg export`备份
- [ ] PowerShell DSC+GPO批量管理配置，非手动修改

## Step 3: 域控与企业桌面管理

- [ ] AD架构设计完整：四大支柱/FSMO五大角色/Tier Model三层防横向移动
- [ ] AD红线遵守：DC禁非必要软件/禁互联网/禁域管日常运维/禁RC4/禁LM Hash
- [ ] 多站点部署：Site设计/RODC分支/KCC复制/DNS Netmask Ordering
- [ ] GPO治理：<500个GPO，命名规范，定期归档废弃策略
- [ ] Entra Connect Sync版本≥v2.5.79.0（2026年9月前升级）
- [ ] 条件访问策略≤50个，六维度（用户/应用/设备/位置/客户端/风险）
- [ ] PIM特权治理：JIT激活+MFA+审批，默认8小时
- [ ] Intune策略下发：Autopilot v2/Settings Catalog/EPM/Co-Management
- [ ] 零信任落地路线4年规划：第1年MFA→第2年PAM→第3年ZTNA→第4年SASE
- [ ] 信创替换（如适用）：三段式（并行→灰度→收敛），每阶段有回滚方案
- [ ] 架构选型匹配规模：<200人全云/200-2000混合/2000+多林多域

## Overall

- [ ] All steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位。
