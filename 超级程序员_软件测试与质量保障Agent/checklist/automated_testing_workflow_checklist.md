# 自动化测试 Workflow Checklist

Use this checklist after completing every step of `workflow/automated_testing_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 接口自动化测试体系搭建

- [ ] OpenAPI 3.1规范已获取或已从PRD生成，作为测试设计基准
- [ ] 契约验证使用JSON Schema/Zod/Ajv，消费者驱动契约（CDC）使用Pact v4（若适用微服务）
- [ ] 接口测试框架选型有明确决策矩阵（团队技术栈×学习曲线×CI成熟度×报告丰富度）
- [ ] 测试用例三层分层明确：冒烟层（<3min）/回归层（<30min）/全量层（<2h）
- [ ] 特殊协议（GraphQL/gRPC/WebSocket）有专项测试方案
- [ ] Mock工具选型明确（WireMock/MSW/MockServer/Hoverfly）
- [ ] 测试数据管理合规：生产数据脱敏或AI合成，符合GDPR/个人信息保护法
- [ ] CI/CD三层流水线集成：提交层（<3min）→构建层（<30min）→发布层（<2h）
- [ ] AI驱动接口测试：脚本生成/自愈/多Agent协同至少一项已评估或落地
- [ ] 核心接口覆盖率≥90%，接口缺陷联调前捕获率提升目标已量化
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位

## Step 2: UI自动化测试体系搭建

- [ ] UI自动化范围聚焦"用户旅程关键路径"，占比<15%全部用例，ROI已评估
- [ ] Web框架选型明确：Playwright（新项目）/Selenium 4（遗留）/Cypress（企业级）
- [ ] 移动端框架选型明确：Appium 2.x（跨平台）/XCUITest（iOS原生）/Espresso（Android原生）
- [ ] 元素定位策略分层：data-testid优先→视觉语义fallback→相对XPath兜底
- [ ] 自愈合测试已评估或落地（五级自愈层次至少实现前3级）
- [ ] 视觉AI定位已评估（Applitools/Testim/mabl/Playwright 1.50+）
- [ ] 跨浏览器帕累托策略已定义（Tier1每次提交→Tier4每月）
- [ ] 失败分析体系完整：智能分类（ML准确率≥85%）/Trace Viewer/Allure报告
- [ ] 关键KPI达标：通过率>95%、Flaky Rate<2%、MTTR<4h、核心流程<15min
- [ ] CI分层执行：提交（1min）→PR（5min）→全量回归（30-60min nightly）

## Step 3: 性能测试与压测方案

- [ ] 性能指标定义完整：P50/P95/P99 RT、QPS/TPS、错误率、资源利用率
- [ ] 性能测试工具选型明确：k6（新项目首选）/JMeter（Java生态）/国产工具（信创需求）
- [ ] 负载模型六级数据建模至少覆盖到第4级（统计分布/Zipf效应）
- [ ] 性能瓶颈分析四维定位法已配置：CPU火焰图/内存GC/IO iostat/网络tcpdump
- [ ] eBPF无侵入分析工具（Pixie/Grafana Beyla）已评估或部署
- [ ] 全链路压测方案完整：流量复制+影子库+全链路追踪+自动熔断+低峰期执行
- [ ] 混沌工程融合：压测期间注入至少1类故障（Pod kill/网络延迟/数据库故障）
- [ ] 三级性能门禁：L1提交（3min单用户）→L2合并（10min/100并发）→L3发布（30min全链路）
- [ ] 容量规划：USL建模或AI预测（Prophet/LSTM）已输出容量需求报告
- [ ] 性能基线已建立，退化>5%则阻断发布

## Overall

- [ ] All steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位。
