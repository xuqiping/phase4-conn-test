# System Design and Technology Selection Workflow

## Purpose

基于系统设计与技术选型知识框架，提供从领域建模到技术决策的标准化执行路径，覆盖DDD领域驱动设计、ADR架构决策、技术选型评估矩阵、数据库建模范式、API First设计、UI/UX设计系统、HEART体验度量七大核心能力。

## Prerequisites

- 已明确系统规模与复杂度（简单CRUD/中等业务系统/复杂分布式系统）
- 已确定团队规模与技术储备

## Steps

### Step 1: DDD战略设计与事件风暴

**Goal**: 通过DDD战略设计建立统一语言，划分限界上下文，完成事件风暴工作坊
**Completion criterion**: 输出《领域模型图》与《限界上下文映射》，统一语言术语表≥30个术语，限界上下文数量2-7个

依据DDD战略设计框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 系统设计与技术选型]：
1. 统一语言（Ubiquitous Language）：
   - 与业务专家共创术语表，每个术语有定义+使用场景+反例
   - 禁止同一概念有多个名称（如"订单"和"合同"不能混用）
   - 输出《术语表》：术语 + 英文名称 + 定义 + 同义词（禁止使用的） + 使用上下文
2. 限界上下文（Bounded Context）划分：
   - 合适粒度：2-7个聚合 per Context，过少则粒度粗，过多则管理复杂
   - 划分依据：业务边界（不同业务规则）/ 团队边界（康威定律）/ 技术边界（不同技术栈）
   - 输出《限界上下文图》：Context名称 + 核心聚合 + 团队归属
3. 上下文映射：
   - 防腐层（ACL）：保护本Context不受外部模型污染
   - 开放主机服务（OHS）：对外提供标准化服务接口
   - 发布语言（PL）：定义共享数据格式
   - 合作关系（Partnership）：两个Context紧密协作
   - 共享内核（Shared Kernel）：提取公共子域共用
   - 客户-供应商（Customer-Supplier）：明确上下游依赖
4. 事件风暴五阶段（总时长8-14小时）：
   - 阶段1：混乱探索（贴出所有领域事件，无过滤）
   - 阶段2：排序与聚类（按时间线排列，发现流程断点）
   - 阶段3：识别聚合（将相关事件归入同一聚合）
   - 阶段4：划定限界上下文（按聚合归属划分边界）
   - 阶段5：定义关系（ACL/OHS/PL等映射关系）
执行：
- 组织事件风暴工作坊（参与者：业务专家+架构师+开发代表+测试代表）
- 输出《领域模型图》（Event Storming结果图）
- 输出《限界上下文映射图》（Context Map）
- 输出《统一语言术语表》

### Step 2: DDD战术设计与架构风格选型

**Goal**: 完成DDD战术设计，选定系统架构风格，输出技术架构蓝图
**Completion criterion**: 输出《系统架构设计书》，包含分层架构图、实体关系图、CQRS/ES决策记录

依据DDD战术设计框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > DDD战术设计]：
1. 战术设计元素：
   - 实体（Entity）：有唯一标识，状态可变
   - 值对象（Value Object）：无唯一标识，不可变
   - 聚合根（Aggregate Root）：封装一组实体和值对象，事务边界
   - 领域服务（Domain Service）：跨实体的业务逻辑
   - 领域事件（Domain Event）：记录已发生的事实
   - 仓储（Repository）：聚合根的持久化抽象
2. DDD分层架构：
   - 用户接口层（Presentation）：API/CLI/Web/消息适配
   - 应用层（Application）：用例编排，无业务逻辑
   - 领域层（Domain）：核心业务逻辑，最纯净
   - 基础设施层（Infrastructure）：数据库/消息队列/外部服务
3. CQRS+ES决策：
   - CQRS适用场景：读模型与写模型差异大/查询性能要求高/事件溯源需要
   - ES（Event Sourcing）适用场景：审计需求强/需要状态重建/事件驱动架构
   - 输出《CQRS+ES决策记录》：决策 + 理由 + 影响
