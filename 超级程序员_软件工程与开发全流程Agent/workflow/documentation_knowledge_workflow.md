# Documentation and Knowledge Management Workflow

## Purpose

基于技术文档与知识管理知识框架，提供从文档体系到知识沉淀的标准化执行路径，覆盖四层文档体系、Docs as Code实践、API文档与开发者门户、SECI知识沉淀、Runbook标准化运维五大核心能力。

## Prerequisites

- 已确定文档受众（开发者/架构师/运维/产品经理/外部用户）
- 已确定文档平台（Confluence/GitBook/Docusaurus/自研）

## Steps

### Step 1: 四层技术文档体系建设

**Goal**: 建立L1-L4分层文档体系，每类文档有明确受众、更新频率与责任人
**Completion criterion**: 四层文档体系已定义，ADR目录≥5个，arc12模板已配置，文档覆盖率（核心模块有文档）≥90%

依据四层文档框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 技术文档与知识管理]：
1. L1 战略层（技术愿景/架构蓝图）：
   - 受众：CTO/架构师/技术委员会
   - 内容：技术战略/架构原则/技术雷达/演进路线图
   - 更新频率：年度
   - 模板：技术愿景宣言 + 架构原则清单 + 技术栈决策矩阵
2. L2 设计层（ADR/系统设计/API设计）：
   - 受众：架构师/高级开发
   - 内容：ADR决策记录/系统设计说明书/API设计文档
   - 更新频率：随架构变更
   - 模板：ADR标准模板 + arc42 12个标准章节 + C4四层模型
3. L3 实现层（API文档/代码文档/测试文档）：
   - 受众：开发者/测试
   - 内容：API参考/代码注释/测试用例说明
   - 更新频率：随代码变更
   - 模板：OpenAPI Spec + Javadoc/JavaDoc + 测试用例描述
4. L4 运维层（Runbook/SOP/故障手册）：
   - 受众：SRE/运维/值班人员
   - 内容：操作手册/故障排查/应急预案/备份恢复
   - 更新频率：季度
   - 模板：Runbook标准格式（见Step 5）
5. arc42架构文档12个章节：
   - 简介与目标/约束/上下文与范围/解决方案策略/构建块视图/运行时视图/部署视图/跨领域概念/架构决策/质量需求/风险/术语表
6. C4模型四层：
   - C1 Context：系统与外部关系
   - C2 Container：应用/数据库/消息队列容器
   - C3 Component：容器内部组件
   - C4 Code：类/接口级别（可选，通常由IDE自动生成）
执行：
- 输出《文档体系规范》：四层定义 + 模板 + 评审流程 + 维护责任
- 建立ADR目录：`docs/adr/`，编号格式 `0001-决策标题.md`
- 配置Structurizr DSL绘制C4模型
- 输出《文档覆盖率报告》：已文档化模块 / 全部模块

### Step 2: Docs as Code实践

**Goal**: 建立Docs as Code工作流，实现文档与代码同生命周期管理
**Completion criterion**: 文档仓库与代码仓库关联（同仓库或子模块），CI/CD文档流水线运行，pre-commit文档检查生效

依据Docs as Code框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > Docs as Code实践]：
1. 五大原则：
   - 文档与代码同生命周期：同仓库或子模块，同PR同评审
   - 纯文本优先：Markdown/AsciiDoc/ReStructuredText，可diff可merge
   - 审查即质量控制：PR评审包含文档变更审查
   - 自动化一切：lint/build/deploy全部自动化
   - 可组合可复用：片段化内容，多处引用不重复
2. 静态站生成器选型：
   - Docusaurus 3.x：React/MDX，适合开源项目/技术博客
   - MkDocs Material 9.x：Python/极速，适合创业团队快速起步
   - Antora 3.x：AsciiDoc/Java企业级多组件
   - VitePress 1.x：Vue/极速，适合Vue生态项目
3. CI/CD文档流水线：
   - pre-commit：markdownlint + prettier + cspell（拼写检查）
   - PR阶段：构建测试 + lychee链接检查 + vale风格检查 + Deploy Preview
   - 合并后：全量构建 → 部署CDN → Algolia索引更新
4. 文档质量检查：
   - 链接有效性：lychee扫描死链
   - 风格一致性：vale或markdownlint规则
   - 拼写检查：cspell自定义词典
   - 图片压缩：imagemin自动优化
执行：
- 输出《Docs as Code实践指南》：工具选型 + 目录结构 + CI配置 + 写作规范
- 配置文档CI Pipeline：.github/workflows/docs.yml
- 配置pre-commit钩子：文档文件自动格式化+检查

### Step 3: API文档与开发者门户

**Goal**: 建立API产品化文档体系，打造开发者门户
**Completion criterion**: API文档P0要素齐全，OpenAPI自动化工具链运行，SDK自动生成≥3语言，开发者门户上线

依据API文档框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > API文档与开发者门户]：
1. API文档P0要素：
   - 认证说明：OAuth2/API Key/JWT获取方式
   - 请求格式：Base URL + Header + Body示例
   - 响应格式：成功响应 + 错误响应（所有错误码）
   - 参数说明：路径参数/查询参数/Body字段/类型/必填/示例
   - 代码示例：至少3种语言（cURL/Python/Java）
   - 快速开始指南：5分钟内完成首个API调用
2. OpenAPI自动化工具链：
   - Spec编写：Swagger Editor / IntelliJ插件 / 手写YAML
   - 文档生成：Swagger UI / Redoc / Stoplight Elements
   - 客户端SDK生成：OpenAPI Generator（50+语言）
   - 服务端Stub生成：快速启动新项目
   - Mock服务：Prism / MockServer，前端并行开发
   - 测试生成：Dredd / Schemathesis，基于Spec自动测试
