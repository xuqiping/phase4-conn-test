# DDD 战略设计 + 事件风暴

## 1. 概述

本文档基于领域驱动设计（DDD）方法论，通过事件风暴（Event Storming）方法对多 Agent 智能体平台进行战略层面的领域分析。目标是识别核心领域事件、划分限界上下文、建立上下文映射关系，并统一领域语言。

---

## 2. 事件风暴结果

### 2.1 领域事件清单

按照业务流程梳理，共识别出 **34 个领域事件**：

#### 认证与授权领域

| 编号 | 领域事件 | 触发者 | 说明 |
|------|---------|--------|------|
| E01 | `UserRegistered` | 用户/管理员 | 新用户注册成功 |
| E02 | `UserLoggedIn` | 用户 | 用户登录成功，签发 JWT |
| E03 | `UserLoggedOut` | 用户 | 用户主动登出，Token 加入黑名单 |
| E04 | `TokenRefreshed` | 系统 | Refresh Token 换发新 Access Token |
| E05 | `RoleCreated` | 管理员 | 创建新角色 |
| E06 | `PermissionGranted` | 管理员 | 为角色授予权限 |
| E07 | `UserPasswordChanged` | 用户 | 用户修改密码 |

#### Agent 管理领域

| 编号 | 领域事件 | 触发者 | 说明 |
|------|---------|--------|------|
| E08 | `AgentCreated` | 管理员 | 创建新 Agent |
| E09 | `AgentUpdated` | 管理员 | 更新 Agent 配置信息 |
| E10 | `AgentDeleted` | 管理员 | 删除 Agent |
| E11 | `AgentPublished` | 管理员 | Agent 发布上线 |
| E12 | `AgentUnpublished` | 管理员 | Agent 下线 |
| E13 | `AgentGroupCreated` | 管理员 | 创建 Agent 分组 |
| E14 | `SkillCreated` | 管理员 | 为 Agent 创建新技能 |
| E15 | `SkillUpdated` | 管理员 | 更新技能配置 |
| E16 | `SkillStepReordered` | 管理员 | 调整技能步骤顺序 |
| E17 | `AgentAccessed` | 用户 | 用户浏览 Agent 详情 |

#### 工作流编排领域

| 编号 | 领域事件 | 触发者 | 说明 |
|------|---------|--------|------|
| E18 | `WorkflowCreated` | 用户 | 创建新工作流 |
| E19 | `WorkflowUpdated` | 用户 | 更新工作流定义 |
| E20 | `WorkflowDeleted` | 用户 | 删除工作流 |
| E21 | `WorkflowPublished` | 用户 | 发布工作流 |
| E22 | `NodeAdded` | 用户 | 向工作流添加节点 |
| E23 | `NodeRemoved` | 用户 | 从工作流移除节点 |
| E24 | `NodeConfigured` | 用户 | 配置节点参数 |
| E25 | `EdgeConnected` | 用户 | 连接两个节点 |
| E26 | `EdgeRemoved` | 用户 | 断开节点连接 |
| E27 | `WorkflowValidated` | 系统 | 工作流结构验证通过 |

#### 执行领域

| 编号 | 领域事件 | 触发者 | 说明 |
|------|---------|--------|------|
| E28 | `ExecutionStarted` | 用户/调度器 | 工作流开始执行 |
| E29 | `NodeExecutionStarted` | 执行引擎 | 单个节点开始执行 |
| E30 | `NodeExecutionCompleted` | 执行引擎 | 节点执行成功 |
| E31 | `NodeExecutionFailed` | 执行引擎 | 节点执行失败 |
| E32 | `ExecutionCompleted` | 执行引擎 | 工作流执行完成 |
| E33 | `ExecutionFailed` | 执行引擎 | 工作流执行失败 |
| E34 | `ExecutionLogAppended` | 执行引擎 | 追加执行日志 |

### 2.2 事件时序关系（核心流程）

```
用户注册 ──→ 用户登录 ──→ 浏览Agent大厅 ──→ 查看Agent详情
                                              │
                                              ▼
                                        创建工作流 ──→ 添加节点 ──→ 连接边 ──→ 验证 ──→ 发布
                                                                            │
                                                                            ▼
                                                                      执行工作流
                                                                        │
                                                            ┌───────────┼───────────┐
                                                            ▼           ▼           ▼
                                                        节点执行    节点执行    节点执行
                                                        成功/失败   成功/失败   成功/失败
                                                            │           │           │
                                                            └───────────┼───────────┘
                                                                        ▼
                                                                  执行完成/失败
```

---

## 3. 限界上下文划分

### 3.1 上下文总览

