# 系统架构设计书

## 1. 概述

### 1.1 文档目的

本文档定义多 Agent 智能体平台的系统架构，包括整体架构风格、模块划分、接口定义、部署方案、数据流转以及技术债务管理。

### 1.2 架构目标

- **可维护性**：模块化单体架构，清晰的模块边界，为未来微服务拆分做准备
- **可扩展性**：支持 14 个子 Agent 和 51 个工作流的并发执行
- **安全性**：JWT + RBAC + Redis 黑名单的多层安全防护
- **开发效率**：统一技术栈，减少技术认知负担

---

## 2. 系统架构图

### 2.1 总体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                          客户端层 (Client Tier)                      │
│                                                                     │
│   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────────┐   │
│   │ 登录页面  │  │Agent大厅  │  │Agent详情  │  │  工作流编辑器     │   │
│   └──────────┘  └──────────┘  └──────────┘  └──────────────────┘   │
│                        Vue 3 + TypeScript + Pinia                   │
│                        Element Plus + Vue Flow                      │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ HTTPS / WebSocket
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        网关层 (Gateway Tier)                         │
│                                                                     │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                    Spring Boot 内嵌 Tomcat                    │   │
│   │  ┌──────────────┐  ┌──────────────┐  ┌───────────────────┐  │   │
│   │  │  JWT Filter  │  │  CORS Filter │  │  Rate Limiter     │  │   │
│   │  └──────────────┘  └──────────────┘  └───────────────────┘  │   │
│   └─────────────────────────────────────────────────────────────┘   │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      应用层 (Application Tier)                       │
│              模块化单体 — Spring Boot 单进程部署                      │
│                                                                     │
│   ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐  │
│   │  auth 模块   │ │  agent 模块  │ │ workflow模块 │ │ execution模块│  │
│   │             │ │             │ │             │ │             │  │
│   │· Controller │ │· Controller │ │· Controller │ │· Controller │  │
│   │· Service    │ │· Service    │ │· Service    │ │· Service    │  │
│   │· Repository │ │· Repository │ │· Repository │ │· Repository │  │
│   │· DTO        │ │· DTO        │ │· DTO        │ │· DTO        │  │
│   └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘  │
│                                                                     │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                    共享基础设施 (Shared Kernel)                │   │
│   │  · 全局异常处理器   · 统一响应封装   · 日志切面               │   │
│   │  · Redis 工具类    · 文件存储服务   · 参数校验               │   │
│   └─────────────────────────────────────────────────────────────┘   │
└───────────────┬──────────────────────┬──────────────────────────────┘
                │                      │
                ▼                      ▼
┌───────────────────────┐  ┌──────────────────────────────────────────┐
│   数据层 (Data Tier)   │  │            缓存层 (Cache Tier)           │
│                       │  │                                          │
│  ┌─────────────────┐  │  │  ┌────────────────────────────────────┐  │
│  │   PostgreSQL     │  │  │  │           Redis 单节点              │  │
│  │                 │  │  │  │                                    │  │
│  │ · users         │  │  │  │ · JWT Token 黑名单                  │  │
│  │ · roles         │  │  │  │ · 用户权限缓存 (RBAC)               │  │
│  │ · permissions   │  │  │  │ · 工作流编辑锁                       │  │
│  │ · agents        │  │  │  │ · API 限流计数器                     │  │
│  │ · workflows     │  │  │  │ · 执行状态缓存                      │  │
│  │ · execution_logs│  │  │  └────────────────────────────────────┘  │
│  └─────────────────┘  │  └──────────────────────────────────────────┘
└───────────────────────┘
```

---

## 3. 模块职责与接口定义

### 3.1 模块职责矩阵

| 模块 | 包路径 | 核心职责 | 对外暴露 | 依赖模块 |
|------|--------|---------|---------|---------|
| **auth** | `com.platform.auth` | 用户认证、JWT 管理、RBAC 授权 | REST API | 无 |
| **agent** | `com.platform.agent` | Agent CRUD、技能管理、分组管理 | REST API | auth（权限校验） |
| **workflow** | `com.platform.workflow` | 工作流定义、节点编排、画布操作 | REST API | auth, agent（查询Agent信息） |
| **execution** | `com.platform.execution` | 工作流执行、节点调度、日志记录 | REST API + 内部事件 | auth, agent, workflow |
| **shared** | `com.platform.shared` | 通用工具、异常处理、响应封装 | 工具类/切面 | 无 |

### 3.2 模块间接口定义

#### 3.2.1 auth 模块接口

```java
// 对外暴露的服务接口（其他模块通过接口调用，不直接依赖实现）
public interface AuthFacade {
    /** 验证JWT Token有效性，返回用户信息 */
    UserInfo validateToken(String token);

