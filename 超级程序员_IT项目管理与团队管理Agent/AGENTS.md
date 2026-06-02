# AGENTS.md — Task Routing Table

## Agent: 超级程序员_IT项目管理与团队管理Agent

本文件是任务路由的唯一真相来源。当用户向本子Agent提出请求时，读取此表以确定加载哪个细粒度工作流。

## Routing Table

| 任务关键词 / 意图 | 工作流文件 | 描述 |
|------------------|-----------|------|
| 产品需求,需求设计,JTBD,KANO,RICE,需求优先级,需求验证,Validation Pyramid,MVP,PMF,产品路线图,Roadmap,North Star Metric,AARRR,A/B测试,用户访谈,竞品分析,To B,To C,需求管理,需求评审,DACI,Shape Up,PRD,原型设计,原型,Figma,Axure,Double Diamond,Atomic Design,Design Token,BMC,商业模式,Lean Canvas,ICP,定价,PLG,SLG,GTM,NRR,Rule of 40,Magic Number,SaaS商业化,产品增长,增长黑客,RevOps | workflow/product_manager_workflow.md | 产品经理体系：IT产品需求设计（JTBD/KANO/RICE/Validation Pyramid）→原型设计&8段式PRD撰写（Double Diamond/Atomic Design）→技术产品商业化规划（BMC/Lean Canvas/PLG+SLG/定价策略/SaaS指标） |
| 团队管理,组织架构,团队架构,Conway定律,Team Topologies,Squad,Tribe,超级个体,AI团队,创业团队,股权分配,ESOP,Slicing Pie,远程协作,Async First,招聘,面试,Bar Raiser,Take-Home Project,职级体系,双通道,M序列,P序列,阿里P,字节,腾讯T,九宫格,人才盘点,绩效,OKR,35+转型,研发流程,Platform Engineering,Trunk-Based Development,ADR,C4模型,DDD,CI/CD,Feature Flag,测试金字塔,混沌工程,可观测性,SRE,SLI,SLO,DORA,SPACE,效能度量,价值流 | workflow/team_management_workflow.md | 技术团队管理：创业小团队分工架构（Conway/Team Topologies/Squad+Tribe/超级个体+AI/股权）→技术人才梯队建设（双通道M/P/九宫格盘点/AI时代能力模型/Bar Raiser）→研发流程规范（Platform Engineering/Trunk-Based Dev/ADR/C4/DDD/测试金字塔/可观测性/SRE/DORA+SPACE） |
| 项目管理,PMP,PMBOK,项目管理计划,WBS,EVM,挣值管理,关键路径,CPM,CCPM,蒙特卡洛,风险管理,干系人管理,瀑布模型,Waterfall,V模型,基线,配置管理,CCB,变更控制,Scrum,敏捷,Product Owner,Scrum Master,Sprint,Backlog,DoD,WSJF,DORA,SAFe,LeSS,Nexus,每日站会,Sprint评审,Sprint回顾,规模化敏捷,混合模式,外瀑内敏,数字化瀑布 | workflow/project_management_workflow.md | 项目管理方法论：PMP项目管理标准（PMBOK 8th/6原则+7绩效领域/WBS/EVM/CPM/蒙特卡洛）→敏捷Scrum（3角色5事件3工件/WSJF/DoD/规模化SAFe/LeSS/Nexus）→瀑布模型（7阶段/V模型/基线控制/外瀑内敏混合） |

## Notes

- 本子Agent处理所有与产品需求设计、PRD撰写、商业化规划、团队组织架构、人才梯队建设、研发流程规范、PMP项目管理、敏捷Scrum、瀑布模型相关的请求。
- 关键词覆盖范围要足够广以捕获同义/转述请求。
- 新增能力时在此表添加新行，并创建对应 workflow + checklist 文件对。

## Cross-Module Dependencies

- `product_manager_workflow.md` Step 2 中的PRD撰写可能引用 [参考: Agents知识库/0_超级编程行业知识库/01_编程语言与基础开发.md > 前端框架]（UI组件/交互设计技术可行性）
- `product_manager_workflow.md` Step 3 中的商业化规划可能引用 [参考: Agents知识库/0_超级编程行业知识库/05_人工智能与大模型.md > AI应用开发]（AI产品商业化/Service-as-a-Software）
- `team_management_workflow.md` Step 2 中的研发流程可能引用 [参考: Agents知识库/0_超级编程行业知识库/09_软件测试与质量保障.md]（测试金字塔/CI门禁/可观测性/DORA）
- `team_management_workflow.md` Step 3 中的效能度量可能引用 [参考: Agents知识库/0_超级编程行业知识库/02_后端架构与中间件.md > 微服务架构]（架构决策/限界上下文/服务拆分）
- `project_management_workflow.md` Step 1 中的WBS分解可能引用 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 需求工程]（需求追踪/范围管理）
- `project_management_workflow.md` Step 2 中的Scrum事件可能引用 [参考: Agents知识库/0_超级编程行业知识库/09_软件测试与质量保障.md > 测试工程体系]（左移4D/DevOps门禁/混沌工程）
