# AGENTS.md · file-keeper 项目级 AI 指令

> Context Engineering 的核心产物。AI agent（Claude / Codex / Copilot）每次开工前**必读**。
> 等价于 `CLAUDE.md`（Claude）/ `GEMINI.md`（Gemini）/ `copilot-instructions.md`。
> 上层仓库根 `../CLAUDE.md` 讲多 Agent 平台通用约定；本文件只讲 file-keeper 特有约定，二者**叠加生效**。

## 项目一句话定位

file-keeper 是一款跨平台（Windows / macOS / Linux）桌面效率工具：一键打开 / 关闭 / 批量管理常用文件与程序，叠加剪贴板、截图、Office 批处理、工作汇报、AI 接入等模块。
本地模块始终开放且不要求登录；工作汇报、AI 等服务端模块使用账号登录、用户数据隔离和设备禁用。Office 基础批处理对所有用户开放，超过免费批量额度时必须登录并在线校验 Office Pro；AI 使用服务端积分计量。

## 技术栈（五件套全栈）

| 子系统 | 位置 | 技术栈 |
|---|---|---|
| 桌面端前端 | `src/` | Vue 3 + TS + Vite 5 + Pinia + **Tailwind**（**不用 Naive UI**）+ **单 `App.vue`，无 vue-router** |
| 桌面端原生 | `src-tauri/` | Tauri 2 + Rust（`commands/`：auth / clipboard / files / icons / processes / screenshot / work_report） |
| 后端服务 | `server/` | Spring Boot 3.2.5 / Java 17 / MyBatis-Plus 3.5.5 / Flyway / PostgreSQL(生产)+H2(测试) / Redis / JWT |
| 管理后台 | `admin-web/` | Vue 3 + **Naive UI** + vue-router 4 + Pinia |
| OCR 边车 | `tools/ocr-sidecar/` | Python |

端口：桌面端前端 dev **1420**（vite strictPort）；后端 **8088**（`application.yml`）。

## 通用规则（CORE RULES）

### 后端约定
- 包名 `com.superprogrammer`；ORM 用 MyBatis-Plus，实体继承 `common.BaseEntity`（含 `id/createdBy/createdAt/updatedBy/updatedAt/deleted`，`@TableLogic` 逻辑删除）。
- 响应统一 `R<T>`（`common.R`，`R.ok()` / `R.fail()`）；分页用 `PageResult`；业务异常抛 `BusinessException(ErrorCode)`。
- 方法级权限 `@RequirePermission("resource:action")`；高风险操作记录 `admin_audit_logs` 审计。
- 数据库迁移走 **Flyway**：`server/src/main/resources/db/migration/V<n>__desc.sql`，**已执行脚本不可改**，改结构加新版本号；脚本必须兼容 PostgreSQL + H2；主键 `GENERATED ALWAYS AS IDENTITY`；业务表必含 `created_by/created_at/updated_by/updated_at/deleted`。
- 错误码复用 `ErrorCode` 枚举（`FORBIDDEN 403` / `UNPROCESSABLE 422` …）。

### 前端约定
- **桌面端 `src/`**：Vue 3 `<script setup>` + TS + **Tailwind**（用已有 dark 类如 `dark:bg-dark-panel`）；**不引入 Naive UI、不引入 vue-router**（单 `App.vue` 架构，Tab 由 `currentTab` ref 控制）。文件、进程、剪贴板、截图和免费规模 Office 任务始终开放；超额 Office 任务实时校验 Office Pro；AI 同时校验登录、套餐和积分。
- **管理后台 `admin-web/`**：Vue 3 + Naive UI + vue-router，请求走 `admin-web/src/api/request.ts`。
- 国际化文本进 `src/locales/{zh-CN.ts,en.ts}`，不硬编码中文；状态用 Pinia；调 Tauri 命令走 `src/api/*.ts` 封装。

### Rust 约定
- 命令用 `#[tauri::command]`，在 `src-tauri/src/main.rs` 的 `invoke_handler` 注册。
- 本地原生命令不检查旧 moduleCode 商业授权 Token；Office 超额任务在创建执行票据前实时调用服务端校验 Office Pro。本地仍必须保留路径、文件规模、PID、窗口句柄、密码和输出目录等业务与安全校验。
- 平台差异下沉 `src-tauri/src/platform/`；**绝不用固定屏幕坐标**定位控件。

