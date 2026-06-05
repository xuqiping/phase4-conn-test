# Testing and Quality Assurance Workflow

## Purpose

基于测试与质量保障知识框架，提供从测试策略到质量度量的标准化执行路径，覆盖测试策略模型选型、四级测试体系、自动化测试工程化、性能压测全链路、质量度量与DORA指标五大核心能力。

## Prerequisites

- 已确定系统架构类型（单体/微服务/前端应用/全栈）
- 已确定测试团队规模与自动化成熟度

## Steps

### Step 1: 测试策略模型选型

**Goal**: 根据系统架构与业务特征选择最优测试策略模型，建立测试投入分配基线
**Completion criterion**: 输出《测试策略决策记录》，明确测试金字塔形状与各层比例，风险驱动测试（RBT）覆盖核心业务流程

依据测试策略模型框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 测试策略模型选型]：
1. 五种测试模型对比：
   - 金字塔模型（后端单体）：单元70%/集成20%/E2E 10%
   - 钻石模型（微服务）：API层最厚（单元30%/API集成50%/E2E 20%）
   - 奖杯模型（前端）：集成最厚+静态分析（静态20%/单元30%/集成40%/E2E 10%）
   - 蜂巢模型（金融/军工）：各层均衡（单元25%/集成25%/系统25%/E2E 25%）
   - 轻量金字塔（创业MVP）：核心逻辑单元+关键路径E2E（单元50%/E2E 50%）
2. 2026年推荐API-First Pyramid：
   - API层70%（Contract Testing + API集成测试）
   - 单元20%（核心业务逻辑）
   - UI层10%（关键用户旅程）
3. 风险驱动测试（RBT）：
   - 风险 = 概率 × 影响
   - 高风险区域分配更多测试资源
   - AI辅助风险评估2025年落地
4. 敏捷测试四象限（Lisa Crispin）：
   - Q1技术-支持团队：单元测试/组件测试
   - Q2业务-支持团队：API测试/Story测试
   - Q3业务-评价产品：UAT/探索性测试/Demo
   - Q4技术-评价产品：性能/安全/兼容性/可靠性
执行：
- 输出《测试策略决策记录》（ADR格式）：选型理由 + 各层比例 + 关键业务覆盖
- 绘制《测试金字塔图》：当前状态 vs 目标状态
- 定义《风险登记册》：业务模块 + 风险等级 + 测试投入

### Step 2: 四级测试体系建立

**Goal**: 建立单元测试→集成测试→系统测试→验收测试的四级测试体系，每层有明确的进入/退出标准
**Completion criterion**: 四级测试体系运行正常，单元测试覆盖率≥80%，集成测试通过率100%，CI/CD分层触发配置完成

依据四级测试体系框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 四级测试体系]：
1. 单元测试：
   - 方法论：TDD红-绿-重构 / FIRST原则（Fast/Independent/Repeatable/Self-validating/Timely）
   - 2026年趋势：AI辅助生成单元测试（准确率80%+）
   - 工具：Vitest取代Jest成为Vite生态首选，pytest（Python），JUnit（Java）
   - 覆盖率目标：核心业务逻辑≥80%，边界条件全覆盖
2. 集成测试：
   - 革命性变化：Testcontainers自动启动真实数据库/MQ容器替代Mock
   - Contract Testing（Pact）：从"小众"变"标配"，消费者驱动契约
   - API集成测试：Postman Collection/Newman CI集成
3. 系统测试（E2E）：
   - Playwright全面超越Selenium成首选（新项目采用率65%）
   - AI Self-Healing Tests：维护成本降40-60%
   - 覆盖：关键用户旅程（注册→登录→核心操作→退出）
4. 验收测试（UAT）：
   - 由业务用户执行，发现"技术正确但业务不合理"的问题
   - 只有业务用户能识别真实业务场景中的缺陷
   - 验收标准：依据PRD中的BDD Gherkin验收条件逐条验证
5. CI/CD分层触发：
   - PR验证：<5分钟（单元测试+静态扫描+关键路径E2E）
   - 合并验证：<15分钟（全量单元+集成测试）
   - Nightly：<60分钟（全量E2E+性能基准+安全扫描）
执行：
- 配置CI Pipeline分层触发规则
- 输出《测试准入/准出标准》：每级的进入条件 + 通过标准 + 退出标准
- 部署Testcontainers环境（Docker Compose配置）

### Step 3: 自动化测试工程化

**Goal**: 建立工程化的自动化测试体系，降低Flaky Test率，提升测试可维护性
**Completion criterion**: 自动化测试套件稳定运行（Flaky Rate<2%），Page Object Model覆盖全部E2E页面，测试数据管理策略明确

依据自动化测试工程化框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 自动化测试工程化]：
1. Playwright工程化：
   - 多浏览器支持（Chromium/Firefox/WebKit）
   - 自动等待（Auto-waiting）消除显式sleep
   - Trace Viewer：失败时自动录制操作轨迹
   - 并行执行：shards分片执行加速
2. Page Object Model（POM）：
   - 每个页面对应一个Page Class
   - 封装页面元素定位与操作方法
   - 页面改版时仅需修改一处定位器
