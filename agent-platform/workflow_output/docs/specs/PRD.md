# PRD · 产品需求文档 · SuperCoder Agent Hub

> SDD 唯一真相源。实现必须与本文件对齐；冲突时改实现或改本文档（注明原因）。
> Phase 1 产物，迭代维护。**内容真相源**：既有 `项目工程文档/需求/产品需求文档PRD.md`（683 行详版）。本文件为 SDD 索引版，详细条款引用既有文档。
> **文档规模**：≤5000 tokens，超限时拆 `PRD.功能需求.md` / `PRD.非功能需求.md` / `PRD.术语表.md`。

## 1. 项目概述
- **定位**：多 Agent 低代码编排平台，覆盖软件工程全生命周期（详见 [项目分析报告](../项目分析/项目分析报告.md)）。
- **背景**：Agent/工作流泛滥质量参差，需「每方向一顶尖 Agent」+ 标准化检查清单。
- **成功指标**：注册100、首周工作流≥1、4周留存≥40%、执行成功率≥85%。

## 2. 用户故事（节选，全量见既有 PRD）
- 作为**开发者**，我在大厅按分类搜索 Agent，快速找到所需能力。
- 作为**开发者**，我拖拽「编码→审查→测试」Agent 连线，5 分钟搭出自动化链。
- 作为**开发者**，我通过 SSE/WS 实时看节点状态（黄→绿/红），即时定位失败。
- 作为**团队负责人**，我建「需求→架构→编码→审查→测试→部署」模板，团队一键复用。
- 作为**管理员**，我用 RBAC 控制 Agent 访问权限（如部署 Agent 仅限特定角色）。

## 3. 功能需求（核心清单，详版见既有 PRD + [feature-map 总路由](../feature-map/)）
| 编号 | 功能 | 简述 | 优先级 | MVP |
|---|---|---|---|---|
| F1 | 登录认证 | JWT 双 Token、5 次失败锁定 15 分钟 | P0 | 是 |
| F2 | 用户/角色/权限 RBAC | 4 角色 × 15 权限、`@RequirePermission` | P0 | 是 |
| F3 | Agent 大厅 | 分类标签、debounce 搜索、响应式网格 | P0 | 是 |
| F4 | Agent 详情 | schema、关联检查清单、创建工作流入口 | P0 | 是 |
| F5 | Agent 与技能管理 | Agent CRUD、层级、Skill/Step、Markdown 同步 | P0 | 是 |
| F6 | 工作流画布编辑器 | Vue Flow 无限画布、拖拽连线、撤销重做、自动保存 | P0 | 是 |
| F7 | 节点参数面板 | 动态表单、`{{node.field}}` 引用、必填校验 | P0 | 是 |
| F8 | 工作流执行引擎 | 拓扑排序、并行/分支、SSE 事件流 | P0 | 是 |
| F9 | 执行日志与历史 | execution_logs + step_logs、重试/恢复/审批 | P0 | 是 |
| F10 | 多模态对话 | CHAT/AGENT/WORKFLOW 三模式，WS→SSE→REST 降级 | P0 | 是 |
| F11 | LLM 供应商管理 | 全局 + 用户私有（AES 加密 Key） | P0 | 是 |
| F12 | 三套暗色主题 | Deep Space / Dark Pro / Cyber Glow | P1 | 否 |
| F13 | 工作流模板导入导出 | JSON 跨环境迁移 | P1 | 否 |
| F14 | 审计日志与统计 | DAU/MAU、成功率图表 | P1 | 否 |
| F15 | 人工审批节点 HUMAN_APPROVAL | 暂停等人审 | P1 | 否 |
| F16 | 企业级 RAG | pgvector 向量 + BM25 混合检索（已落地） | P2 | 否 |
| F17 | 检查清单节点（51 项） | 风格23/安全15/性能13 | P1 | 否 |
| F18 | HUMAN_INPUT 中途提问 | 工作流暂停收集答案续跑（Phase1 已落地） | P1 | 否 |
| F19 | 资产库公众池与授权 | OWNER 可直接开放或申请审批后开放资产项目；管理员发布带官方标记；公共访问只读 | P1 | 否 |
| F20 | 价表模型约束 | 从全局供应商选择未配置模型，自动绑定 provider/kind 并防重复 | P1 | 否 |

> 已落地功能（速查表01-23 全绿）：认证/用户/角色/主题/Agent/Skill/工作流编辑器/编排引擎/Sidecar/执行监控/对话/记忆/知识库RAG基础+检索+对账/审计/LLM供应商/用户LLM/文件/系统设置/移动端适配。

## 4. 非功能需求
- **性能**：LCP≤2.0s、FID≤100ms、CLS≤0.1；API P50/P95/P99 ≤100ms/500ms/1s；WS 消息延迟≤200ms；DB 查询均≤50ms；并发执行工作流≥20；可用性 99.5%。
- **安全**：HTTPS+TLS1.2+HSTS；JWT RS256/jjwt 0.12.5；RBAC+Redis 缓存权限；BCrypt cost=10；参数化查询防注入；Redis 令牌桶限流；用户 LLM Key AES 加密。详 [security_strategy](security_strategy.md)（若有）或既有 `项目工程文档/设计/adr/003-JWT与RBAC权限.md`。
- **兼容性**：Chrome90+/Firefox88+/Edge90+/Safari14+；最小宽 1280px（移动端≥390px 适配）；PostgreSQL 16 + pgvector；Redis 7。