    /** 检查用户是否拥有指定权限 */
    boolean hasPermission(Long userId, String resource, String action);

    /** 获取用户的所有角色 */
    Set<String> getUserRoles(Long userId);
}
```

#### 3.2.2 agent 模块接口

```java
// 对外暴露的服务接口
public interface AgentFacade {
    /** 获取Agent简要信息（工作流节点引用用） */
    AgentSummary getAgentSummary(Long agentId);

    /** 执行Agent的指定技能 */
    SkillExecutionResult executeSkill(Long agentId, Long skillId, Map<String, Object> input);

    /** 获取所有已发布Agent列表（Agent大厅用） */
    List<AgentSummary> listPublishedAgents(Long groupId);
}
```

#### 3.2.3 workflow 模块接口

```java
// 对外暴露的服务接口
public interface WorkflowFacade {
    /** 获取工作流完整定义（执行引擎用） */
    WorkflowDefinition getWorkflowDefinition(Long workflowId);

    /** 验证工作流结构 */
    ValidationResult validateWorkflow(Long workflowId);
}
```

#### 3.2.4 execution 模块接口

```java
// 对外暴露的服务接口
public interface ExecutionFacade {
    /** 异步执行工作流 */
    Long executeWorkflow(Long workflowId, Long userId, Map<String, Object> variables);

    /** 获取执行日志 */
    ExecutionDetail getExecutionDetail(Long executionId);

    /** 取消正在执行的工作流 */
    void cancelExecution(Long executionId);
}
```

### 3.3 模块通信规则

1. **同进程直接调用**：模块间通过 Facade 接口直接调用，Spring 自动注入
2. **禁止循环依赖**：模块依赖方向为 auth <- agent <- workflow <- execution
3. **DTO 隔离**：跨模块传输数据使用 DTO，不暴露实体类
4. **事务边界**：每个模块的 Service 层控制事务，不跨模块传播事务

---

## 4. 部署架构

### 4.1 开发环境部署

```
┌──────────────────────────────────────────┐
│           开发者本地环境                    │
│                                          │
│  ┌────────────┐     ┌────────────────┐  │
│  │  Vue Dev    │────▶│  Spring Boot   │  │
│  │  Server     │     │  :8080         │  │
│  │  :5173      │     │                │  │
│  └────────────┘     └───────┬────────┘  │
│                             │            │
│                    ┌────────┴────────┐   │
│                    │                 │   │
│              ┌─────▼─────┐  ┌───────▼──┐│
│              │ PostgreSQL │  │  Redis   ││
│              │  :5432     │  │  :6379   ││
│              └───────────┘  └──────────┘│
└──────────────────────────────────────────┘
```

### 4.2 生产环境部署

```
                    ┌─────────────┐
                    │   Nginx     │
                    │  反向代理    │
                    │  SSL终止    │
                    │  :443/:80   │
                    └──────┬──────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
              ▼            ▼            ▼
     ┌─────────────┐ ┌─────────┐ ┌─────────┐
     │ App实例 1    │ │App实例2  │ │App实例3  │
     │ Spring Boot │ │Spring   │ │Spring   │
     │  :8080      │ │Boot     │ │Boot     │
     └──────┬──────┘ └────┬────┘ └────┬────┘
            │             │           │
            └─────────────┼───────────┘
                          │
              ┌───────────┴───────────┐
              │                       │
     ┌────────▼────────┐   ┌─────────▼──────┐
     │  PostgreSQL     │   │  Redis Sentinel │
     │  主从复制        │   │  高可用集群      │
     │  主:5432        │   │  :6379          │
     │  从:5432        │   │                 │
     └─────────────────┘   └─────────────────┘
