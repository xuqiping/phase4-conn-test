# 自动化测试 Workflow

## Purpose

为软件自动化测试提供从接口自动化、UI自动化到性能测试与压测的完整工程化路径。覆盖API契约验证、Web/移动端UI测试、全链路性能压测三大维度，集成CI/CD流水线与AI驱动测试生成。

## Prerequisites

- 用户已明确待测系统技术栈（前后端框架、通信协议、部署架构）
- 用户已提供API文档（OpenAPI/Swagger）或系统可访问用于录制
- 用户已明确自动化目标（回归覆盖/持续集成/性能基线/契约验证）

## Steps

### Step 1: 接口自动化测试体系搭建

**Goal**: 建立接口自动化测试框架，覆盖API契约验证、回归测试、CI/CD集成，实现"接口测试左移"。
**Completion criterion**: 接口测试框架搭建完成，核心接口覆盖率≥90%，CI流水线集成通过，接口缺陷联调前捕获率提升目标达成。

**A. API规范与契约定义**
- 获取OpenAPI 3.1规范（Swagger JSON/YAML），作为测试设计基准
- 若团队采用API-first设计 → 从PRD直接输出OpenAPI规范 → 测试基于规范生成用例
- 契约验证：JSON Schema / Zod / Ajv 验证请求/响应数据结构
- 消费者驱动契约（CDC）：使用Pact v4，消费者团队定义期望→生成契约→提供者团队验证实现
- Pact支持协议：HTTP REST / 消息队列（Kafka/RabbitMQ）/ GraphQL / gRPC

**B. 接口测试框架选型**
- Java生态：Karate（BDD+模拟+性能一体）/ REST Assured（DSL优雅）
- Python生态：pytest + requests（灵活+生态丰富）/ httpx（异步支持）
- Go生态：httptest（标准库）/ ginkgo + gomega（BDD风格）
- Node.js生态：SuperTest（Express/Koa集成）/ axios + jest
- 选型决策矩阵：团队技术栈匹配度 × 学习曲线 × CI集成成熟度 × 报告丰富度 × 社区活跃度

**C. 测试用例分层设计**
- 冒烟层（Pre-commit）：核心接口50条，执行时间<3分钟，每次提交自动运行
- 回归层（Merge）：全量接口300-3000条，执行时间<30分钟，合并前强制通过
- 全量层（Nightly）：扩展边界值/异常场景/安全扫描，执行时间<2小时
- 案例参考：京东接口回归分层（冒烟50条/5min → 回归3000条/30min → 全量1.2万条/2h）

**D. 特殊协议接口测试**
- GraphQL：Apollo Studio / GraphQL Inspector进行Schema变更检测和自动测试生成
- gRPC：BloomRPC / Kreya / ghz进行服务测试，字节/阿里gRPC服务占比超60%
- WebSocket/SSE：测试实时应用（IM/直播/协同/AI流式响应），验证连接稳定性/消息时序/断线重连

**E. Mock与测试数据管理**
- Mock工具：WireMock（Java）/ MSW（前端）/ MockServer（多语言）/ Hoverfly（云原生流量模拟）
- 测试数据即服务（TDaaS）：阿里平台日均生成10亿+测试数据，准备时间从4小时降至5分钟
- AI合成数据：LLM生成符合业务规则的逼真数据，不含真实隐私信息
- 合规红线：GDPR/个人信息保护法要求生产数据脱敏后方可使用，禁止直接用于测试

**F. CI/CD流水线集成**
- 三层流水线标准：
  - 提交层（Pre-commit）：代码风格 + 增量单测 + 快速接口冒烟 / <3分钟
  - 构建层（Merge）：全量单测 + 接口回归 + 性能基线 + 安全扫描 / <30分钟
  - 发布层（Release）：全量接口 + 压测 + 混沌验证 / <2小时
- K8s分布式执行：万级用例10分钟完成（字节实践）
- 质量门禁：基于代码变更风险动态调整门禁严格度

