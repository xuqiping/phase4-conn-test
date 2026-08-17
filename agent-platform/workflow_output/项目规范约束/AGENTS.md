# AGENTS.md · 项目级 AI 指令

> Context Engineering 核心产物。AI agent 每次开工前必读，定义「行为准则」。
> 等价于 CLAUDE.md / GEMINI.md / copilot-instructions.md，用 AGENTS.md 通用命名。
> Phase 0 建初版，Phase 3 每完成一个通用能力就织入更新。
> **注意**：本项目根目录另有 `CLAUDE.md`（Claude 专用约定），与本文件互补——CLAUDE.md 偏技术栈速记，本文件偏工作流与文档规范。冲突时以 `CLAUDE.md` 的代码约定为准、以本文件的流程约定为准。

## 通用规则（CORE RULES）

### 代码风格
- **后端**：Java 17 + Spring Boot 3.2.5；包名 `com.superprogrammer`；ORM MyBatis-Plus 3.5.5；实体继承 `BaseEntity`；命名驼峰。
- **前端**：Vue 3 Composition API + TypeScript + Vite 5；UI Naive UI（暗色）；状态 Pinia；样式 Sass + BEM + CSS 变量。
- **sidecar**：Python 3.10+ FastAPI；无沙箱、禁 `eval/exec/subprocess`；条件求值手写解析。
- 必须通过：后端 `mvn compile`/`mvn test`；前端 `npm run build`；sidecar `python -m pytest -q`。

### 禁忌（不要做）
- 不使用浮点存金额（用 DECIMAL）。
- 不引入沙箱执行/任意代码执行（sidecar 安全边界，无 eval）。
- 不在 `application.yml` 写死密钥/密码——敏感配置走环境变量（fail-closed）。
- 不直接改已执行的 Flyway 脚本——结构变更只能新加 `V<n+1>__*.sql`。
- 不造重复轮子：LLM 调用统一走 `LlmGateway`，权限统一走 `@RequirePermission`，响应统一 `R<T>`。

### 偏好（优先这么做）
- 响应统一 `R<T>`，分页 `PageResult<T>`；业务异常抛 `BusinessException(ErrorCode)`。
- 数据库主键 `GENERATED ALWAYS AS IDENTITY`；逻辑删除 `deleted`+`@TableLogic`；自动填充 `created_by/at`、`updated_by/at`（`MetaObjectHandler`）。
- 配置类灵活字段优先 JSONB（PostgreSQL 决定性优势）。
- 文档/对话/记忆大文本走 text 列，不混入主表。
- 修 bug 在注释里简述理由。

## 反幻觉条款（硬性）
- 不确定或缺少上下文时，**先问，不要编**。
- 不要引用不存在的函数/库/API。
- 修 bug 时说明理由（注释或对话）。
- 记忆文件指向的 file/function 若记忆与现状冲突，以代码现状为准（见 [memory workflow-runtime-proxy-token.md](../../../../C:\Users\Administrator\.claude\projects\e--workspace\memory\) 等自动记忆仅作背景）。

## 工作流约束
- **specs before code**：开工前先读 [workflow_output/docs/specs/PRD.md](../docs/specs/PRD.md)。
- **plan before implement**：按 [workflow_output/docs/plans/](../docs/plans/) 总路由（指向既有 计划1-11）走。
- **commit 当存档点**：每完成一个 chunk（测试通过）立即建议提交。
- **人工测试方案（按需）**：需人工交互测试的功能（UI/主观体验/真实第三方）在 [测试方案/](../docs/测试方案/) 产出；不需要则跳过。
- **never commit code you can't explain**：看不懂的代码先加注释或简化。
- **每一轮对话结束更新开发进度**：[开发进度/](../开发进度/) 记录本轮。

## 文档写作规范
- **单文件 5000 tokens 上限**：所有 `workflow_output/` 下文档不得超过。接近 4000 预警，超限拆分子文件 + 总路由索引。
- **功能 README**：[开发进度/<功能>/README.md](../开发进度/)，先判受众 A 技术类/B 用户类/C 两者。
- **专业术语批注**：specs/plans 术语首次出现行内括注大白话 + 文档底部术语表。
- **复用既有文档**：本项目 `项目工程文档/` 已有大量成熟文档（PRD/设计/速查表/数据库设计）。workflow_output/ 优先做**导航与索引**，内容已存在的用链接指向，不重复抄。