```

### 4.3 环境配置

| 环境项 | 开发环境 | 测试环境 | 生产环境 |
|--------|---------|---------|---------|
| Spring Boot 实例数 | 1 | 2 | 3+ |
| JVM 堆内存 | 512MB | 1GB | 2GB |
| PostgreSQL | 单节点 | 主从 | 主从 + 读写分离 |
| Redis | 单节点 | Sentinel | Sentinel 集群 |
| 前端部署 | Vite Dev Server | Nginx 静态 | Nginx + CDN |
| 日志输出 | Console | File + ELK | File + ELK |

---

## 5. 数据流图

### 5.1 用户登录数据流

```
用户 ──[POST /api/auth/login]──▶ JWT Filter(放行)
                                        │
                                        ▼
                                   AuthController
                                        │
                                        ▼
                                   AuthService
                                   ┌──┤
                                   │  ├─ 1. 验证用户名密码 (查PostgreSQL)
                                   │  ├─ 2. 生成AccessToken(30min) + RefreshToken(7d)
                                   │  ├─ 3. 缓存用户权限到Redis (TTL=30min)
                                   │  └─ 4. 返回Token
                                   │
                                   ▼
                              {accessToken, refreshToken, userInfo}
```

### 5.2 工作流执行数据流

```
用户 ──[POST /api/executions]──▶ JWT Filter(验证Token+权限)
                                       │
                                       ▼
                                  ExecutionController
                                       │
                                       ▼
                                  ExecutionService
                                  ┌──┤
                                  │  ├─ 1. 获取工作流定义(PostgreSQL)
                                  │  ├─ 2. 创建执行日志记录
                                  │  ├─ 3. 提交异步执行任务(ThreadPool)
                                  │  └─ 4. 返回执行ID
                                  │
                                  ▼  [异步线程]
                              ExecutionEngine
                              ┌──┤
                              │  ├─ 1. 解析工作流DAG
                              │  ├─ 2. 拓扑排序确定执行顺序
                              │  ├─ 3. 遍历节点执行:
                              │  │    ├─ AGENT节点 → 调用AgentFacade.executeSkill()
                              │  │    ├─ CONDITION节点 → JavaScript表达式求值
                              │  │    ├─ PARALLEL节点 → CompletableFuture.allOf()
                              │  │    └─ LOOP节点 → 循环执行直到条件满足
                              │  ├─ 4. 每个节点执行后更新日志
                              │  └─ 5. 更新最终执行状态
                              │
                              ▼
                         execution_logs 表
```

### 5.3 工作流画布编辑数据流

```
用户操作(拖拽节点)
       │
       ▼
  Vue Flow 组件
  ┌──┤
  │  ├─ 1. 更新本地画布状态(Pinia Store)
  │  ├─ 2. 防抖 500ms 后发送保存请求
  │  └─ 3. 批量更新节点和边
  │
  ▼
[PUT /api/workflows/{id}/canvas]
  │
  ▼
WorkflowController
  │
  ▼
WorkflowService
  ├─ 1. 验证工作流所有权
  ├─ 2. 删除旧节点和边(事务)
  ├─ 3. 批量插入新节点和边
  └─ 4. 返回保存结果
```

---

## 6. 关键技术决策

### 6.1 模块化单体实现方式

采用 Spring Boot 的包结构而非独立的 Maven/Gradle 模块，理由：

- 当前阶段团队规模小（1-3人），独立模块的构建复杂度不值得
- 包结构即可实现清晰的模块边界
- 未来拆分时，包结构调整比模块拆分更容易

```
src/main/java/com/platform/
├── auth/           # 认证模块
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── dto/
│   └── entity/
├── agent/          # Agent管理模块
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── dto/
│   └── entity/
├── workflow/       # 工作流模块
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── dto/
│   └── entity/
├── execution/      # 执行模块
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── dto/
│   └── entity/
└── shared/         # 共享基础设施
    ├── config/
    ├── exception/
    ├── response/
    ├── util/
    └── aspect/
