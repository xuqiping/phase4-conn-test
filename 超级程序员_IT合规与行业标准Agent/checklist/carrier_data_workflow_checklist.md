# Carrier Data Modeling Workflow Checklist

Use this checklist after completing every step of `workflow/carrier_data_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 运营商三域架构梳理

- [ ] 《运营商三域架构蓝图》已输出
- [ ] B域/O域/D域系统清单完整（系统名称/厂商/版本/数据量/接口数量）
- [ ] 《三域数据流向图》已绘制（采集→清洗→存储→加工→服务→消费）
- [ ] 数据孤岛与断点已识别，制定《数据贯通计划》
- [ ] TM Forum SID统一信息模型已对齐

## Step 2: 套餐计费建模演进

- [ ] 《套餐计费模型设计书》已输出
- [ ] 支持5G-A多维计费（GB+QoS+时延+可靠性）
- [ ] 计费准确性≥99.99%
- [ ] 现有计费系统兼容映射方案已设计
- [ ] 《计费系统升级路线图》已输出

## Step 3: 用户画像标签体系建设

- [ ] 《用户画像标签体系规范》已输出
- [ ] 标签数量≥3000个，覆盖六大维度（基础/通信/消费/位置/DPI/终端）
- [ ] 标签质量指标已定义（准确率≥95%/覆盖率≥90%/稳定性波动<10%）
- [ ] 联邦学习跨域建模方案已确定
- [ ] 差分隐私与L1-L5脱敏级别已配置

## Step 4: 信令与位置数据管理

- [ ] 《信令数据管理规范》已输出
- [ ] 信令采集完整性≥99.9%
- [ ] AI-DPI模型已部署，识别准确率≥88%
- [ ] 位置脱敏五级体系（L1-L5）已落地
- [ ] 《位置脱敏实施指南》已输出

## Step 5: 数据合规变现体系建设

- [ ] 《数据合规变现体系方案》已输出
- [ ] 隐私计算平台已上线（联邦学习/MPC/TEE至少1种）
- [ ] 对外服务合同含合规条款（使用范围/禁止再识别/审计权利）
- [ ] 数据产品目录已建立（标签服务/模型服务/报告服务）
- [ ] 用户授权机制已建立（明示同意+单独同意+撤回机制）

## Overall

- [ ] All 5 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.