## 数据库约束
- 迁移脚本放 `backend/src/main/resources/db/migration/V<版本号>__<描述>.sql`，按 Flyway 版本号顺序执行，**已执行脚本不可改**。
- .sql 脚本带注释（表头说明用途、字段行内注释、关联说明）。
- 向量字段用 `halfvec(2048)` + `halfvec_cosine_ops` HNSW 索引（**不要用 `vector(2048)` 建 HNSW，会报 >2000 维错误**）。
- 外键/高频查询字段加索引；金额 DECIMAL；状态字段注释取值含义。

## 计费归户约束（BillingContext / LlmGateway）

> 详见 [开发进度/积分计费系统/开发进度4.md](../开发进度/积分计费系统/开发进度4.md)。目标：**加新模块调模型不再重接计费**。

- **咽喉**：所有文本 LLM 调用走 `LlmGateway`（`chat`/`chatStream`/`embed`）。出口已做「userId 自动归户」——**调用方忘传 userId，gateway 从 `BillingContext.current()` 兜底，照常采 token + 扣费**。新模块调 `gateway.chat(req)` 即自动计费，**无需写计费代码、无需手传 userId**。
- **userId 三路自动种入 `BillingContext`**：请求线程（`BillingContextFilter` 排 `JwtAuthenticationFilter` 后，从 `(Long)principal` 种入）、线程池任务（4 个 `*TaskExecutorConfig` 的 `BillingContextTaskDecorator` 透传）、SSE 裸线程（`ChatController.doStream`/`KnowledgeAskController.ask` 手工 `set/clear`）。
- **`@Scheduled` / 定时轮询新模块例外**：调度线程无请求上下文，三路都不覆盖——**必须自种 `BillingContext.set(dbUserId)` 或显式传 uid 给 gateway**，否则 uid=null 仅采不扣（`log.warn("LLM 调用无用户上下文...")` 可见）。参考 `IndexJobWorker`（按 `doc.getCreatedBy()`）、`LlmProviderService.chargeAdminDiagnostic`（按 `BillingContext.current()`）。
- **admin 诊断调用**（测试连通等须直调特定 provider 实例、不能走 gateway 按 model 路由）：直调 provider 后手动 `billingService.onSuccess(uid, providerId, "GLOBAL", model, kind, in, out)` 归户扣费，全链吞异常（诊断计费失败不得报错）。
- **铁律不变**：计费是 side-channel——`LlmBillingService`/`MediaBillingService` 全链 try/catch 吞异常，**绝不回归成功的 LLM/媒体响应**；`userId=null` → 仅采不扣；`billing.enabled=false` → 扣/退短路。
- **组池归属（计划5 Step4）**：`BillingContext` 第二槽 `currentGroupId()`——网关四出口 `resolveBillingGid`（显式 `LlmRequest.projectGroupId`/embed-rerank gid 形参优先，空回退组槽）。gid 来自请求体非 principal，**Filter 不种**：入口点手工种（ask 裸线程 `set(uid,gid)`；chat/AGENT RAG 检索段 `setGroup(gid)`+finally 还原，防记忆写入串组）；TaskDecorator 随 userId 快照透传。带 gid 调用=组池预检（非成员 403/组池尽 40201，入口可抛）+chargeGroup+`llm_usage_logs.project_group_id` 落账；**局部段落切组必须 try/finally 还原**，引擎内部开销（路由选路/记忆）保持个人。

## 运维/脚本约束（运维系统沉淀）

- **Windows .bat 红线**：含中文的 .bat 必须 **GBK + CRLF + 无 BOM、禁止 chcp 65001**（UTF-8/65001 多字节错位致 REM 行被当命令执行；BOM 炸 `@echo off`；LF 致 REM 保护失效）。
- **改 GBK bat 禁用 git-bash sed**——sed 输出会丢 `\r` 触发上一条全炸。一律 PowerShell 字节级：`[IO.File]::ReadAllBytes` + `[Text.Encoding]::GetEncoding('GBK')` 改写。
- bat 子例程末尾显式 `exit /b 0`（cmd 的 echo 不复位 errorlevel，错误码会泄漏给调用方）。
- **可执行 bat 一律放仓库英文目录 `scripts/ops/`**：SYSTEM 计划任务解析不了中文路径（实测报「系统找不到指定的路径」，同脚本提权 cmd 直跑却正常）；`项目工程文档/运维/` 只放模板与文档。
- bat 发往 webhook 的 JSON 一律 ASCII（GBK 字节钉钉乱码）；生成的 yml 注释也 ASCII。
- **监控红线**：userId/traceId/agentId/IP 等高基数值永远不进 metric tag 和 alert label；指标埋点 O(1)、禁 IO/查库（Gauge 回调除外）；告警 annotations 中文大白话+处置入口，不含敏感数据。
- **监控组件安全策略**：Prometheus/Grafana/Alertmanager/适配器全部只绑 127.0.0.1，不经 Nginx 反代（与 /actuator 同策略）。
- **密钥**：webhook/SMTP/DB 密码等只存服务器本地文件，仓库只存 .example 模板；钉钉机器人须经转译适配器（拒收 Alertmanager 原生报文）。