```

### 6.2 前端架构

```
src/
├── views/              # 页面组件
│   ├── Login.vue
│   ├── AgentHall.vue
│   ├── AgentDetail.vue
│   └── WorkflowEditor.vue
├── components/         # 通用组件
│   ├── common/
│   │   ├── AppButton.vue
│   │   ├── AppCard.vue
│   │   ├── AppInput.vue
│   │   └── AppTable.vue
│   ├── agent/
│   │   ├── AgentCard.vue
│   │   └── SkillList.vue
│   └── workflow/
│       ├── FlowCanvas.vue
│       ├── NodePanel.vue
│       └── ConfigPanel.vue
├── stores/             # Pinia状态管理
│   ├── auth.ts
│   ├── agent.ts
│   ├── workflow.ts
│   └── execution.ts
├── api/                # API请求封装
│   ├── auth.ts
│   ├── agent.ts
│   ├── workflow.ts
│   └── execution.ts
├── router/             # 路由配置
│   └── index.ts
├── styles/             # 样式文件
│   ├── themes/
│   │   ├── deep-space.scss
│   │   ├── dark-pro.scss
│   │   └── cyber-glow.scss
│   ├── variables.scss
│   └── global.scss
├── utils/              # 工具函数
│   ├── request.ts      # Axios封装
│   └── token.ts        # Token管理
└── App.vue
```

---

## 7. 非功能需求设计

### 7.1 性能目标

| 指标 | 目标值 | 实现方式 |
|------|--------|---------|
| API 平均响应时间 | < 200ms | Redis 缓存 + 数据库索引优化 |
| API P99 响应时间 | < 1s | 连接池调优 + 异步处理 |
| 工作流画布加载 | < 500ms | Vue Flow 虚拟化 + 懒加载 |
| 并发执行工作流数 | >= 20 | 线程池配置 + 资源隔离 |
| Agent 列表加载 | < 300ms | 分页查询 + Redis 缓存 |

### 7.2 安全设计

| 安全层 | 措施 | 实现细节 |
|--------|------|---------|
| 传输层 | HTTPS + CORS | Nginx SSL 终止，CORS 白名单 |
| 认证层 | JWT 双 Token | Access Token 30min + Refresh Token 7d |
| 授权层 | RBAC | 角色-权限矩阵，Redis 缓存权限 |
| 会话层 | Token 黑名单 | 登出 Token 加入 Redis 黑名单 |
| 数据层 | SQL 注入防护 | MyBatis-Plus 参数化查询 |
| 接口层 | 限流 | Redis 令牌桶限流 |
| 前端层 | XSS/CSRF | CSP 头 + SameSite Cookie |

### 7.3 可观测性

```
┌─────────────────────────────────────────┐
│               可观测性体系                │
│                                         │
│  ┌───────────┐  ┌───────────┐          │
│  │  日志      │  │  指标      │          │
│  │  Logback  │  │  Micrometer│          │
│  │  → ELK    │  │  → Prometheus│         │
│  └───────────┘  └───────────┘          │
│                                         │
│  ┌───────────┐  ┌───────────┐          │
│  │  链路追踪  │  │  健康检查  │          │
│  │  自定义    │  │  Actuator  │          │
│  │  TraceId  │  │  /health   │          │
│  └───────────┘  └───────────┘          │
└─────────────────────────────────────────┘
```

---

## 8. 技术债务与演进路线

### 8.1 已知技术债务

| 编号 | 债务描述 | 影响范围 | 优先级 | 计划解决时间 |
|------|---------|---------|--------|------------|
| TD-01 | 模块间直接调用耦合度高 | 扩展性 | P2 | Phase 3 |
| TD-02 | 工作流执行使用线程池而非消息队列 | 可靠性 | P2 | Phase 3 |
| TD-03 | 文件存储使用本地磁盘 | 可扩展性 | P3 | Phase 4 |
| TD-04 | 缺少工作流版本管理 | 可维护性 | P2 | Phase 3 |
| TD-05 | 前端状态管理可能随复杂度增加变得难以维护 | 可维护性 | P3 | Phase 4 |

### 8.2 演进路线

```
Phase 1 (当前)                    Phase 2                        Phase 3
模块化单体                        稳定运行                       服务化准备
├── 完成4个核心模块               ├── 性能优化                   ├── 引入消息队列
├── JWT+RBAC安全体系              ├── 完善监控告警               ├── 工作流版本管理
├── 工作流画布                    ├── 补充集成测试               ├── 模块接口抽象化
└── 基础执行引擎                  └── 用户体验优化               └── 数据库分库准备

    Phase 4                        Phase 5
    微服务拆分                      平台化
    ├── 拆分execution为独立服务     ├── 多租户支持
    ├── 引入消息总线                ├── Agent市场
    ├── 分布式事务方案              ├── 工作流模板市场
    └── 容器化部署(K8s)             └── 开放API
```

### 8.3 技术选型预留

| 未来需求 | 预留方案 | 说明 |
|---------|---------|------|
| 消息队列 | RabbitMQ / Kafka | 工作流执行解耦，异步事件驱动 |
| 搜索引擎 | Elasticsearch | Agent 和工作流的全文搜索 |
| 对象存储 | MinIO / OSS | Agent 头像、工作流附件 |
| 容器化 | Docker + K8s | 生产环境容器化部署 |
| API 网关 | Spring Cloud Gateway | 微服务拆分后的统一入口 |
