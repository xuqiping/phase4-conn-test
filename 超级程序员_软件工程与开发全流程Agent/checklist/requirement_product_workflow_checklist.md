# Requirement and Product Engineering Workflow Checklist

Use this checklist after completing every step of `workflow/requirement_product_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: AI-Native用户研究与需求收集

- [ ] 《用户研究报告》已输出，覆盖用户画像/核心痛点/使用场景
- [ ] AI用研成本较传统降低80%+
- [ ] B端三层用户（决策者/管理者/使用者）或C端DAU/MAU/LTV已分析
- [ ] 需求按FURPS+分类（功能性/可用性/可靠性/性能/可支持性+其他约束）
- [ ] 合规预检完成：PIPL/GDPR告知同意、生物识别"单独同意"

## Step 2: 需求结构化拆解与Story Mapping

- [ ] 《用户故事地图》已输出（Jeff Patton八步法完整）
- [ ] 四级粒度（Epic/Feature/Story/Task）完整，MVP范围明确
- [ ] 验收标准用BDD Gherkin格式表达（Given-When-Then）
- [ ] 每个Story至少1个验收标准

## Step 3: 优先级组合排序

- [ ] 《需求优先级矩阵》已输出
- [ ] 多框架组合已应用：RICE（Roadmap）+ Kano（战略）+ MoSCoW（版本）
- [ ] Must类需求占比≤60%
- [ ] 每个需求有明确的框架标签与排序依据

## Step 4: 需求变更追踪与RTM管理

- [ ] CCB四级审批机制已建立并运行
- [ ] RTM覆盖率≥95%
- [ ] CIA五维影响分析（Cost/Impact/Architecture/Timeline/Risk）已记录
- [ ] 变更日志完整：变更ID/日期/申请人/CCB决策/影响范围

## Step 5: PRD视觉化与数据驱动迭代

- [ ] PRD通过评审，包含视觉原型+数据埋点方案
- [ ] Figma高保真原型覆盖核心流程
- [ ] A/B测试计划满足p<0.05+功效>0.8
- [ ] 扩展信号已定义：留存率>30%次日+NPS>30+核心功能使用率>50%

## Overall

- [ ] All 5 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.