3. Design-First vs Code-First：
   - Design-First（推荐）：先写OpenAPI Spec → 评审 → 生成代码Stub → 实现
   - Code-First：先写代码 → 注解生成Spec → 风险：注解不准确导致文档失真
4. 开发者门户：
   - 功能：API目录 / 文档 / 调试台（Try it out）/ SDK下载 / 示例代码 / 社区论坛
   - 平台：ReadMe / Stoplight / Postman Public Workspace / 自研
   - 运营：版本更新日志 / 迁移指南 / 弃用通知（Sunset Header）
执行：
- 输出《API文档标准》：P0要素清单 + OpenAPI规范 + 示例模板
- 配置OpenAPI Generator：生成Python/Java/Go/TypeScript SDK
- 部署开发者门户：集成Swagger UI + Redoc + 调试台
- 输出《API版本管理策略》：URL路径版本 + Sunset Header + oasdiff CI检测

### Step 4: SECI知识沉淀与团队知识管理

**Goal**: 建立基于SECI模型的团队知识管理体系，降低巴士因子，提升新人上手效率
**Completion criterion**: SECI模型四阶段运行，巴士因子红色警报（<3）已识别，知识贡献纳入绩效考核（≥10%权重），RAG知识库已部署

依据SECI知识管理框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > SECI知识沉淀]：
1. SECI四阶段模型：
   - 社会化（Socialization）：隐性知识→隐性知识，导师制/结对编程/ Brown Bag午餐分享
   - 外显化（Externalization）：隐性知识→显性知识，写文档/画架构图/录视频/Post-Mortem
   - 组合化（Combination）：显性知识→显性知识，知识库整合/FAQ汇总/最佳实践手册
   - 内化（Internalization）：显性知识→隐性知识，培训/实践/复盘，新人30/60/90天计划
2. 巴士因子（Bus Factor）：
   - 定义：核心模块活跃贡献者数<3为红色警报
   - 监控：Git统计每个模块的贡献者数
   - 改进：强制Code Review要求非Owner参与 / 轮岗制度 / 文档化关键知识
3. 知识半衰期：
   - 技术知识半衰期约6-12个月
   - 应对：定期更新文档/技术分享会/外部培训/技术雷达刷新
4. 知识管理成熟度模型L1-L5：
   - L1个人英雄：知识在少数人脑中
   - L2文档化：有文档但零散
   - L3系统化：知识库+分类+搜索
   - L4流程化：知识沉淀融入工作流程
   - L5自进化：AI辅助生成/维护/问答
5. 知识贡献激励：
   - 纳入绩效考核权重≥10%
   - 知识之星评选（月度/季度）
   - 内部技术大会演讲机会
6. RAG知识库：
   - 新人问题50%由AI回答
   - 向量数据库（Pinecone/Milvus/自研）存储文档Embedding
   - LLM + RAG回答技术问题，引用来源
执行：
- 输出《知识管理策略》：SECI实施路径 + 巴士因子监控 + 知识贡献激励
- 部署RAG知识库：上传全部技术文档 → 切分Chunk → 生成Embedding → LLM问答
- 建立知识分享机制：每周Tech Talk + 每月架构Review + 季度技术大会
- 输出《新人上手指南》（见Step 5）

### Step 5: Runbook标准化与运维SOP

**Goal**: 建立标准化的Runbook体系，将MTTR从45分钟降至12分钟
**Completion criterion**: Runbook覆盖Top 20运维场景，标准化Runbook体系已实施，操作失误率从5%降至0.3%

依据Runbook框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > Runbook标准化运维]：
1. Runbook四层架构：
   - 模板层：标准格式（目的/前置条件/步骤/验证/回滚/关联）
   - 存储层：集中存储（Confluence/GitBook/自研Runbook平台）
   - 执行层：可执行Runbook（Python/Shell脚本 + 人工确认节点）
   - 度量层：执行时间/成功率/改进项跟踪
2. 三级Runbook体系：
   - 例行操作：每日检查/每周备份/每月巡检/每季度审计
   - 标准变更：配置变更/版本升级/资源扩缩容/证书更新
   - 紧急响应：P1/P2故障响应/安全事件/灾难恢复
3. 故障排查Runbook标准格式：
   - 诊断决策树：if-else结构，每步含检查命令+预期输出+异常处理分支
   - 分级处理：自助修复（L1）→ 专家介入（L2）→ 厂商支持（L3）
   - 每步含：命令/脚本 + 预期输出 + 判断条件 + 下一跳
4. 可执行Runbook：
   - 半自动化：脚本执行+人工确认（"是否继续？Y/N"）
   - 全自动化：无人值守，触发条件→自动执行→自动验证→通知结果
   - 工具：Rundeck/Ansible Tower/自研Runbook引擎
5. 度量与改进：
   - 标准化Runbook体系可将MTTR从45min降至12min
   - 操作失误率从5%降至0.3%
   - 每季度Review Runbook有效性，更新过时内容
执行：
- 输出《Runbook编写规范》：模板 + 分级 + 决策树格式 + 可执行化标准
- 编写Top 20运维场景Runbook：
  - 例行：日志清理/备份验证/证书过期检查/容量巡检
  - 变更：数据库DDL/配置热更新/金丝雀发布/回滚操作
  - 紧急：服务宕机/数据库主从切换/缓存雪崩/网络分区/安全事件
- 配置Runbook执行平台：半自动化/全自动化执行能力
- 输出《Runbook度量报告》：执行次数/平均时间/成功率/改进项

## Post-Workflow

1. 读取 `checklist/documentation_knowledge_workflow_checklist.md`
2. 交叉验证每个ADR文件、每份API文档、每个Runbook步骤
3. 全部通过后输出《技术文档与知识管理总纲》并归档