## 模块级约束（按需新增并在此索引）
- [通用约束.md](通用约束.md) —— 跨所有模块的编码/命名/响应规范
- **前端模块开关 + 权限显隐机制**（10x 沉淀）：控制某模块在前端是否展示，统一走 `frontend/src/config/modules.ts`：
  - `ENABLED_MODULES`（项目级开关，false=对所有人隐藏含 admin）+ `MODULE_PERMISSION_MAP`（模块→权限码，叠加 RBAC）。
  - 消费方三处：`Sidebar.canSeeModule`（菜单）、`router/accessGuard.resolveRouteAccess`（路由守卫）、入口组件 `v-if`。
  - **加新模块**：① `modules.ts` 加 key+布尔+权限码；② Sidebar navItem 标 `module`；③ 路由 `meta.module`/`meta.requireAdmin`。改一处不生效=三处都漏。
  - **隐藏存量模块**：把对应布尔改 false，菜单+路由+入口同步消失，后端代码不动。
  - 路由守卫逻辑抽纯函数（`accessGuard.ts`），避开真实懒加载导航在 jsdom 测试超时；守卫读 localStorage 判角色（早于 Pinia）。
  - 默认落地页用 `defaultLanding()` 动态选首个启用模块，**不要硬编码**（曾硬编码 `/agents`，关 /agents 后登录白屏）。
- **价表导入导出规范**（7x 沉淀）：镜像 LLM 供应商 export/import 那套（DTO + upsert + 200 上限 + 逐行容错）：
  - upsert 业务键：`(providerId + model + kind + hasReference)`；存在覆盖价格刷新 `effective_from=now`，不存在新建。
  - 模板复用 `availablePricingModels()`（已排除已配置模型），天然区分 LLM/图片/视频（kind 字段）。
  - 三 endpoint 均 `@RequirePermission("pricing:manage")` + `@AuditLog`；价表无加密，导出无需二次确认。
  - 导入逐行校验复用 `validatePricingRule` + provider/model/category 复核；非法行进 errors 不中断整体。
  - **PG 软删列是 INTEGER**（`deleted`），SQL 里写 `= 0` 不能写 `= false`（`operator does not exist: integer = boolean` → 兜底 500，曾坑图片价表创建）。
- **视频参考定价维度 has_reference**（7x 沉淀）：
  - `pricing_rule.has_reference BOOLEAN NOT NULL DEFAULT FALSE`（V95）；VIDEO 同模型可配 false+true 两行。
  - 查询 fallback 到 false 行（不区分的模型配 1 行 false 即可）；只配 true 没配 false → 无参考任务报「价表未配置」（不无限兜底）。
  - **worker 算 hasReference 必须从 `request.getAttachments()` 的 `kind=="video"` 判**，不用 taskType（IMAGE2VIDEO 被重载用于 image/video/audio 参考，不可靠）；**首尾帧参考图（kind=="image"）不算参考视频**。
  - `MediaTaskVO.hasReference` 是计算字段（按 inputAttachments 实时算），与定价维度 `pricing_rule.has_reference` 是不同字段——前者任务侧展示/审查，后者价表行配置/计费命中，口径一致（都按 kind=="video"）。
- **视频任务推送参数审查**（7x 沉淀）：实际发给 Provider 的 body 已脱敏落库在 `media_gen_tasks.request_config` JSONB 的 `providerRequestSnapshot` 子键（媒体 URL→sha256/大小，无二进制）。新接入审查只需复用 `MediaTaskRequestDetails` 组件，**无需新 DB 列**；Canvas 路径需在 `pollVideoTask`/`hydrateVideoPreviews` 两处 `updateNodeData` 保留审计字段。
- **前端后台型请求豁免 `_background`**（2x 四轮沉淀）：轮询/blob 预取类请求在 axios config 标 `_background: true`（声明在 `api/request.ts` 的 `AxiosRequestConfig` 模块扩充）——网络层失败不进断路计数、不踢会话、toast 换节流版「后台任务网络波动」。**只给后台型标**：用户主动点击触发的请求（提交/上传/抽帧 POST）保留完整断路语义。轮询一律走 `utils/mediaTaskPolling.ts`（自带 5→10→30s 退避 + visibility 回显补轮），新增后台轮询不要再手写 setInterval。

