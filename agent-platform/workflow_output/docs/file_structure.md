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

## 企业级精准知识库 RAG 规格（2026-08-12）

`workflow_output/docs/specs/` 新增以下 Phase 1 规格，旧的 `项目工程文档/项目功能介绍/速查表/14-知识库RAG-*`、`15-知识库RAG-*`、`16-知识库RAG-*` 保留原样：

- `企业级精准知识库RAG-总览.md`：决策、范围、导航和总体验收标准。
- `企业级精准知识库RAG-架构与数据.md`：PostgreSQL/OpenSearch/对象存储边界、版本、分块、多索引和 Trace。
- `企业级精准知识库RAG-检索与重排.md`：QueryPlan、多通道召回、RRF、LLM/Rerank、多证据和拒答。
- `企业级精准知识库RAG-质量安全与迁移.md`：评测、安全、缓存、性能、降级、测试和迁移。
- `企业级精准知识库RAG-旧文档差异清单.md`：逐文件列出旧设计未来应调整项，但本次不修改旧文件。

Phase 3 实现导航新增：

- `workflow_output/docs/feature-map/企业级精准知识库RAG.feature-map.md`：P0–P5 代码、迁移、调用链和运维约束。
- `workflow_output/docs/user-ops/企业级精准知识库RAG用户操作手册.md`：用户提问、管理员评测、索引运维、灰度回滚和反馈操作。
- `workflow_output/docs/测试方案/企业级精准知识库RAG测试方案.md`：Phase 4 人工验收基准。
- `workflow_output/开发进度/企业级精准知识库RAG/README.md`：功能地图与技术总览。
- 旧 `14-知识库RAG-*`、`15-知识库RAG-*`、`16-知识库RAG-*` 正文保持不变；需要修改的内容统一见“旧文档差异清单”，避免两套正文同时漂移。

## 人工测试问题四轮修复规格（2026-08-17）

`workflow_output/docs/specs/` 新增六份 Phase 1 修复/功能规格，来源为 `workflow_output/人工测试问题/` 五份问题单的未解决项（2x/4x/6x/7x/14x）：

| 规格文件 | 覆盖问题单 | 主题 |
|---|---|---|
| `无限画布四轮增强设计.md` | 2x | 超时韧性（「服务不可达」根因=前端断路误伤）/节点宽高拖拽/批量依赖调度/图片翻转+彩色框选标注/@参考预览/关联高亮/左右连线/节点组 |
| `资产库公共选择与评分等级设计.md` | 2x | 公共池资产选择/成员搜索默认展示/评分 A+~D 等级制/复制管控（发布自选） |
| `视频反推与转绘设计.md` | 2x | 反推分镜/关键帧/剧本 + 本土化转绘（关键帧采样+多模态 LLM 路线） |
| `图片视频模块历史分页与预览缓存设计.md` | 4x、6x | 历史分页（默认10可选5/10/20/50）/参考图悬浮+灯箱/图片两级缓存（前端 LRU+后端 ETag） |
| `项目组与积分划拨设计.md` | 7x | 独立项目组实体/组池+成员限额/五入口「参与项目」/项目推进模块/账单项目字段 |
| `知识库模型选择与保密权限设计.md` | 14x | embedding 与问答 LLM 下拉可选/只读越权谓词修复/库级保密开关 |

公共依赖注意：`MediaLightbox`/`HoverPreviewImage` 共享组件定义在图片视频模块规格 §4，画布与反推规格引用之——实现排期时该组件为前置任务。

对应六份 Phase 2 实现计划（2026-08-17，`workflow_output/docs/plans/`，建议实施顺序即下表顺序）：

1. `图片视频模块历史分页与预览缓存.plan.md`（Step 3 共享预览组件是画布/反推计划前置）
2. `资产库公共选择与评分等级.plan.md`
3. `知识库模型选择与保密权限.plan.md`（Step 1 有只读授权影响面 SQL 需用户确认）
4. `无限画布四轮增强.plan.md`（Step 1 background 标记是反推计划前置）
5. `项目组与积分划拨.plan.md`（迁移+账务，建议独立分支）
6. `视频反推与转绘.plan.md`（依赖 1 的组件与 4 的 background 标记）

## 视频模型接入扩展 II：MiniMax H3 + HappyHorse 1.1 中转接入（2026-08-30）

`workflow_output/docs/specs/` 新增 `视频模型接入扩展II-H3与HappyHorse中转接入.md`（单文件规格，HHX-1~11）：

- **来源**：`人工测试问题/3x. 模型接入问题.md` 未解决项——经 ai.ctaigw.cn 中转接入 MiniMax H3（视频生成 + H3-Context-IR 提示词增强 + 2K 再生成）与 HappyHorse 1.1 三模型（t2v/i2v/r2v），种子迁移 V166 落 provider 两行 + 价表六行（apiKeyEnc 留空由管理员填 key）。
- **上游规格**：`视频模型接入扩展.md`（MVR-1~8 适配器/协议/SECOND 分档秒价槽）——本文件是其二期，零新适配器、零 schema 变更，全部为现有 Minimax/Dashscope 适配器的能力补齐 + Flyway 种子。
- 涉及落点清单见规格 §9（V166 迁移 / 两 provider / 能力层三形态分档 / DTO 加 sourceTaskId 与 resultText·双 token / 提交结算分流 / 前端再生成与 Context-IR 表单）。