3. Flaky Test治理：
   - 根因分类：等待不足35%/数据竞争20%/环境不稳定15%/时序问题10%/其他20%
   - 四步治理：检测（连续运行10次）→ 隔离（标记@flaky单独运行）→ 修复（根因消除）→ 预防（等待策略/数据隔离）
   - Flaky Rate纳入度量目标：<2%
4. 测试数据管理：
   - 策略1：每个测试前创建数据，测试后清理（Setup/Teardown）
   - 策略2：共享测试数据集（Test Fixture），只读不修改
   - 策略3：数据库快照（Snapshot），测试前恢复到已知状态
执行：
- 输出《自动化测试工程化规范》：目录结构 + POM设计 + 测试数据策略 + CI集成
- 建立Flaky Test Registry：记录每个Flaky Test的根因+修复状态+验证结果
- 配置Playwright Reporter：HTML报告 + Slack通知 + 失败Trace自动上传

### Step 4: 性能压测全链路

**Goal**: 建立覆盖六大性能测试类型的全链路压测体系，支撑容量规划与性能优化
**Completion criterion**: 输出《性能测试计划》，k6/Grafana已部署，全链路压测通过，关键指标达到设计目标

依据性能测试框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 性能压测全链路]：
1. 六大性能测试类型：
   - 基准测试（Baseline）：单用户/单请求，建立性能基线
   - 负载测试（Load）：目标并发下持续运行，验证稳定性
   - 压力测试（Stress）：超出目标并发，发现性能拐点
   - 稳定性测试（Soak）：长时间运行（7×24小时），发现内存泄漏/连接泄漏
   - 峰值测试（Spike）：瞬间高并发冲击，验证弹性扩容
   - 容量测试（Capacity）：逐步增加负载直到系统崩溃，确定容量上限
2. k6全链路压测：
   - JS脚本编写测试场景
   - CI原生集成（k6 run in Docker）
   - Grafana可视化（k6 Cloud或自建InfluxDB+Grafana）
   - 全链路压测核心技术：影子库 + 流量标记透传 + MQ隔离
3. 持续性能测试三层次：
   - PR级：10-50 VU，1-5分钟，核心API响应时间
   - 每日级：100-500 VU，关键业务流程
   - 版本级：目标负载，全量场景，发布前必须通过
4. 性能指标基线：
   - 响应时间：P50/P95/P99，P99目标值
   - 吞吐量：QPS/TPS，目标值
   - 资源利用率：CPU<70%/内存<80%/连接池<80%
执行：
- 输出《性能测试计划》：测试类型 + 场景 + 指标 + 通过标准
- 编写k6测试脚本：核心API + 关键业务流程 + 数据驱动（CSV/JSON）
- 配置Grafana Dashboard：实时压测指标监控
- 输出《性能基线报告》：当前性能数据 + 瓶颈分析 + 优化建议

### Step 5: 质量度量与DORA指标

**Goal**: 建立质量度量体系，基于DORA与SPACE框架驱动持续改进
**Completion criterion**: DORA四大指标可自动采集，度量Dashboard上线，团队理解"度量用于改进而非考核"

依据质量度量框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 质量度量与DORA指标]：
1. DORA四大指标：
   - 部署频率（Deployment Frequency）：精英级→每天多次，高级→每周一次，中级→每月一次，低级→每季度一次
   - 变更前置时间（Lead Time for Changes）：精英级→<1小时，高级→<1周，中级→<1月，低级→1-6月
   - 变更失败率（Change Failure Rate）：精英级→<5%，高级→<15%，中级→<20%，低级→>45%
   - 恢复服务时间（Time to Restore Service）：精英级→<1小时，高级→<1天，中级→<1周，低级→>1月
2. SPACE框架（开发者体验）：
   - S：满意度与幸福感（Satisfaction and Well-being）
   - P：绩效（Performance）
   - A：活动（Activity）
   - C：沟通与协作（Communication and Collaboration）
   - E：效率与流程（Efficiency and Flow）
3. 度量原则：
   - 古德哈特定律防范：指标一旦成为考核目标，就会失真
   - 度量用于：发现问题、指导改进、验证假设
   - 不用于：排名考核、惩罚依据、个人绩效
4. 度量Dashboard：
   - 数据源：CI/CD流水线（Jenkins/GitHub Actions）、监控系统（Prometheus）、项目管理（Jira）
   - 展示：Grafana/自定义Dashboard
   - 更新频率：实时（部署频率）/ 每日（前置时间）/ 每周（失败率）/ 按需（恢复时间）
执行：
- 配置DORA指标自动采集：Git Webhook记录部署事件，CI记录构建时间，Incident记录恢复时间
- 输出《质量度量手册》：指标定义 + 采集方式 + 目标值 + 当前值 + 改进行动
- 建立月度度量回顾会议：审视趋势 + 识别瓶颈 + 制定改进项

## Post-Workflow

1. 读取 `checklist/testing_quality_workflow_checklist.md`
2. 交叉验证每个测试覆盖率指标、每次压测结果、每项DORA数据
3. 全部通过后输出《测试与质量保障总纲》并归档