4. 架构风格选型核心原则：
   - 团队<15人：用模块化单体，不要追微服务
   - 团队15-50人：考虑微服务，但先验证单体是否真成为瓶颈
   - 团队>50人：微服务+中台，前提是有DevOps成熟度
执行：
- 输出《系统架构设计书》：分层架构图 + 模块依赖图 + 数据流图
- 输出《实体关系图》（ER图）：实体 + 关系 + 聚合边界
- 输出《API契约初稿》：基于限界上下文的API列表

### Step 3: ADR架构决策记录

**Goal**: 建立ADR体系，记录所有关键架构决策过程，确保决策可追溯、可审计
**Completion criterion**: 输出《ADR目录》，包含≥5个关键决策记录，格式符合MADR 3.x标准

依据ADR框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > ADR架构决策]：
1. MADR 3.x标准格式：
   - 标题：简短描述决策内容
   - 状态：Proposed → Accepted → Deprecated → Superseded（不可修改，只能覆盖）
   - 背景：问题陈述 + 约束条件
   - 决策：选定的方案
   - 备选方案：至少2个备选 + 优缺点分析
   - 后果：正面影响 + 负面影响 + 风险
2. 必须记录的关键决策：
   - 架构风格（单体/微服务/Serverless）
   - 编程语言与框架
   - 数据库选型（SQL/NoSQL/NewSQL）
   - 消息传递（同步REST/异步消息队列/Event Sourcing）
   - 部署策略（容器/K8s/Serverless/VM）
3. ADR生命周期管理：
   - 存储位置：`docs/adr/` 目录，编号 `0001-choose-database.md`
   - 评审流程：编写 → 架构师评审 → 技术委员会审批 → 发布
   - 废弃流程：新ADR引用旧ADR并标记Superseded，不删除旧ADR
4. 架构视图演进：
   - 从4+1模型演进到C4模型（Context-Container-Component-Code）
   - Structurizr DSL实现"架构图即代码"
执行：
- 输出《ADR目录》：按编号排序，包含所有关键决策的索引
- 每个ADR文件符合MADR 3.x格式
- 使用Structurizr DSL绘制C4模型，纳入版本控制

### Step 4: 技术选型评估矩阵

**Goal**: 基于多维度评估矩阵完成关键技术选型，确保选型有据可查、可逆可退
**Completion criterion**: 输出《技术选型评估报告》，包含≥3项关键技术的评估矩阵，每项有POC验证结论

依据技术选型五步法 [参考: Agents知识库/0_超级编程行业知识库/14_超级编程行业知识库/14_软件工程与开发全流程.md > 技术选型评估矩阵]：
1. 五步法：
   - Step 1：需求澄清（功能需求+非功能需求+约束条件）
   - Step 2：候选筛选（行业主流方案，至少3个候选）
   - Step 3：多维度评估（功能适配度25%/性能20%/运维成熟度15%/团队匹配度15%/生态活跃度10%/成本15%）
   - Step 4：POC验证（核心场景原型验证，产出《POC报告》）
   - Step 5：ADR记录（将选型决策归档为ADR）
2. 核心原则：
   - 适用性优先：不追新，选团队能驾驭的
   - 可逆性优先：选型错误时能在3个月内替换
3. 技术雷达：
   - 构建内部技术雷达，每季度评审更新
   - 四象限：Adopt（采用）/ Trial（试验）/ Assess（评估）/ Hold（暂缓）
执行：
- 输出《技术选型评估矩阵》：技术项 + 候选A/B/C + 各维度评分（1-5分）+ 加权总分 + POC结论
- 输出《POC验证报告》：验证目标 + 环境 + 测试用例 + 结果 + 结论
- 更新技术雷达图，标记新选型位置

### Step 5: 数据库设计与API First设计

