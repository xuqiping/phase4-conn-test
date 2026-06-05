# AGENTS.md — Task Routing Table

## Agent: 超级程序员_软件测试与质量保障Agent

本文件是任务路由的唯一真相来源。当用户向本子Agent提出请求时，读取此表以确定加载哪个细粒度工作流。

## Routing Table

| 任务关键词 / 意图 | 工作流文件 | 描述 |
|------------------|-----------|------|
| 功能测试,手工测试,黑盒测试,白盒测试,探索性测试,ET,SBTM,Tour,等价类,边界值,判定表,状态转换,场景法,BDD,Gherkin,组合测试,Pairwise,用例设计,缺陷管理,Bug报告,缺陷定级,Triage,根因分析,RCA,5Why,鱼骨图,8D,用户体验测试,UX测试,可用性测试,SUS,HEART,风险驱动测试,FMEA,RPN,AI测试生成,测试评审 | workflow/functional_testing_workflow.md | 功能测试全链路：测试思维模型（六维）/用例设计六法（等价类/边界值/判定表/状态转换/场景法/组合测试）/探索性测试SBTM/Tour方法论/缺陷管理CLEAR报告/六维矩阵/AI Triage/根因分析 |
| 自动化测试,接口测试,API测试,REST Assured,Karate,pytest,Postman,契约测试,Pact,OpenAPI,Schema验证,GraphQL,gRPC,WebSocket,Mock,WireMock,UI自动化,Playwright,Selenium,Cypress,Appium,移动端测试,视觉AI,自愈合测试,低代码测试,性能测试,压测,k6,JMeter,LoadRunner,全链路压测,容量规划,USL,混沌测试,流量复制,GoReplay,影子库 | workflow/automated_testing_workflow.md | 自动化测试三维：接口自动化（OpenAPI/契约测试/CI流水线）/UI自动化（Playwright/Selenium/Appium/视觉AI/自愈合）/性能测试（k6/JMeter/全链路压测/容量规划） |
| 测试左移,Shift Left,测试右移,Shift Right,DevOps,质量门禁,Quality Gate,SonarQube,覆盖率,突变测试,SAST,SCA,DevSecOps,CI/CD,金丝雀发布,蓝绿部署,混沌工程,Chaos Mesh,TDD,BDD,可测试性设计,测试团队,QE,SDET,QA转型,TaaS,测试平台,DORA,缺陷逃逸率,质量度量,质量文化 | workflow/testing_engineering_workflow.md | 测试工程体系：测试左移/右移4D模型/DevOps四层质量门禁（PR/主干/预发布/生产验证）/混沌工程/企业测试团队架构（QA→QE→TaaS）/SDET技术栈/质量度量体系 |

## Notes

- 本子Agent处理所有与软件测试、质量保障、自动化测试、DevOps质量门禁、测试团队管理相关的请求。
- 关键词覆盖范围要足够广以捕获同义/转述请求。
- 新增能力时在此表添加新行，并创建对应 workflow + checklist 文件对。

## Cross-Module Dependencies

- `functional_testing_workflow.md` Step 2 中的BDD场景法可能引用 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 需求工程]（用户故事/验收标准/需求追踪）
- `automated_testing_workflow.md` Step 1 中的契约测试可能引用 [参考: Agents知识库/0_超级编程行业知识库/02_后端架构与中间件.md > 微服务架构]（API网关/服务拆分/接口规范）
- `automated_testing_workflow.md` Step 2 中的UI自动化可能引用 [参考: Agents知识库/0_超级编程行业知识库/01_编程语言与基础开发.md > 前端框架]（React/Vue组件测试/前端工程化）
- `automated_testing_workflow.md` Step 3 中的全链路压测可能引用 [参考: Agents知识库/0_超级编程行业知识库/02_后端架构与中间件.md > 服务治理]（限流/熔断/降级/容量评估）
- `testing_engineering_workflow.md` Step 2 中的DevSecOps门禁可能引用 [参考: Agents知识库/0_超级编程行业知识库/07_网络安全与信息安全.md > 应用安全]（SAST/DAST/SCA/ASPM）
- `testing_engineering_workflow.md` Step 2 中的混沌工程可能引用 [参考: Agents知识库/0_超级编程行业知识库/02_后端架构与中间件.md > 服务治理]（故障注入/韧性验证）
