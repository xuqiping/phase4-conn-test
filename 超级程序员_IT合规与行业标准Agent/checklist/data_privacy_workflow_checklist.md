# Data Privacy Workflow Checklist

Use this checklist after completing every step of `workflow/data_privacy_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 数据资产盘点与分类分级

- [ ] 全部数据存储位置已盘点（结构化/非结构化/日志/备份），覆盖率100%
- [ ] 数据资产已按敏感度分为公开/内部/机密/绝密四级
- [ ] 每类数据已定义差异化控制策略（高敏→字段级加密+严格审计）
- [ ] 《数据分类分级管理办法》已输出
- [ ] 《数据资产目录》（Excel/CMDB形式）已输出

## Step 2: 数据六阶段控制策略落地

- [ ] 采集阶段已建立必要性审查+告知同意+采集日志审计
- [ ] 传输阶段已部署TLS 1.3+证书固定+API网关流量加密
- [ ] 存储阶段已部署静态加密（TDE+SSE）+KMS/HSM密钥管理+备份加密
- [ ] 处理阶段已部署脱敏处理+MPC多方安全计算+访问审计
- [ ] 交换阶段已部署API网关沙箱+数据共享协议（DUA）+交换日志
- [ ] 销毁阶段已遵循NIST 800-88标准，物理销毁+逻辑销毁流程已定义
- [ ] 《数据全生命周期控制矩阵》（6阶段×技术×管理）已输出

## Step 3: 加密技术决策与密钥管理体系

- [ ] 加密技术决策树已应用（同态/MPC/字段级/TDE场景匹配正确）
- [ ] KMS（国密SM2/SM3/SM4）已部署，支持密钥自动生成+轮换+销毁
- [ ] 高敏感数据密钥采用HSM保护
- [ ] 密钥轮换周期已定义（建议90天自动轮换）
- [ ] 《密钥管理运维手册》已输出

## Step 4: DLP三道防线部署

- [ ] 终端DLP已部署（USB/剪贴板/屏幕/打印监控）
- [ ] 网络DLP已部署（邮件网关/SSL解密/IM外发审计）
- [ ] 云端DLP已部署（CASB监控SaaS应用/云存储异常下载告警）
- [ ] DLP已分阶段部署（监控→告警→阻断），误报率<5%
- [ ] DLP事件分级响应机制已建立
- [ ] 《DLP运营报告》模板已建立

## Step 5: 数据出境合规通道建设

- [ ] 全部数据出境场景已梳理（云服务/跨境办公/集团汇聚）
- [ ] 每个出境场景已匹配三条通道之一（安全评估/SCC/认证）
- [ ] 安全评估申报材料（申报书+自评估报告）已准备
- [ ] 标准合同（SCC）+ PIA评估已准备
- [ ] 出境审批流程已建立（业务→法务→安全→高管）

## Step 6: 数据泄露应急响应体系建设

- [ ] PICERL六阶段应急响应预案已制定
- [ ] 应急响应小组已组建（安全/法务/PR/业务/高管）
- [ ] 泄露确认标准已定义（如非授权批量下载>1000条个人信息）
- [ ] 72小时监管上报流程已打通
- [ ] 每半年应急演练计划已制定
- [ ] 《数据泄露应急响应预案》+ 《上报材料模板》已输出

## Overall

- [ ] All 6 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.