**G. AI驱动接口测试**
- 脚本生成：GitHub Copilot / 通义灵码基于OpenAPI自动生成测试代码，接受率40-60%
- 脚本自愈：字节FastBot覆盖30%接口变更场景，维护人力节省50%
- 多Agent协同：CrewAI / LangGraph推出API Test Swarm概念，Multi-Agent协同探索
- 七步生成法：Gather（收集规范）→ Extract（提取字段约束）→ Generate（生成用例）→ Review（人工审核）→ Execute（执行验证）→ Diagnose（诊断失败）→ Maintain（持续维护）

**输出物**: `接口自动化测试框架_项目名称/`（含测试框架代码、OpenAPI绑定、Mock配置、CI流水线脚本、测试数据工厂）。

**[参考: Agents知识库/0_超级编程行业知识库/软件测试与质量保障/自动化测试.md > 接口自动化测试]**
**[参考: Agents知识库/0_超级编程行业知识库/软件测试与质量保障.md > 接口自动化测试]`

---

### Step 2: UI自动化测试体系搭建

**Goal**: 建立Web和移动端UI自动化测试框架，覆盖用户旅程关键路径，实现视觉AI定位与自愈合能力。
**Completion criterion**: UI测试框架搭建完成，核心用户旅程覆盖率≥80%，Flaky Rate<2%，通过率>95%，MTTR<4小时。

**A. 测试范围与ROI策略**
- UI自动化位于测试金字塔顶端，维护成本最高，聚焦"用户旅程关键路径"（占比<15%全部用例）
- ROI公式：自动化价值 = (手工执行次数 × 单次时间 × 人力成本) / (开发成本 + 维护成本)
- 优先自动化：高频回归路径 / 跨环境部署必验路径 / 发布阻断路径
- 不自动化：一次性验证 / 频繁变更的实验性功能 / 复杂人类判断场景

**B. Web UI框架选型**
- Playwright（2026新项目首选，采用率43%）：
  - 自动等待（无需显式sleep）
  - Codegen录制生成代码
  - Trace Viewer可视化调试
  - 原生并行执行（多worker）
  - 支持Chromium/Firefox/WebKit三引擎
- Selenium 4.x（遗留系统维护）：CDP原生支持、BiDi协议、Selenium Manager自动驱动管理
- Cypress（企业级测试管理）：跨Tab/跨域限制仍是短板
- WebDriver BiDi（W3C新标准）：统一底层协议，Playwright/Selenium未来将兼容

**C. 移动端UI框架选型**
- Appium 2.x：插件架构 + W3C WebDriver协议统一iOS/Android接口，跨平台复用率70%+
- 原生框架：XCUITest（Xcode 16 AI辅助）/ Espresso + Compose Test（Android组件化测试）
- 云真机平台：Sauce Labs / BrowserStack / Testin / WeTest（78%企业采用）
- 跨平台架构：统一抽象层（业务逻辑）+ 平台适配层（元素定位/手势），三端复用

**D. 元素定位策略（稳定性优先）**
- 第一优先级：data-testid（开发植入，最稳定，不受UI变更影响）
- 第二优先级：视觉语义定位（Playwright 1.50+ / Applitools AI识别，维护成本降低58%）
- 第三优先级：ARIA角色/标签（无障碍友好，UI结构调整仍有效）
- 兜底策略：相对XPath / CSS Selector（受UI变更影响最大，需频繁维护）
- 混合策略：data-testid优先 → 视觉语义 fallback → 相对XPath兜底

**E. 自愈合测试（2026覆盖率预计65%）**
- 五级自愈层次：
  1. 属性fallback：data-testid失效时尝试name/role/label
  2. 相对位置修复：基于相邻稳定元素重新计算目标位置
  3. 视觉语义匹配：AI识别UI截图中的目标元素（Applitools/Testim/mabl）
  4. 上下文推理：基于页面结构和业务语义推断目标元素
  5. 自主重生成：LLM基于用例描述和当前UI截图重新生成定位代码
- 工具：Mabl / Testim / Healenium / 自研AI定位层
- 价值：定位器相关失败减少73%，Flaky Test比例控制在≤2%

**F. 视觉AI与跨浏览器测试**
- 视觉大模型（VLM）验证：GPT-4V / Claude 3 Opus "看懂"UI截图验证布局正确性
- 跨浏览器帕累托策略：
  - Tier 1（覆盖80%用户）：Chrome，每次提交执行
  - Tier 2（覆盖95%用户）：+ Safari移动端 / Firefox，每小时执行
  - Tier 3（覆盖99%用户）：+ Edge / 折叠屏 / 车机屏，每月执行
  - Tier 4（覆盖99.5%用户）：+ 小众浏览器，按需执行
- 工具：Playwright原生支持三引擎，Selenium Grid + Docker跨浏览器并行

**G. 低代码与自然语言测试**
- 低代码平台：Tricentis Tosca（企业级）/ mabl（AI-first）/ Katalon（双模式）/ MeterSphere（国产开源）
- 自然语言生成："测试用户登录后添加商品到购物车并结算"→自动生成Playwright代码，准确率92%
- 适用场景：业务人员参与测试设计、快速原型验证、非核心路径补充覆盖

**H. 失败分析与报告**
- 智能失败分类（ML）：应用缺陷 / 脚本问题 / 环境问题 / Flaky Test，准确率85%+
- Playwright Trace Viewer：可视化重放每一步，诊断时间从20分钟降至3分钟
- 报告标准：Allure（跨语言）/ Report Portal（实时聚合和趋势分析）
- 关键KPI：通过率>95%、Flaky Rate<2%、MTTR<4h、核心流程执行<15min

**I. CI/CD集成**
- 分层执行：提交（1分钟核心冒烟）→ PR（5分钟模块级）→ 全量回归（30-60分钟 nightly）
- AI测试影响分析（Launchable / Appsurify）：基于代码变更预测受影响用例，减少回归执行量60-80%
- Flaky Test治理：首次失败自动重试 → 连续3次失败移入专项修复队列
- Mock服务集成：MSW浏览器级拦截，"Mock 90%，集成10%"是2026年最佳实践

**输出物**: `UI自动化测试框架_项目名称/`（含POM页面对象、视觉AI配置、自愈合层、跨浏览器矩阵、CI流水线脚本）。

**[参考: Agents知识库/0_超级编程行业知识库/软件测试与质量保障/自动化测试.md > UI自动化测试]`
**[参考: Agents知识库/0_超级编程行业知识库/软件测试与质量保障.md > UI自动化测试]`

---

### Step 3: 性能测试与压测方案

**Goal**: 建立性能测试体系，完成负载测试、压力测试、全链路压测和容量规划，确保系统在高负载下满足SLA。
**Completion criterion**: 性能基线建立完成，关键链路P99延迟达标，容量规划报告产出，全链路压测通过。

**A. 性能测试类型与指标定义**
- 六大测试类型：
  - 负载测试：验证系统在预期负载下的表现
  - 压力测试：找到系统性能拐点（崩溃/降级/恢复点）
  - 耐力测试：长时间运行检测内存泄漏/连接池耗尽
  - 尖峰测试：突发流量验证弹性扩容
  - 容量测试：确定系统最大承载能力
  - 混沌测试：故障注入验证韧性
- 核心指标：
  - P50/P95/P99 响应时间（RT）
  - QPS/TPS 吞吐量
  - 错误率（目标<0.1%）
  - CPU/内存/IO/网络利用率
  - 绿色性能：每请求碳排放（SCI标准）

**B. 工具选型**
- k6（2026新项目首选，采用率42%）：JavaScript脚本、GitOps原生、云原生、 thresholds即代码
- JMeter 5.6+（Java生态/复杂协议）：DSL代码方式编写、插件生态丰富、合规认证强
- LoadRunner（企业级）：50+协议支持、合规认证、高成本
- 国产工具：阿里云PTS（AI智能压测）/ 华为云CPTS（千万级并发）/ XRunner（信创）
- 浏览器级：k6 xk6-browser测量Core Web Vitals（LCP/INP/CLS）

**C. 负载模型与场景设计**
- 六级数据建模：
  1. 静态：固定参数
  2. 参数化：变量替换（用户ID/商品ID）
  3. 动态生成：运行时生成随机数据
  4. 统计分布：正态/泊松/Zipf分布模拟真实流量（20%内容获80%访问）
  5. AI合成：LLM生成符合业务规则的真实行为流
  6. 真实流量回放：GoReplay + 脱敏变换，保持流量特征保护隐私
- 负载曲线模式：
  - 逐步爬坡：探测容量上限
  - 突发尖峰：验证弹性扩容速度
  - 波浪负载：验证稳定性
  - 真实回放：基于生产日志训练的负载模型

**D. 性能瓶颈分析**
- AI辅助根因分析：Dynatrace Davis / Datadog Watchdog生成自然语言根因报告
- eBPF无侵入分析：Pixie / Grafana Beyla / SkyWalking，零代码改动获取性能数据
- Continuous Profiling：Pyroscope / Parca支持7×24h不间断CPU和内存剖析
- 四维定位法：
  - CPU：火焰图定位热点函数
  - 内存：jmap + GC日志分析泄漏/频繁GC
  - IO：iostat/blktrace定位磁盘瓶颈
  - 网络：tcpdump/wireshark定位延迟/丢包
- 数据库调优：EXPLAIN分析 + 慢查询日志 + AI推荐索引（PgHero / MySQLTuner）

**E. 全链路压测与生产演练**
- 核心技术：流量复制（GoReplay / Nginx Mirror）+ 影子库（Shadow DB）+ 全链路追踪（OpenTelemetry）
- 压测链路：流量入口 → API网关 → 微服务A → 微服务B → 数据库 → 缓存 → 消息队列
- 安全实践：
  - 自动熔断：错误率>5%或P99>3倍基线自动停止
  - 低峰期执行：避开业务高峰
  - 运维值守：压测期间SRE全程监控
  - 红色按钮：一键停止所有压测流量
- 生产演练：5%生产流量镜像 + 20倍放大，验证真实环境表现
- 混沌工程融合：Litmus / Chaos Mesh在压测期间注入故障（Pod kill / 网络延迟 / 数据库故障）

**F. CI/CD中的性能回归检测**
- 性能即代码（Performance as Code）：k6脚本 + SLO阈值全部Git管理
- 三级性能门禁：
  - L1提交：3分钟单用户基线（核心接口RT < 200ms）
  - L2合并：10分钟100并发（吞吐量不低于基线95%）
  - L3发布：30分钟全链路（P99 < 500ms，错误率<0.1%）
- A/B性能测试：蓝绿/金丝雀部署中对比新旧版本实际性能，退化>5%则阻断发布

**G. 容量规划**
- USL建模（通用可扩展性定律）：量化理论最大吞吐量、最优并发点、扩容系数
- AI容量预测：Prophet / LSTM预测未来3-6个月容量需求，准确率85%+
- 容量公式：所需容量 = 预测业务量 × 安全系数（1.3）× 灾备冗余（1.5）
- 实时仪表盘：Grafana k6 App替代静态文档，实时观测压测指标

**输出物**: `性能测试体系_项目名称/`（含k6/JMeter脚本、负载模型、瓶颈分析报告、容量规划报告、CI性能门禁配置）。

**[参考: Agents知识库/0_超级编程行业知识库/软件测试与质量保障/自动化测试.md > 性能测试与压测方案]`
**[参考: Agents知识库/0_超级编程行业知识库/软件测试与质量保障.md > 性能测试与压测]`

---

## Post-Workflow

1. Read `checklist/automated_testing_workflow_checklist.md`.
2. Cross-validate every output against every checklist item.
3. Only proceed to the next workflow or notify the user of completion after all checklist items pass.
