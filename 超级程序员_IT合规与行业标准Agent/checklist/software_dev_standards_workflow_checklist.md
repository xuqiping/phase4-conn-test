# Software Development Standards Workflow Checklist

Use this checklist after completing every step of `workflow/software_dev_standards_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: GB/T 8566生存周期过程落地

- [ ] 《软件生存周期过程定义》已输出，四大过程类（技术/管理/组织使能/协议）已映射到实际项目管理流程
- [ ] 每个过程的输入/输出/责任人/工具/检查点已定义
- [ ] 《过程裁剪指南》已输出，允许根据项目规模裁剪
- [ ] 所有`[参考: ...]`标注指向知识库中"GB/T 8566四大过程类"章节

## Step 2: GB/T 8567文档编制规范实施

- [ ] 14类核心文档清单与模板已建立
- [ ] 统一文档模板（封面/目录/正文/附录/版本历史）已配置
- [ ] 文档评审流程已定义（编写→自审→同行评审→专家评审→批准发布）
- [ ] 文档变更控制流程已定义（变更申请→影响分析→审批→修订→重评审）

## Step 3: GB/T 25000质量模型应用

- [ ] 八大质量特性（功能性/性能效率/兼容性/易用性/可靠性/信息安全性/可维护性/可移植性）已量化
- [ ] 每个特性有可量化的评估指标与实测数据
- [ ] 信创测试直接引用此模型作为验收标准
- [ ] 《软件质量评估报告》+ 《质量特性度量指标库》已输出

## Step 4: GB/T 36964成本度量实施

- [ ] 功能点计数方法已掌握（ILF/EIF/EI/EO/EQ组件识别与复杂度判定）
- [ ] 未调整功能点（UFP）+ 调整因子（VAF）计算正确
- [ ] 工作量转化参考行业基准数据（CSBMK）
- [ ] 《功能点计数报告》+ 《项目成本估算书》已输出

## Step 5: GB/T 47470-2026安全开发能力评估

- [ ] 自评估已完成，八大能力域（安全需求/设计/编码/测试/交付/运维/知识/治理）逐项打分
- [ ] 当前成熟度等级与目标等级差距已识别
- [ ] 12个月改进路线图已制定，每季度一个里程碑
- [ ] 《安全开发能力评估报告》+ 《改进路线图》已输出

## Step 6: 等保2.0与信创标准落地

- [ ] 等保差距分析完成，三级系统211项测评要求逐条对照
- [ ] 未满足项整改计划已制定（技术+管理措施）
- [ ] 信创全栈选型已完成（芯片/OS/数据库/中间件/应用/安全）
- [ ] 《等保差距分析报告》+ 《整改计划》+ 《信创替代清单与路线图》已输出

## Overall

- [ ] All 6 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.