## 5. 架构
三件套 + 分层：
| 模块 | 技术栈 | 端口 | 职责 |
|---|---|---|---|
| backend | Spring Boot 3.2.5 / Java 17 / MyBatis-Plus / WebFlux / WS | 8080 | 持久化、Agent/Skill 真实执行、LLM 网关、认证 RBAC |
| frontend | Vue 3 + TS + Vite 5 + Naive UI + Vue Flow + Pinia | 5173 | SPA：大厅/画布/对话/管理 |
| runtime-sidecar | Python 3.10 + FastAPI + LangGraph | 8090 | 图编排引擎（节点排序/分支/并行/检查点/事件流） |

关键技术决策（理由见 `项目工程文档/设计/adr/`）：
1. 模块化单体非微服务（1-3 人团队）。
2. 图编排交 Python LangGraph sidecar，叶节点回调 Java。
3. `runtime.gateway.mode=mock` 可省略 sidecar。
4. PostgreSQL + JSONB（配置灵活字段）。
5. 三路流式降级（WS→SSE→REST）。
6. sidecar 安全边界：无沙箱/无 eval/无 subprocess。

## 6. 数据模型
核心实体（详见 `项目工程文档/数据库设计文档.md`）：
- 认证：users / roles / permissions / user_roles / role_permissions（N:N）
- Agent：agent_groups / agents / skills / skill_steps（1:N 链）
- 工作流：workflows / workflow_nodes / workflow_edges
- 执行：execution_logs / execution_step_logs
- 对话/记忆：chat_sessions / chat_messages / user_memories / memory_conflicts
- LLM：llm_providers / user_llm_providers（Key AES）
- RAG：knowledge_*、rag_*、embedding_model_versions（halfvec(2048) HNSW）
- 通用约定：主键 IDENTITY、软删 deleted、乐观锁 version、审计四字段。

## 7. 测试策略
- 后端：JUnit 单元 + 集成测试（`backend/src/test/`）。
- 前端：`npm run build` + 关键 api `.test.ts`；E2E 走 Playwright。
- sidecar：`python -m pytest -q`（runtime/callback/checkpoint/graph/executor）。
- 关键路径：登录→建工作流→执行→看监控；RAG 上传→检索→问答。

## 8. 边界与不做
不做 Bot 市场、不做通用对话产品、不做 IDE 插件（对标 Coze 的差异化边界）。

## 9. 变更记录
| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-07-17 | SDD 索引版建立 | SDD 改造 |
| 2026-08-10 | 增加资产公众池、授权修复与价表模型选择 | 人工测试遗留问题及用户确认的审批发布规则 |

## 10. 术语表（专业术语 · 大白话 · 案例）
| 术语 | 大白话 | 案例 |
|---|---|---|
| Agent | 会做某类软件工程活的智能体 | 代码审查 Agent：提交前自动检查 51 项 |
| Skill | Agent 内按顺序执行的「技能」 | 主控 Agent 有「分发任务」技能 |
| SkillStep | 技能里的一步动作 | 调 LLM / 调 HTTP / 跑条件判断 |
| Workflow | 把多个 Agent 连起来的流程图 | 需求→架构→编码→测试 |
| Node | 画布上的一个节点 | 把「编码 Agent」拖到画布 |
| Edge | 节点间的连线，代表数据流 | 编码节点→审查节点的箭头 |
| DAG | 有向无环图，工作流底层结构 | 不允许 A→B→C→A 的环 |
| RBAC | 按角色控制谁能干什么 | developer 不能删别人工作流 |
| JWT | 登录令牌，前后端靠它认人 | 登录后拿 accessToken 调接口 |
| Refresh Token | 换新 accessToken 的长效令牌 | accessToken 过期时无感刷新 |
| 黑名单（Redis） | 登出后令牌立刻作废的列表 | 用户点退出 token 立即失效 |
| Sidecar | 旁边的辅助进程（Python）跑图编排 | Java 把图交给 Python LangGraph |
| LangGraph | Python 状态图编排库 | 用 StateGraph 实现分支/并行 |
| 检查点 Checkpoint | 工作流跑到一半保存的状态快照 | 失败后从检查点恢复 |
| HUMAN_APPROVAL | 暂停等人审批的节点 | 部署前等 leader 点「通过」 |
| HUMAN_INPUT | 暂停向人提问收集答案的节点 | 工作流中途问「确认用哪个方案」 |
| RAG | 检索增强生成，让 AI 查知识库 | 上传文档→向量化→对话引用 |
| pgvector | PostgreSQL 向量检索扩展 | 存 halfvec(2048) + HNSW 索引 |
| Vue Flow | 前端可视化画布库 | 拖拽连线画工作流 |
| BM25 | 关键词相关性打分算法 | 配合向量做混合检索 |