基于领域事件的聚类分析，将系统划分为 **4 个限界上下文**：

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     多 Agent 智能体平台                                   │
│                                                                         │
│  ┌───────────────┐  ┌───────────────┐  ┌──────────────┐  ┌───────────┐ │
│  │   认证上下文   │  │ Agent管理上下文│  │ 工作流编排上下文│  │  执行上下文 │ │
│  │  Auth Context  │  │ Agent Mgmt    │  │  Workflow     │  │ Execution │ │
│  │               │  │  Context      │  │  Context      │  │  Context  │ │
│  │ · 用户注册    │  │ · Agent CRUD  │  │ · 工作流CRUD  │  │ · 执行引擎 │ │
│  │ · 登录/登出   │  │ · Agent分组   │  │ · 节点管理    │  │ · 日志记录 │ │
│  │ · JWT管理     │  │ · 技能管理    │  │ · 边管理      │  │ · 状态追踪 │ │
│  │ · RBAC权限    │  │ · 发布管理    │  │ · 画布编辑    │  │ · 异常处理 │ │
│  │ · Token黑名单 │  │               │  │ · 结构验证    │  │           │ │
│  └───────────────┘  └───────────────┘  └──────────────┘  └───────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.2 各上下文详细定义

#### 3.2.1 认证上下文（Auth Context）

- **核心职责**：用户身份认证、JWT 令牌管理、RBAC 角色权限控制
- **聚合根**：User、Role、Permission
- **领域事件**：E01 ~ E07
- **数据库表**：users、roles、permissions、user_roles、role_permissions
- **关键业务规则**：
  - JWT Access Token 有效期 30 分钟，Refresh Token 有效期 7 天
  - 登出时 Token 加入 Redis 黑名单
  - 权限变更实时生效（通过 Redis 缓存失效）
  - 密码使用 BCrypt 加密存储

#### 3.2.2 Agent 管理上下文（Agent Management Context）

- **核心职责**：Agent 生命周期管理、技能编排、分组组织、发布控制
- **聚合根**：Agent、AgentGroup、Skill
- **领域事件**：E08 ~ E17
- **数据库表**：agent_groups、agents、skills、skill_steps
- **关键业务规则**：
  - Agent 必须属于一个分组
  - Agent 发布前必须至少有一个技能
  - 技能步骤支持顺序、条件、并行三种执行模式
  - Agent 删除采用软删除策略

#### 3.2.3 工作流编排上下文（Workflow Orchestration Context）

- **核心职责**：工作流定义、节点编排、连接管理、结构验证
- **聚合根**：Workflow、WorkflowNode、WorkflowEdge
- **领域事件**：E18 ~ E27
- **数据库表**：workflows、workflow_nodes、workflow_edges
- **关键业务规则**：
  - 工作流必须有且仅有一个起始节点和一个结束节点
  - 节点类型包括：AgentNode、ConditionNode、ParallelNode、LoopNode
  - 边必须连接两个合法节点
  - 发布前必须通过结构验证（无孤立节点、无环路、可达终止节点）
  - 画布操作通过 Vue Flow 组件实现拖拽式编排

#### 3.2.4 执行上下文（Execution Context）

- **核心职责**：工作流执行调度、节点执行引擎、执行日志、状态追踪
- **聚合根**：ExecutionLog
- **领域事件**：E28 ~ E34
- **数据库表**：execution_logs
- **关键业务规则**：
  - 执行采用异步模式，通过线程池调度
  - 节点执行失败支持重试（默认 3 次）
  - 执行日志记录完整的输入输出快照
  - 并行节点通过 CompletableFuture 实现
  - 条件节点支持 JavaScript 表达式求值

---

## 4. 上下文映射图

### 4.1 上下文关系

```
                        ┌──────────────────┐
                        │    认证上下文      │
                        │  (Auth Context)  │
                        └────────┬─────────┘
                                 │
                    U/D          │   U/D = 上游/下游
              ┌──────────────────┘   ACL  = 防腐层
              │                      OHS  = 开放主机服务
              │                      PL   = 发布语言
              ▼
     ┌────────────────┐         ┌──────────────────┐
     │ Agent管理上下文  │◄──PL────│  工作流编排上下文  │
     │(Agent Mgmt Ctx) │         │(Workflow Ctx)    │
     └────────┬───────┘         └────────┬─────────┘
              │                          │
              │         CF               │   CF = 客户-供应商
              │    (Customer-Supplier)   │
              │                          │
              └──────────┬───────────────┘
                         │
                         ▼
                ┌─────────────────┐
                │    执行上下文     │
                │(Execution Ctx)  │
                └─────────────────┘
```

### 4.2 上下文映射关系明细

