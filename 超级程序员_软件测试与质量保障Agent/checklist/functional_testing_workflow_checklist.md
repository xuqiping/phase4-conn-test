# 功能测试 Workflow Checklist

Use this checklist after completing every step of `workflow/functional_testing_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 需求分析与测试策略制定

- [ ] 已提取完整功能点清单，每个功能点可测试性评估通过
- [ ] 已使用FMEA方法评估RPN，RPN>200的模块已标记为专项测试
- [ ] 测试优先级分级明确（P0阻塞→P4延后），高RPN模块对应P0/P1
- [ ] 至少启用3种测试思维模型（黑盒/白盒/系统/逆向/用户/风险），高风险领域启用全部6种
- [ ] 测试范围、环境、数据、时间估算已明确
- [ ] 准入/准出标准已定义且可量化
- [ ] 测试计划通过评审，用户确认
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位

## Step 2: 测试用例设计

- [ ] 用例设计技术选择有明确理由，与功能特性匹配（等价类/边界值/判定表/状态转换/场景法/组合测试）
- [ ] 等价类划分遵循"多有效类合并+单无效类独立"原则
- [ ] 边界值分析覆盖min-1/min/max/max+1，浮点数用BigDecimal处理
- [ ] 判定表法覆盖所有条件组合，MC/DC达标（若适用航空/汽车DAL-A）
- [ ] 状态转换图法覆盖0-switch/1-switch，关键路径N-switch
- [ ] 场景法包含基本流+至少3个替代流+至少3个异常流（Sad Path）
- [ ] BDD格式（Given-When-Then）正确，工具链（Cucumber/pytest-bdd/Karate）选型明确
- [ ] 组合测试使用Pairwise/ACTS将组合爆炸压缩至可执行范围
- [ ] 用例评审通过，五维覆盖率矩阵（需求/代码/变更/风险/AI生成）已评估
- [ ] 需求覆盖率≥95%，关键路径覆盖率100%

## Step 3: 探索性测试与UX验证

- [ ] SBTM Charter设计明确（目标/范围/时间/资源）
- [ ] Session执行单元为90分钟（5+75+10），Debrief汇报完成
- [ ] 至少执行3种Tour方法论（地标/反叛/破坏者/通宵/古董商/超模/收藏家/指南书）
- [ ] HEART框架五项指标（Happiness/Engagement/Adoption/Retention/Task Success）已评估
- [ ] SUS评分已采集，62分以下不及格，78分良好
- [ ] 无障碍测试覆盖WCAG 2.1 AA（若适用EAA合规）
- [ ] 探索性测试报告包含缺陷清单、风险项、覆盖率评估

## Step 4: 缺陷管理与根因分析

- [ ] 缺陷报告遵循CLEAR五步法（Concise/Locate/Evidence/Actual vs Expected/Reproducible）
- [ ] 缺陷定级使用六维矩阵（严重度/优先级/影响面/业务价值/修复成本/风险等级）
- [ ] Triage SLA达标：新缺陷4小时内完成，P0缺陷30分钟内分配
- [ ] AI Triage或人工Triage完成，分类准确率目标≥85%
- [ ] 重复检测机制运行（Sentence-BERT或人工检查），避免重复修复
- [ ] 修复验证遵循FIXER六步法，可信回归三原则执行
- [ ] 根因分析使用5Why（至少3层）或鱼骨图或8D报告，已产出预防措施
- [ ] 无责备复盘原则执行，聚焦系统改进
- [ ] 缺陷逃逸率（DRE）已度量，目标<10%
- [ ] 三层质量门禁（提交/构建/发布）已定义并与缺陷管理联动

## Overall

- [ ] All steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位。
