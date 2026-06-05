# Telecom Industry Standards Workflow Checklist

Use this checklist after completing every step of `workflow/telecom_standards_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 5G/5G-A架构设计与部署

- [ ] 《5G-A网络架构设计书》已输出
- [ ] 三大能力（下行万兆/上行千兆/低空覆盖）有明确技术方案
- [ ] 设备选型已完成（核心网/无线/传输至少3类设备）
- [ ] 频谱规划方案已输出（Sub-6GHz+毫米波协同）
- [ ] 《设备选型报告》已输出

## Step 2: BSS/OSS中台化重构

- [ ] 《BSS/OSS中台化架构蓝图》已输出
- [ ] 微服务拆分策略清晰（前台/中台/后台三层）
- [ ] 技术栈已确定（Spring Cloud Alibaba + K8s + ONAP）
- [ ] 迁移策略已制定（新建直接云原生→存量逐步容器化→老旧退役）
- [ ] 《BSS/OSS中台化实施路线图》已输出

## Step 3: 算力网络架构设计

- [ ] 《算力网络架构设计书》已输出
- [ ] SRv6+CFN算力路由方案已确定
- [ ] 智算网络架构（Spine-Leaf+RDMA）设计完成
- [ ] PUE目标已设定（西部≤1.25/东部≤1.30）
- [ ] 《智算中心设计方案》已输出

## Step 4: 承载网400G升级

- [ ] 《承载网400G升级方案》已输出
- [ ] G.654.E光纤部署计划已确定
- [ ] OXC全光交换节点规划已完成
- [ ] SRv6+FlexE硬切片策略已定义
- [ ] 升级路线图（试点→规模部署→全网覆盖）已制定

## Step 5: 卫星互联网融合

- [ ] 《卫星互联网融合方案》已输出
- [ ] 天通+5G双模终端规模部署计划已确定
- [ ] 3GPP R17 NTN技术方案（透明载荷/再生载荷）已选定
- [ ] 频谱协调方案（L/S/Ka频段）已规划
- [ ] 《NTN技术实施指南》已输出

## Step 6: 通信设备集采流程执行

- [ ] 《集采执行手册》已输出
- [ ] 7步法每步有明确交付物与责任人
- [ ] 技术规范书引用至少3项行业标准（3GPP/ITU-T/CCSA）
- [ ] 评标标准技术分权重≥60%
- [ ] 《技术规范书模板》+ 《评标标准模板》已输出

## Overall

- [ ] All 6 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.