| 上游上下文 | 下游上下文 | 关系模式 | 集成方式 | 说明 |
|-----------|-----------|---------|---------|------|
| 认证 | Agent管理 | 上游/下游 | JWT Token + 用户ID | Agent管理需要认证信息来识别操作者 |
| 认证 | 工作流编排 | 上游/下游 | JWT Token + 用户ID | 工作流操作需要认证 |
| 认证 | 执行 | 上游/下游 | JWT Token + 用户ID | 执行操作需要认证 |
| Agent管理 | 工作流编排 | 发布语言（PL） | REST API | 工作流节点引用 Agent 时查询 Agent 定义 |
| 工作流编排 | 执行 | 客户-供应商（CF） | 内部事件 | 执行引擎消费工作流定义进行执行 |
| Agent管理 | 执行 | 开放主机服务（OHS） | REST API | 执行引擎调用 Agent 技能 |

### 4.3 跨上下文通信矩阵

```
                认证    Agent管理   工作流编排    执行
认证             -      Token验证   Token验证   Token验证
Agent管理       权限校验    -       Agent查询    技能调用
工作流编排      权限校验   引用Agent    -       触发执行
执行           权限校验   调用技能    获取工作流    -
```

---

## 5. 聚合设计

### 5.1 聚合划分

#### 认证上下文聚合

```
User (聚合根)
├── id: Long
├── username: String
├── password: String (BCrypt)
├── email: String
├── status: UserStatus (ACTIVE/DISABLED/LOCKED)
├── roles: List<Role> (值对象引用)
└── createdAt / updatedAt: LocalDateTime

Role (聚合根)
├── id: Long
├── name: String
├── code: String
├── description: String
└── permissions: List<Permission> (值对象引用)

Permission (实体)
├── id: Long
├── name: String
├── code: String
├── resource: String
└── action: String
```

#### Agent 管理上下文聚合

```
Agent (聚合根)
├── id: Long
├── name: String
├── description: String
├── avatar: String
├── groupId: Long
├── status: AgentStatus (DRAFT/PUBLISHED/OFFLINE)
├── config: AgentConfig (值对象)
│   ├── model: String
│   ├── temperature: Double
│   └── maxTokens: Integer
└── skills: List<Skill> (实体)

Skill (实体)
├── id: Long
├── name: String
├── description: String
├── type: SkillType
└── steps: List<SkillStep> (实体)

SkillStep (实体)
├── id: Long
├── stepOrder: Integer
├── name: String
├── action: String
└── config: JsonNode

AgentGroup (聚合根)
├── id: Long
├── name: String
├── description: String
├── sortOrder: Integer
└── agentCount: Integer (计算属性)
```

#### 工作流编排上下文聚合

```
Workflow (聚合根)
├── id: Long
├── name: String
├── description: String
├── status: WorkflowStatus (DRAFT/PUBLISHED/ARCHIVED)
├── ownerId: Long
├── nodes: List<WorkflowNode> (实体)
└── edges: List<WorkflowEdge> (实体)

WorkflowNode (实体)
├── id: Long
├── nodeId: String (UUID, 画布唯一标识)
├── type: NodeType (AGENT/CONDITION/PARALLEL/LOOP/START/END)
├── position: Position (值对象: x, y)
├── config: JsonNode
└── label: String

WorkflowEdge (实体)
├── id: Long
├── sourceNodeId: String
├── targetNodeId: String
├── sourceHandle: String (可选)
├── targetHandle: String (可选)
├── label: String (可选)
└── condition: String (条件边表达式)
```

#### 执行上下文聚合

```
ExecutionLog (聚合根)
├── id: Long
├── workflowId: Long
├── workflowName: String
├── triggeredBy: Long (用户ID)
├── status: ExecutionStatus (RUNNING/SUCCESS/FAILED/CANCELLED)
├── startedAt: LocalDateTime
├── completedAt: LocalDateTime
├── duration: Long (毫秒)
└── nodeLogs: List<NodeExecutionLog> (值对象)

NodeExecutionLog (值对象)
├── nodeId: String
├── nodeType: String
├── nodeLabel: String
├── status: String
├── input: JsonNode
├── output: JsonNode
├── error: String
├── startedAt: LocalDateTime
└── completedAt: LocalDateTime
```

---

## 6. 统一语言术语表

### 6.1 核心术语

