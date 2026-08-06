# File Structure · 文件与目录结构说明

> Context Engineering 核心：让 AI agent（和新成员）一眼看懂每个文件/目录干什么。
> 维护：每次新增/删除目录，**同步更新本文件**。它和 [AGENTS.md](../项目规范约束/AGENTS.md) 是 AI 的「入职手册」。
> **文档规模**：≤5000 tokens。后端/前端/中文工程文档各自复杂，本文件只保留总览索引；细节见各子目录的速查表（[feature-map/](feature-map/)）与既有中文文档。

## 两套文档体系说明（重要）

本项目同时存在**两套文档目录**，职责互补、不冲突：

| 目录 | 性质 | 内容 |
|---|---|---|
| `项目工程文档/` | **既有中文文档**（保留） | 需求 PRD、设计/ADR、计划1-11、数据库设计、功能介绍/速查表、开发进度 |
| `workflow_output/` | **SDD 标准脚手架**（本次新增） | Phase 0-6 产物导航、AGENTS.md、plan/feature-map/user-ops/run-guide/deploy/changes 模板 |

> 原则：`项目工程文档/` 作为**内容真相源**保留不动；`workflow_output/` 作为**SDD 流程入口与索引层**，大量文档以「总路由」形式指向既有中文文档，避免内容重复。

## 目录树（总览）

```
agent-platform/
├── backend/              # Java Spring Boot 后端（业务主服务，端口 8080）
├── frontend/             # Vue 3 + Vite 前端（SPA，端口 5173）
├── runtime-sidecar/      # Python FastAPI 工作流编排 Sidecar（端口 8090，可选）
├── docs/                 # Claude Code superpowers 技能资源（非业务文档）
├── 项目工程文档/          # 既有中文工程文档（保留，内容真相源）
└── workflow_output/      # SDD 标准产物（本次新增，流程导航+模板）
    ├── docs/
    │   ├── 项目分析/       # Phase 0 商业前置报告
    │   ├── specs/          # Phase 1 规格（PRD + 术语表）
    │   ├── plans/          # Phase 2 实现计划（总路由→既有 计划1-11）
    │   ├── 测试方案/        # Phase 3 人工测试方案（按需）
    │   ├── feature-map/    # Phase 3 代码速查表（总路由→既有 速查表01-23）
    │   ├── user-ops/       # Phase 3 用户操作手册（B/C 类功能）
    │   ├── run-guide/      # Phase 4 快速启动速查表
    │   ├── deploy/         # Phase 5 部署手册
    │   ├── changes/        # Phase 6 变更记录 + 影响评估
    │   └── file_structure.md   # 本文件
    ├── 项目规范约束/        # AGENTS.md（AI 指令）+ 通用约束
    └── 开发进度/            # Phase 3 进度跟踪（总览 + 每功能逐步骤）
```

## 后端包结构（`backend/src/main/java/com/superprogrammer/`）

| 包 | 职责 | 关键类 |
|---|---|---|
| `AgentPlatformApplication` | Spring Boot 启动入口 | — |
| `auth/` | 认证/JWT/RBAC/钉钉登录 | AuthController、AuthService、RequirePermissionAspect、DingTalkService |
| `agent/` | Agent 实体/CRUD/技能/权限/KB绑定 | AgentController、AgentService、SkillService、MarkdownSyncService |
| `workflow/` | 工作流定义/节点 CRUD | WorkflowController、WorkflowService |
| `engine/` | 编排引擎核心（路由/策略/执行器） | OrchestrationEngine、AgentRouter、ExecutionStrategy |
| `execution/` | 执行日志/步骤日志 | ExecutionController、ExecutionLogService |
| `runtime/` | 对接 Python sidecar 网关（mock/sidecar 双模式） | RuntimeCallbackController、RuntimeExecutionService |
| `chat/` | 对话会话/消息/WebSocket/记忆 | ChatController、ChatSessionService、MemoryService、MemoryConflictService |
| `knowledge/` | RAG 知识库（文档/嵌入/检索/审计） | KnowledgeBaseController、RagRetrievalService、DocumentParserService |
| `llm/` | LLM 网关/Provider/AES加密 | LlmGateway、LlmController、UserLlmProviderService、AesEncryptService |
| `project/` | 项目空间隔离 | ProjectController、ProjectService |
| `file/` | 文件上传存储 | FileController、FileStorageService |
| `system/` | 系统设置 | SystemSettingController |
| `common/` | 公共：config/result/exception/security/typehandler | R<T>、PageResult、BusinessException、ErrorCode |

> Flyway 迁移：`backend/src/main/resources/db/migration/V1..V42__*.sql`（V1 初始 schema，V17 RAG，V25 KB绑定/RETRIEVAL节点，V27 记忆冲突，V42 HUMAN_INPUT）。完整清单见 [feature-map/](feature-map/) 总路由。

## 前端目录（`frontend/src/`）

| 目录 | 职责 |
|---|---|
| `api/` | axios 调用层（`request.ts` 基座 + 按模块拆分） |
| `views/` | 路由级页面：Login/AgentHall/AgentDetail/Chat/WorkflowEditor/WorkflowList/Knowledge/ExecutionMonitor/Settings + admin/ |
| `components/` | 业务组件，分子目录 chat/、knowledge/、settings/、workflow/ |
| `composables/` | useBreakpoints（移动端）、useTheme |
| `layouts/` | AuthLayout、MainLayout |
| `router/` | 路由表 + 守卫 |
| `stores/` | Pinia：auth/chat/knowledge/theme |
| `styles/` | global.scss + themes/（3 套暗色） |
| `utils/` | workflowMapper（VueFlow↔后端）、workflowRuntime、storage 等 |

## runtime-sidecar（`runtime-sidecar/app/`）

| 文件 | 职责 |
|---|---|
| `main.py` | FastAPI 入口：`GET /health`、`POST /api/runtime/executions` |
| `graph_compiler.py` | WorkflowDefinition → LangGraph StateGraph 编译 |
| `runtime_executor.py` | 执行器主循环 |
| `node_runtime.py` | 各节点运行时（SKILL/AGENT_REF/CONDITION/ROUTER/PARALLEL/JOIN/HUMAN_*） |
| `callback_client.py` | 反向回调 Java 执行 SKILL/AGENT_REF（带 trust_env=False + X-Runtime-Token） |
| `checkpoint_store.py` | 检查点持久化（断点恢复） |

## 关键文件清单（AI 必读优先级）

1. [项目规范约束/AGENTS.md](../项目规范约束/AGENTS.md) —— 每次开工前必读。
2. [specs/PRD.md](specs/PRD.md) —— 做功能前必读。
3. 本文件 —— 找文件时必读。
4. 既有 `项目工程文档/0_快速启动.md` + `项目工程文档/数据库设计文档.md` —— 启动与建表真相源。
5. [feature-map/](feature-map/) 总路由 —— 代码位置速查。
6. 对应 [plans/](plans/) 实现计划（指向既有 计划1-11）。