## 参考文档
- 项目结构 → [workflow_output/docs/file_structure.md](../docs/file_structure.md)
- 需求规格 → [workflow_output/docs/specs/PRD.md](../docs/specs/PRD.md)
- 既有中文文档（真相源）→ `项目工程文档/`（需求/设计/ADR/计划/速查表/数据库设计）
- 快速启动 → [workflow_output/docs/run-guide/快速启动速查表.md](../docs/run-guide/快速启动速查表.md)

## 模型解析约束

- 运行时代码禁止硬编码具体模型 ID，也禁止用供应商模型列表第一项作为隐式默认值。
- 文本与向量调用统一遵循：显式选择 → 管理员对应类别默认值 → 明确业务错误。
- 显式模型不可用时直接报错，不得静默替换成其他模型。
- 历史 Flyway 迁移和测试数据可以保留具体模型 ID，但不得作为新运行时默认来源。

## RAG Trace 与日志约束

- RAG 检索、重排、模型调用、计费、审计和 Java 日志必须共用同一个 `traceId`，并按需携带 `retrievalRunId`、`rankingRunId`、`modelRequestId`、`callPurpose`。
- MDC 只允许放关联 ID、用户 ID、知识库 ID 摘要和调用用途；禁止放 Query、Prompt、Chunk 正文、附件内容、密钥或令牌。
- Query Rewrite/HyDE、Embedding、答案生成等模型调用必须记录真实用途；不得全部笼统标成答案生成。
- 裸线程、线程池和 Reactor/SSE 回调必须显式传播上下文，并在回调结束后立即恢复/清理，禁止 ThreadLocal/MDC 串请求。
- 配置的 Ranking 模式和实际执行模式必须分开记录；启发式代理不得伪装成已经调用 LLM 或专用 Rerank 模型。

## RAG 答案缓存协议约束

- 缓存查询必须同时隔离 `scopeUserId/embeddingModel/rankingConfigVersion/pipelineVersion/promptVersion/knowledgeSnapshot`，禁止只按用户和向量近邻查询。
- 命中候选后仍必须复核当前权限签名、节点 ACTIVE/未删除状态和 evidence `content_hash`；缓存只做优化，不可成为权限或正确性依据。
- 权限、文档撤销和 Ranking 配置变化必须主动停用受影响缓存；Embedding、Pipeline、Prompt、知识快照变化至少通过版本 Key 保证零误命中。
- Pipeline/Prompt 协议版本必须来自可配置项，不得散落硬编码；升级检索或 Prompt 语义时同步递增版本。

## 知识库文档版本治理约束

- `knowledge_documents` 是 Canonical Document，`knowledge_document_versions` 保存不可变历史；禁止用覆盖旧版本文件或 Hash 的方式“更新文档”。
- 版本切换必须在事务内锁主文档，并用 `expectedCurrentVersionId` 检查并发；过期提交明确报冲突，不得静默覆盖。
- 新版本文件和 `sourceHash` 必须由后端存储/计算，禁止信任客户端传入 `fileRef` 或 Hash。
- 每个文档最多一个 `EFFECTIVE` 版本；撤销当前版本必须清空 `current_version_id`，检索只允许当前指针非空的文档。
- 生效、替代、撤销必须写审计并发布缓存/索引失效事件；历史版本查询只返回有 KB 读权限的数据。

## 知识库文档元数据治理约束

- Canonical Document 的 `owner/source/sourceUpdatedAt/authority/confidentiality/tags/effectiveAt/expiredAt` 必须经治理 API 修改；文件引用和内容 Hash 仍只允许后端生成。
- 权威等级仅允许 `OFFICIAL/APPROVED/REFERENCE/UNVERIFIED`；密级仅允许 `PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED`，禁止自由字符串导致过滤口径漂移。
- 密级变更仅管理员可执行；知识库管理员可维护其他治理字段。所有更新写审计并主动失效该 KB 的答案缓存。
- 有效区间使用 `TIMESTAMPTZ`，规则为 `effectiveAt < expiredAt`；默认检索排除尚未生效和已经失效的文档，边界统一以数据库 `now()` 判断。
- 标签最多 20 个、单标签最多 64 字符，写入前去空白、去重；JSONB 更新必须显式 `::jsonb`，不得依赖通用更新器猜 JDBC 类型。

