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

## 模块级约束（按需新增并在此索引）
- [通用约束.md](通用约束.md) —— 跨所有模块的编码/命名/响应规范

## 参考文档
- 项目结构 → [workflow_output/docs/file_structure.md](../docs/file_structure.md)
- 需求规格 → [workflow_output/docs/specs/PRD.md](../docs/specs/PRD.md)
- 既有中文文档（真相源）→ `项目工程文档/`（需求/设计/ADR/计划/速查表/数据库设计）
- 快速启动 → [workflow_output/docs/run-guide/快速启动速查表.md](../docs/run-guide/快速启动速查表.md)