### 禁忌（不要做）
- ❌ 桌面端 `src/` 引入 Naive UI / vue-router（破坏单 `App.vue` 架构）。
- ❌ 直接在数据库执行 `CREATE TABLE`（必须走 Flyway）；改已执行的 Flyway 脚本（加新版本号）。
- ❌ 业务数据（文件路径 / 剪贴板 / 截图 / 进程列表）上传服务端。
- ❌ 用 `deviceId` 替代 JWT 身份认证；服务端模块必须同时校验登录身份、active 设备和资源归属。
- ❌ 密码 / 密钥 / JWT secret 明文写进文档或代码。
- ❌ 将模型供应商 API Key 下发桌面端，或把 Office 密码/正文写入 SQLite、日志、崩溃报告。
- ❌ AI 输出绕过预览直接执行文件修改、命令或脚本。

### 偏好（优先这么做）
- 修 bug 时在注释 / 对话简述理由；不确定先 Read 确认再写。
- 提交前跑测试：`mvn -f server/pom.xml test` / `npm --prefix . test` / `npm --prefix admin-web test` / `cargo test --manifest-path src-tauri/Cargo.toml`。
- 小 commit，**commit 当存档点**。

## 反幻觉条款（硬性）
- 不确定 / 缺上下文时**先问不要编**；不引用不存在的函数 / 库 / API。
- 路径 / 类名 / 字段不确定时，先 Read 确认再写。
- 文件名含中文 / 空格 / 中文标点，引用时注意正确路径。

## 工作流约束（编程类可迭代工作流）
- **specs before code**：开工前读 `workflow_output/docs/specs/PRD.md`。
- **plan before implement**：按 `workflow_output/docs/plans/<功能>.plan.md` 走，逐步骤勾选，**未经许可不写码**。
- **commit 当存档点**：每完成一个 chunk（测试通过）立即提交。
- **never commit code you can't explain**：看不懂的代码先加注释或简化。
- **改 / 增 / 删已有功能走 Phase 6 变更管理**：先做影响评估（`workflow_output/docs/changes/`），改了必跑回归。
- 可复用提示词在 `.github/prompts/`（1-plan / 2-implement / 3-run / 4-review）。

## 文档写作规范
- **单文件 5000 tokens 上限**：所有 `workflow_output/` 下文档不得超过；接近 4000 预警，超限拆子文件 + 总路由索引。
- **功能 README**：功能完成时在 `workflow_output/开发进度/<功能>/README.md` 产出（A 技术 / B 用户 / C 两者）。
- **开发进度**：每轮对话结束在 `workflow_output/开发进度/<功能>/开发进度n.md` 记录。
- **术语批注**：专业术语首次行内括注大白话，文档底部维护术语表。
- **敏感信息**：账号 / 密码 / 密钥不进 `workflow_output`，用占位符 + 独立安全存储。

## 模块级约束（索引）
- `workflow_output/项目规范约束/新增业务模块规范.md` 与 `新增模块实施指导.md` 中的商业授权接入规则已废弃，待兼容清理阶段重写；新功能不得继续接入 moduleCode、匿名试用或离线 Token。
- Office Pro 是“批量规模 + AI 积分”权益，不是模块开关；不得复活旧 moduleCode、匿名试用或离线套餐 Token。超额任务每次在线校验，离线降为免费额度。
- Office 任务状态、输出计数与安全错误码遵守 [`workflow_output/项目规范约束/Office任务状态机约束.md`](workflow_output/项目规范约束/Office任务状态机约束.md)。
- Office 本地任务库迁移、敏感字段和分页清理遵守 [`workflow_output/项目规范约束/Office任务持久化约束.md`](workflow_output/项目规范约束/Office任务持久化约束.md)。
- Office 路径白名单、输出隔离、风险码和免费额度遵守 [`workflow_output/项目规范约束/Office路径与预检约束.md`](workflow_output/项目规范约束/Office路径与预检约束.md)。
- Office Worker 的 JSON Lines、握手、PID、取消、stderr 脱敏和进程回收遵守 [`workflow_output/项目规范约束/Office Worker协议约束.md`](workflow_output/项目规范约束/Office%20Worker协议约束.md)。
- Office 临时目录、空间预算、校验、原子发布、发布凭证、恢复和替换原件遵守 [`workflow_output/项目规范约束/Office输出事务约束.md`](workflow_output/项目规范约束/Office输出事务约束.md)。
- 上层 [`../CLAUDE.md`](../CLAUDE.md) —— 多 Agent 平台通用约定。

## 参考文档
- 目录结构 → [`workflow_output/docs/file_structure.md`](workflow_output/docs/file_structure.md)
- 需求规格 → [`workflow_output/docs/specs/`](workflow_output/docs/specs/)