## 知识库结构化解析协议约束

- `ExtractedDocument` 必须携带 `schemaVersion/parserName/parserVersion/sourceHash/documentType`；Section 必须携带稳定 `sectionId/nodeType/titlePath/ordinal/locator`，后续分块不得重新猜测源文档位置。
- 定位协议按文档类型填写可靠子集：PDF 用真实页码，Excel 用 Sheet/行/Cell，Markdown/DOCX 用标题树和阅读顺序，图片固定第 1 页区域；无法可靠获得 bbox 时必须留空，禁止伪造坐标。
- 结构化解析 JSON 属于大对象，必须写文件存储；数据库版本行只保存 `parserVersion/parseArtifactRef/parseArtifactHash/parsedAt`，并以 SHA-256 校验完整性。
- 解析产物必须绑定 `knowledge_documents.current_version_id`；历史无版本数据可兼容解析但不写产物，新上传文档不得绕过版本绑定。
- L0/L2 节点必须继承当前 `versionId`，并把 Section 的层级与 locator 合并进 metadata；原有文件回显 metadata 不得被覆盖。
- 解析器不得执行宏、脚本或外部链接；PDF 页数和全文字符数必须设硬上限。拿不到的视觉证据交给后续 OCR/Layout，不得以默认 bbox 冒充真实证据。

## 知识库分块协议约束

- D0/S1/C2/E3 在迁移期必须采用双轨兼容：D0 继续由文档 `l1_metadata` 承载，S1 写旧 `L0`，C2/E3 写旧 `L2`；新粒度、类型与版本只写节点 metadata，禁止直接破坏现有 PostgreSQL 检索层级。
- Chunk 必须经 `ChunkFactory` 注册策略生成，不得在 Writer 中恢复通用字符硬切。普通文档按完整段落聚合；CLAUSE/FAQ/LIST/PROCEDURE 保持原子；TABLE_ROW、VISUAL_REGION、PDF_PAGE 使用各自明确类型。
- 普通 C2 目标为 300～600 token；禁止跨章节凑下限，超过上限时才允许安全切分。Overlap 必须复制完整段落且最多 100 token，禁止从语句中间截取重叠文本。
- S1、C2、E3 metadata 必须保存稳定 `parentPath/previousPath/nextPath`、`chunkOrdinal/chunkerVersion/titlePath/locator`；邻居关系基于同批稳定顺序生成，不得依赖数据库偶然排序。
- 所有节点必须继承 `tenantId/kbId/documentId/versionId/ownerId/authorityLevel/confidentialityLevel`；缺少 documentId 或 kbId 时立即拒绝写入，禁止产生无归属知识节点。
- 分块日志和指标只记录文档/版本标识、固定粒度、数量、版本与耗时；禁止记录 Prompt、Query、Chunk 正文、密钥或完整模型输出，指标 tag 禁止使用文档 ID 等高基数字段。

## 知识库上下文化与索引任务约束

- C2/E3 的向量输入必须由 `Contextualizer` 按固定顺序拼装“文档标题、不可变版本、titlePath、所属背景、Chunk 原文”；权限、密级、owner、ACL token 等治理字段不得混入模型文本。
- `contentHash` 只覆盖 Chunk 原文，`contextHash` 覆盖完整 contextual content；Worker 在模型调用前、事务完成前都必须复核两者，任一漂移即作废旧任务，不得让旧向量覆盖新版本。
- 索引任务必须在入队时锁定 `versionId/parserVersion/chunkerVersion/embeddingModel/pipelineVersion`。Worker 优先使用任务模型快照；仅历史无快照任务可兼容读取当前 KB 模型，运行时代码禁止硬编码模型 ID。
- Pipeline 版本来自 `rag.index.pipeline-version` / `RAG_INDEX_PIPELINE_VERSION` 配置；索引编排语义变化时必须递增版本，不得散落硬编码。
- 节点任务入队必须使用 `ON CONFLICT (idempotency_key) DO NOTHING`；幂等键覆盖节点、content/context Hash 和完整版本指纹。同任务重放只能跳过，不能把唯一键冲突升级为解析失败。
- 向量 upsert、任务 DONE 和文档 INDEXED 判定必须处于同一短事务；LLM embedding 调用在事务外。写向量时保存节点真实 level 与 contextHash，旧无 level/context 任务仅允许明确兼容回退。
