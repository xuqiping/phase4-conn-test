# System Design and Technology Selection Workflow Checklist

Use this checklist after completing every step of `workflow/system_design_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: DDD战略设计与事件风暴

- [ ] 《领域模型图》已输出（Event Storming五阶段结果）
- [ ] 统一语言术语表≥30个术语
- [ ] 限界上下文数量2-7个
- [ ] 《限界上下文映射图》已输出（ACL/OHS/PL等关系完整）
- [ ] 事件风暴工作坊记录已归档

## Step 2: DDD战术设计与架构风格选型

- [ ] 《系统架构设计书》已输出
- [ ] 分层架构图（用户接口/应用/领域/基础设施）已绘制
- [ ] 实体关系图（ER图）覆盖全部业务实体
- [ ] CQRS+ES决策记录已输出
- [ ] 架构风格选型符合团队规模（<15人用单体）

## Step 3: ADR架构决策记录

- [ ] 《ADR目录》已输出，包含≥5个关键决策记录
- [ ] 格式符合MADR 3.x标准（标题/状态/背景/决策/备选/后果）
- [ ] C4模型（Context-Container-Component-Code）已Structurizr DSL化
- [ ] ADR不可修改，新ADR标记旧ADR为Superseded

## Step 4: 技术选型评估矩阵

- [ ] 《技术选型评估报告》已输出
- [ ] ≥3项关键技术有完整评估矩阵（功能25%/性能20%/运维15%/团队15%）
- [ ] 每项技术有POC验证结论
- [ ] 技术雷达（Adopt/Trial/Assess/Hold）已更新

## Step 5: 数据库设计与API First设计

- [ ] 《数据库设计说明书》已输出（ER图+表结构+索引+分区策略）
- [ ] 《API设计规范》已输出（URL/请求响应/错误码/版本策略）
- [ ] OpenAPI 3.x Spec文件（YAML/JSON）已编写
- [ ] 数据库范式策略：先3NF再按需反范式

## Step 6: UI/UX设计系统与HEART度量

- [ ] 《设计系统规范》已输出（Token+Component+Pattern+Template）
- [ ] 设计Token到前端组件库1:1映射已建立
- [ ] 《HEART度量指标库》已输出（Happiness/Engagement/Adoption/Retention/Task Success）
- [ ] NPS基线已建立，每季度追踪

## Overall

- [ ] All 6 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.
