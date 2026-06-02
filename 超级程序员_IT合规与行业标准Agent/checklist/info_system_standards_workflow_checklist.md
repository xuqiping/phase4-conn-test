# Information System Standards Workflow Checklist

Use this checklist after completing every step of `workflow/info_system_standards_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 系统规划阶段标准化

- [ ] 《系统规划报告》已通过评审，包含目标/范围/约束/资源/风险/进度六要素
- [ ] 现状分析已覆盖现有系统架构/数据资产/业务流程/技术债务
- [ ] 可行性分析已完成技术/经济/法律三维度评估
- [ ] 规划文档格式符合GB/T 8567《项目开发计划》章节要求
- [ ] 《可行性研究报告》+ 《项目开发计划》已输出

## Step 2: 系统分析阶段标准化

- [ ] 《需求规格说明书》已通过甲方评审签字
- [ ] 每条需求有唯一ID+优先级+验收标准+追溯来源
- [ ] 需求追溯矩阵（RTM）覆盖率100%
- [ ] 关键需求已通过原型确认（Figma/Axure高保真原型+签字）
- [ ] 《需求评审会议纪要》已输出

## Step 3: 系统设计阶段标准化

- [ ] 《系统设计方案》已通过专家评审
- [ ] 等保/密评/信创要求已融入设计
- [ ] PoC验证计划已制定（验证目标/范围/环境/通过标准）
- [ ] 包含概要设计+详细设计+接口设计+安全设计专篇
- [ ] 设计文档符合GB/T 8567《设计说明书》章节结构

## Step 4: 系统实施阶段标准化

- [ ] 代码审查通过率100%
- [ ] 单元测试覆盖率≥80%
- [ ] 集成测试通过率100%
- [ ] 静态扫描高危问题清零
- [ ] 文档与代码版本同步更新

## Step 5: 系统验收阶段标准化

- [ ] 验收测试通过率100%
- [ ] 性能指标达到设计目标
- [ ] 等保测评/密评（如需）已通过
- [ ] 交付物清单完整（14类GB/T 8567文档齐全）
- [ ] 《验收报告》各方签字确认，遗留问题有整改计划

## Step 6: 系统运维阶段标准化

- [ ] 监控覆盖率100%
- [ ] SLA达标（如99.9%可用性）
- [ ] 故障响应时间符合约定
- [ ] 备份策略已实施（全量+增量+异地容灾）
- [ ] 《系统运维手册》已移交

## Overall

- [ ] All 6 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.
