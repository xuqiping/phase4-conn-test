# Government IT Project Workflow Checklist

Use this checklist after completing every step of `workflow/gov_it_project_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 招投标阶段合规执行

- [ ] 采购方式已明确（公开招标/邀请招标/竞争性谈判/单一来源），符合《政府采购法》
- [ ] 招标文件已编制，包含技术规范书/商务条款/评标标准/合同模板
- [ ] 投标人资质门槛已设定（等保/密评/信创/CMMI/ISO 27001）
- [ ] 技术规范书引用至少3项国标（GB/T 8566/8567/25000/等保2.0/信创）
- [ ] 评标标准技术分权重≥60%
- [ ] 《评标报告》+ 《中标通知书》已输出

## Step 2: 需求分析阶段深度落地

- [ ] 《需求规格说明书》已通过甲方评审签字
- [ ] 需求追溯矩阵（RTM）覆盖率100%
- [ ] 关键需求已通过原型确认（Figma/Axure高保真原型+签字）
- [ ] 需求优先级已按MoSCoW法则分类
- [ ] 《需求评审会议纪要》+ 《原型确认单》已输出

## Step 3: 方案设计阶段标准化

- [ ] 《系统设计方案》已通过专家评审
- [ ] 等保2.0"一个中心三重防护"已融入安全设计
- [ ] 密码应用设计已纳入（SM2/SM3/SM4国密算法）
- [ ] 信创适配方案已完成（芯片/OS/数据库/中间件选型）
- [ ] PoC验证计划已制定（验证目标/范围/环境/通过标准）
- [ ] 《安全设计专篇》+ 《信创适配方案》已输出

## Step 4: 实施交付阶段管控

- [ ] 项目进度偏差<10%
- [ ] 缺陷密度<0.5/KLOC
- [ ] 变更控制率<15%
- [ ] 周/月度报告按时提交
- [ ] 代码质量：SonarQube高危问题清零，覆盖率≥80%
- [ ] 《变更日志》+ 《风险登记册》+ 《月度项目报告》已输出

## Step 5: 验收运维阶段移交

- [ ] 验收测试通过率100%
- [ ] 等保测评/密评（如需）已通过
- [ ] 14类GB/T 8567文档已移交齐全
- [ ] 管理员+用户培训已完成，考核合格率≥90%
- [ ] 维保协议已签订（响应时间：P1-2h/P2-8h/P3-24h）
- [ ] 《验收测试报告》+ 《文档移交清单》+ 《培训记录》已输出

## Overall

- [ ] All 5 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.