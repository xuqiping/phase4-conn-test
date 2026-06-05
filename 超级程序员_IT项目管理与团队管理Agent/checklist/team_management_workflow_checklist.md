# 技术团队管理 Workflow Checklist

Use this checklist after completing every step of `workflow/team_management_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 团队组织架构设计

- [ ] 组织模型选型有明确理由：扁平式/层级式/Squad+Tribe/超级个体+AI
- [ ] Team Topologies四种团队类型定义清晰，占比合理（Stream-aligned ~80%）
- [ ] 四种交互模式（XaaS/Collaboration/Facilitating/As-is）已匹配团队对
- [ ] Squad/Tribe模型（如适用）：Squad 5-9人/Tribe 40-150人/Chapter/Guild定义
- [ ] AI时代5层架构已评估：执行/协调/决策/创意/战略
- [ ] 团队规模演进路径：创始期→早期→成长期→规模化，每阶段关键动作明确
- [ ] 股权方案：ESOP 20-25%/Slicing Pie动态分配（如适用），经律师确认
- [ ] 远程协作规范：Async First/Follow-the-Sun/季度线下集结
- [ ] Trio决策制：PM+Tech Lead+Designer角色定义

## Step 2: 技术人才梯队建设

- [ ] 双通道职级体系发布：M管理序列/P专业序列，每级能力标准明确
- [ ] 职级对标清晰：阿里P/字节/腾讯T对应关系
- [ ] 九宫格盘点完成≥1轮：绩效×潜力，明星/骨干/改进/淘汰分类
- [ ] AI时代能力模型定义：技术深度/AI协作/系统思维/领域知识
- [ ] 35+转型路径：架构师/技术管理/AI+领域专家三条路径
- [ ] 招聘SOP：Take-Home Project+Bar Raiser+Reverse Recruiting至少2项启用
- [ ] 面试流程完整：简历筛选→技术面试→行为面试→Bar Raiser→试用（可选）
- [ ] 内部流动机制：Transfer制度/轮岗计划
- [ ] 薪酬激励：固定70%+绩效20%+长期激励10%，市场分位P50-P75

## Step 3: 研发流程规范与效能度量

- [ ] 需求管理：Living Requirements+ADR+C4模型+DDD至少3项启用
- [ ] 编码规范：Lint+SonarQube+圈复杂度≤15+AI审查
- [ ] 版本控制：Trunk-Based Development+短生命周期分支+Feature Flag
- [ ] Code Review：至少1人+AI辅助+审查清单
- [ ] 测试金字塔：单元70%/集成20%/E2E 10%+契约测试+混沌工程
- [ ] CI/CD：自动构建→测试→安全扫描→渐进式发布（Feature Flag→灰度→蓝绿）
- [ ] Platform Engineering：IDP构建，开发者自助能力≥3项（环境/DB/部署）
- [ ] 可观测性三大支柱：Metrics+Logs+Traces全部部署
- [ ] SRE实践：SLI/SLO/Error Budget定义，On-Call轮值运行
- [ ] DORA四指标基线建立，目标精英级（按需部署/<1天前置/<5%失败/<1h恢复）
- [ ] SPACE五维度量：满意度/绩效/活动/协作/效率
- [ ] 价值流映射：识别瓶颈（如"等待测试环境"平均天数）

## Overall

- [ ] All steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位。
