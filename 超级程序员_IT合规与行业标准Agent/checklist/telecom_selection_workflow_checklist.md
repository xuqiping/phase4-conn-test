# Telecom Technology Selection Workflow Checklist

Use this checklist after completing every step of `workflow/telecom_selection_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 7+1评估矩阵建立

- [ ] 《技术选型评估矩阵模板》已输出
- [ ] 7+1维度权重已根据组织战略调整（技术25%/经济20%/战略15%/风险15%/运维10%/生态10%/时间5%+合规一票否决）
- [ ] 评分标准已量化（1-5分，每维度有明确评分标准）
- [ ] 合规维度一票否决项已定义（等保/密评/信创/入网许可）
- [ ] 《评分标准手册》+ 《合规检查清单》已输出

## Step 2: 5G-A/6G技术选型

- [ ] 《5G-A/6G技术选型报告》已输出
- [ ] 设备厂商≥2家入围（华为/中兴/爱立信至少评估2家）
- [ ] 技术路线有3年演进规划
- [ ] POC测试方案已制定（典型场景：密集城区/工业园区/低空覆盖）
- [ ] 集采历史数据（价格/交付周期/故障率/客户满意度）已参考

## Step 3: BSS/OSS重构技术选型

- [ ] 《BSS/OSS重构技术选型报告》已输出
- [ ] 微服务框架+云原生平台+信创组件全部确定
- [ ] 《信创适配矩阵》已建立（每个技术组件列出首选+备选）
- [ ] 迁移成本已估算（代码改造/数据迁移/测试/培训）
- [ ] 参考中国电信OSS3.0国产化重构案例（CAPEX 28亿经验）

## Step 4: 算力网络技术选型

- [ ] 《算力网络技术选型报告》已输出
- [ ] SRv6/CFN/RoCE/液冷等关键技术方案已确定
- [ ] 智算中心选址PUE目标已设定（西部≤1.25/东部≤1.30）
- [ ] 绿电比例目标≥80%
- [ ] 《智算中心设计方案》+ 《算力调度平台架构》已输出

## Step 5: 物联网技术选型

- [ ] 《物联网技术选型报告》已输出
- [ ] NB-IoT/RedCap/Cat.4/Cat.1适用场景/成本/功耗/覆盖已明确
- [ ] 模组选型≥2家（移远/广和通/芯讯通/美格智能至少评估2家）
- [ ] 《物联网技术-场景匹配矩阵》已输出
- [ ] 平台对比分析（OneNET/天翼/联通）已完成

## Step 6: 卫星通信技术选型

- [ ] 《卫星通信技术选型报告》已输出
- [ ] 天通/北斗/低轨卫星方案已明确
- [ ] 终端芯片≥2家备选（华力创通/紫光展锐/和芯星通至少评估2家）
- [ ] 3GPP R17 NTN融合方案已选定
- [ ] 《卫星通信业务发展路线图》已输出

## Step 7: 信创技术选型

- [ ] 《信创技术选型报告》已输出
- [ ] 六大层级（芯片/OS/数据库/中间件/应用/安全）全部有信创替代方案
- [ ] 《信创替代清单》已建立（非信创组件→信创替代品+替代时间+替代风险）
- [ ] 兼容性测试计划已制定
- [ ] 《替代路线图》已输出，确保2027年100%替代目标达成

## Step 8: 集采流程技术选型执行

- [ ] 《集采技术规范书》已输出
- [ ] 技术评分标准已量化（功能/性能/兼容性/可扩展性）
- [ ] 合规维度一票否决项明确（信创目录/等保/入网许可）
- [ ] 供应商评估表已建立（资质/技术/商务三维度）
- [ ] 风险控制清单已输出（单一来源/地缘政治/技术锁定）

## Overall

- [ ] All 8 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.