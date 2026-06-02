# AGENTS.md — Task Routing Table

## Agent: 超级程序员_编程语言与基础开发Agent

本文件是任务路由的唯一真相来源。当用户向本子Agent提出请求时，读取此表以确定加载哪个细粒度工作流。

## Routing Table

| 任务关键词 / 意图 | 工作流文件 | 描述 |
|------------------|-----------|------|
| Java,Python,Go,C++,Rust,PHP,Node.js,后端,后端语言,后端选型,后端学习,Java体系,Python体系,Go体系,Spring,FastAPI,Gin,Laravel,NestJS,技术选型,学习路径,问题排查,架构设计,团队技术栈 | workflow/backend_programming_language_workflow.md | 后端编程语言技术选型、学习路径、问题排查、架构设计 |
| 前端,前端框架,Vue,React,Angular,JavaScript,TypeScript,JS,TS,uni-app,小程序,跨端,前端选型,前端学习,Web前端,SSR,SSG,性能优化 | workflow/frontend_framework_workflow.md | 前端编程语言与框架技术选型、学习路径、跨端方案、问题排查 |
| 数据结构,算法,操作系统,编译原理,设计模式,计算机组成原理,面试,LeetCode,刷题,OS,底层基础,基础学习,工程优化,性能优化,缓存,系统调用,eBPF,LLVM,MLIR,SOLID,GoF | workflow/compiler_fundamentals_workflow.md | 编译原理与底层基础知识解答、学习路径、面试辅导、工程优化 |
| 低代码,无代码,低代码平台,无代码开发,低代码选型,Bubble,OutSystems,Mendix,企业低代码,无代码创业,COE,公民开发者,钉钉宜搭,得帆云,ClickPaaS,Webflow,Glide | workflow/lowcode_nocode_workflow.md | 低代码/无代码平台选型、企业落地策略、创业应用分析 |

## Notes

- 本子Agent处理所有与编程语言及基础开发相关的请求。
- 关键词覆盖范围要足够广以捕获同义/转述请求。
- 新增能力时在此表添加新行，并创建对应 workflow + checklist 文件对。

## Cross-Module Dependencies

- `backend_programming_language_workflow.md` Step 2 中的微服务场景可能引用 [参考: Agents知识库/0_超级编程行业知识库/02_网络架构与中间件.md > 微服务基础理论]
- `frontend_framework_workflow.md` Step 2 中的跨端方案可能引用 [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 跨端开发]