**Goal**: 完成数据库建模范式设计与API First接口设计，确保数据层与接口层的高质量
**Completion criterion**: 输出《数据库设计说明书》与《API设计规范》，ER图覆盖全部业务实体，OpenAPI 3.x规范完整

依据数据库与API设计框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 数据库建模范式]：
1. 数据库设计：
   - ER三层模型：概念模型（实体关系）→ 逻辑模型（表结构）→ 物理模型（索引/分区/分片）
   - 范式策略：先满足3NF（消除传递依赖），再按需反范式（冗余字段提升查询性能）
   - 分库分表是最后手段：先优化索引 → 读写分离 → 缓存 → 分区表 → 最后分库分表
   - 输出《数据字典》：表名 + 字段名 + 类型 + 约束 + 说明 + 示例值
2. API First设计：
   - 2026年RESTful占70%+份额，gRPC微服务内部通信延迟低77%
   - OpenAPI 3.2引入SSE流式支持
   - Design-First（先规范再编码）成为新项目首选
   - API文档P0要素：认证说明/请求格式/响应格式/参数说明/代码示例（至少3语言）/快速开始指南
   - 版本管理：URL路径版本（/v1/ /v2/）+ Sunset Header（RFC 8594）+ oasdiff CI检测Breaking Change
3. API设计规范：
   - RESTful：资源URI + HTTP方法语义 + 状态码规范 + HATEOAS（可选）
   - gRPC：Protobuf定义 + 服务接口 + 流式支持
   - GraphQL：Schema定义 + Resolver + 查询优化（N+1问题）
执行：
- 输出《数据库设计说明书》：ER图 + 表结构 + 索引设计 + 分区策略
- 输出《API设计规范》：URL规范 + 请求/响应格式 + 错误码定义 + 版本策略
- 输出OpenAPI 3.x Spec文件（YAML/JSON）

### Step 6: UI/UX设计系统与HEART度量

**Goal**: 建立UI/UX设计系统，定义用户体验度量指标体系
**Completion criterion**: 输出《设计系统规范》与《HEART度量报告》，设计Token到前端组件库1:1映射，NPS基准已建立

依据UI/UX与HEART框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > UI/UX设计系统]：
1. 设计系统层级：
   - Tokens（令牌）：颜色/字体/间距/圆角/阴影
   - Foundations（基础）：网格/布局/排版/图标
   - Components（组件）：Button/Input/Table/Modal/Card
   - Patterns（模式）：表单/列表/搜索/筛选/分页
   - Templates（模板）：页面骨架（Dashboard/Detail/List）
   - Pages（页面）：具体业务页面实例
2. 设计Token到前端组件库1:1映射：
   - Figma Variables → CSS Variables/Tailwind Config
   - 组件属性对齐：Figma变体 = React props/Vue props
   - 自动同步：Figma API + Style Dictionary → 代码库Token文件
3. HEART框架量化用户体验：
   - Happiness（愉悦度）：NPS评分/满意度评分/应用商店评分
   - Engagement（参与度）：DAU/MAU/使用时长/功能使用频率
   - Adoption（采用率）：新功能7日采用率/注册转化率/首次完成核心任务率
   - Retention（留存率）：次日/7日/30日留存/ cohort分析
   - Task Success（任务成功率）：核心任务完成率/错误率/任务完成时间
   - NPS行业基准：30-50良好，50-70优秀，>70卓越
执行：
- 输出《设计系统规范》：Token定义 + 组件库清单 + 使用规则 + 代码映射
- 输出《HEART度量指标库》：指标名称 + 计算公式 + 采集方式 + 目标值 + 当前值
- 建立NPS基线，每季度追踪变化

## Post-Workflow

1. 读取 `checklist/system_design_workflow_checklist.md`
2. 交叉验证每个限界上下文、每个ADR记录、每项技术评分
3. 全部通过后输出《系统设计与技术选型总纲》并归档