| 术语 | 英文 | 定义 | 所属上下文 |
|------|------|------|-----------|
| 用户 | User | 系统的使用者，拥有角色和权限 | 认证 |
| 角色 | Role | 权限的集合，用于 RBAC 授权 | 认证 |
| 权限 | Permission | 对特定资源的操作许可 | 认证 |
| 智能体 | Agent | 具备特定能力的 AI 代理，是平台的核心实体 | Agent管理 |
| 智能体分组 | Agent Group | 对智能体进行分类管理的组织单元 | Agent管理 |
| 技能 | Skill | 智能体具备的单一能力，由步骤序列组成 | Agent管理 |
| 技能步骤 | Skill Step | 技能执行的最小单元，定义具体的操作 | Agent管理 |
| 工作流 | Workflow | 由多个节点按特定逻辑编排的自动化流程 | 工作流编排 |
| 工作流节点 | Workflow Node | 工作流中的执行单元，对应画布上的一个图形元素 | 工作流编排 |
| 工作流边 | Workflow Edge | 连接两个节点的关系，定义执行流向和条件 | 工作流编排 |
| 画布 | Canvas | 工作流编辑器的可视化编辑区域 | 工作流编排 |
| 执行 | Execution | 一次工作流的完整运行过程 | 执行 |
| 执行日志 | Execution Log | 记录工作流执行过程和结果的日志 | 执行 |
| 令牌 | Token | JWT 格式的身份凭证 | 认证 |
| 黑名单 | Blacklist | Redis 中存储的已失效 Token 集合 | 认证 |
| 发布 | Publish | 将草稿状态的实体变为可用的正式状态 | Agent管理/工作流编排 |

### 6.2 状态术语

| 术语 | 适用实体 | 可选值 | 说明 |
|------|---------|--------|------|
| 用户状态 | User | ACTIVE / DISABLED / LOCKED | 用户账户状态 |
| Agent 状态 | Agent | DRAFT / PUBLISHED / OFFLINE | Agent 生命周期状态 |
| 工作流状态 | Workflow | DRAFT / PUBLISHED / ARCHIVED | 工作流生命周期状态 |
| 执行状态 | Execution | RUNNING / SUCCESS / FAILED / CANCELLED | 执行运行时状态 |
| 节点类型 | WorkflowNode | START / END / AGENT / CONDITION / PARALLEL / LOOP | 工作流节点类型分类 |

### 6.3 动作术语

| 术语 | 定义 | 典型场景 |
|------|------|---------|
| 编排 | 通过拖拽方式将节点组合成工作流 | 工作流编辑器 |
| 调度 | 根据工作流定义安排执行顺序和并行策略 | 执行引擎 |
| 触发 | 启动一次工作流执行 | 用户点击运行/定时调度 |
| 回滚 | 将工作流恢复到之前的版本 | 版本管理 |
| 验证 | 检查工作流结构是否合法 | 发布前检查 |
| 授权 | 验证用户是否有权限执行某操作 | RBAC 中间件 |

---

## 7. 领域事件与限界上下文映射矩阵

| 限界上下文 | 产生的事件 | 消费的事件 | 暴露的命令 |
|-----------|-----------|-----------|-----------|
| 认证 | UserRegistered, UserLoggedIn, UserLoggedOut, TokenRefreshed, RoleCreated, PermissionGranted, UserPasswordChanged | - | RegisterUser, Login, Logout, RefreshToken, CreateRole, GrantPermission |
| Agent管理 | AgentCreated, AgentUpdated, AgentDeleted, AgentPublished, AgentUnpublished, AgentGroupCreated, SkillCreated, SkillUpdated, SkillStepReordered, AgentAccessed | UserLoggedIn (认证校验) | CreateAgent, UpdateAgent, DeleteAgent, PublishAgent, CreateGroup, CreateSkill |
| 工作流编排 | WorkflowCreated, WorkflowUpdated, WorkflowDeleted, WorkflowPublished, NodeAdded, NodeRemoved, NodeConfigured, EdgeConnected, EdgeRemoved, WorkflowValidated | UserLoggedIn, AgentPublished (引用Agent) | CreateWorkflow, UpdateWorkflow, DeleteWorkflow, PublishWorkflow, AddNode, ConnectEdge |
| 执行 | ExecutionStarted, NodeExecutionStarted, NodeExecutionCompleted, NodeExecutionFailed, ExecutionCompleted, ExecutionFailed, ExecutionLogAppended | WorkflowPublished, AgentPublished | StartExecution, CancelExecution, RetryExecution |

---

## 8. 设计原则

1. **聚合独立性**：每个聚合根可以独立持久化和加载，避免跨聚合事务
2. **最终一致性**：跨上下文的数据通过事件和异步机制保持最终一致
3. **防腐层（ACL）**：上下文间通过 DTO 和接口适配层隔离，避免领域模型泄露
4. **事件驱动**：关键业务操作产生领域事件，用于解耦和审计
5. **统一语言**：团队交流、代码命名、API 设计均使用本文档定义的统一术语
