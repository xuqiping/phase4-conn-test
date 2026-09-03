# File Structure · file-keeper 文件与目录结构说明

> Context Engineering 核心：让 AI agent 和新人一眼看懂每个目录 / 文件干什么。
> 维护：新增 / 删除目录时**同步更新本文件 + 根 `AGENTS.md`**。本文件 ≤5000 tokens，超限按子系统拆 `file_structure.<子系统>.md`。

## 顶层目录

```
file-keeper/                       # 代码根 = 上层 C:/AI Projects monorepo 的子目录（非独立仓库）
├── src/                           # 桌面端前端 Vue3（Tailwind，单 App.vue，无 router）
├── src-tauri/                     # Tauri2 桌面端 Rust 原生层
├── server/                        # Spring Boot 后端（授权 + 账号 + 工作汇报等服务）
├── admin-web/                     # 管理后台 Vue3（Naive UI + vue-router）
├── tools/                         # 周边工具（ocr-sidecar Python 边车）
├── scripts/                       # Node 脚本（图标生成等）
├── dist/ node_modules/            # 构建产物 / 依赖（非源码）
├── src-tauri/target/ server/target/   # Rust / Maven 构建产物（非源码）
├── docs/                          # 历史文档（docs/superpowers 计划/specs）+ archive/ 归档
├── workflow_output/               # ★工作流文档根（见下表）
├── .github/prompts/               # 可复用 AI 提示词（1-plan/2-implement/3-run/4-review）
└── AGENTS.md                      # ★项目级 AI 指令（每次开工必读）
```

## workflow_output/（工作流文档根）

| 目录 | Phase | 职责 |
|---|---|---|
| `docs/项目分析/` | P0 | 商业前置深度报告（竞品 / 受众 / 商业模式 / 定价） |
| `docs/specs/` | P1 | 规格真相源（PRD / architecture / db_schema / security / performance / testing） |
| `docs/plans/` | P2 | 实现计划（逐步骤 + 复选框 + 技术坑点预判 + 安全 / 运维清单） |
| `docs/测试方案/` | P3 | 需人工测试功能的测试方案（按需，非每功能必产） |
| `docs/feature-map/` | P3 | 功能-代码速查表（位置 + 调用链 + 技术原理注解 + 建表注解） |
| `docs/user-ops/` | P3 | 傻瓜式用户操作手册（B/C 类用户可见功能） |
| `docs/run-guide/` | P4 | 快速启动速查表（组成 / 命令 / 端口 / 查关端口） |
| `docs/deploy/` | P5 | 部署手册（环境 / 软件 / 步骤 / 回滚） |
| `docs/changes/` | P6 | 变更记录 + 影响评估 |
| `docs/file_structure.md` | P1 | 本文件 |
| `项目规范约束/` | 横切 | AGENTS 索引 + 通用 / 模块约束（持续织入） |
| `开发进度/` | P3 | 进度跟踪（总览 + 每功能逐步骤）+ 功能 README |

### 当前 Phase 1 专项规格

`docs/specs/人工测试问题1-4修复.*.md` 是文件管理、进程管理、悬浮球与剪贴板六项修复的规格组，包含 PRD、architecture、db_schema、security_strategy、performance_goals 和 testing_strategy。后续 Phase 2/3 必须以该规格组为输入，不得只依据人工测试问题中的一句描述直接改代码。

## 桌面端前端 `src/`

| 目录 / 文件 | 说明 |
|---|---|
| `main.ts` | Vue 应用入口 |
| `App.vue` | 单页根组件（Tab 由 `currentTab` ref 控制，无 router） |
| `api/` | 17 个：封装后端 HTTP（`fetchR`）+ Tauri Rust 命令（`rustWorkReport` 等），含 `__tests__/` |
| `stores/` | 12 个 Pinia store（含 `commercialAuthStore` 模块授权门禁） |
| `components/` | 组件库（含 `work-report/`、`EntitlementStatus.vue`、`FreeModuleSelector.vue`） |
| `components/office/`（规划） | Office 工作台、向导、预检、冲突确认、任务历史和报告 |
| `composables/` `plugins/` `styles/` `types/` `utils/` `views/` | 组合式函数 / 插件 / 样式 / 类型 / 工具 / 视图 |
| `locales/{zh-CN.ts,en.ts}` | 国际化语言包 |

## Tauri Rust `src-tauri/src/`

| 目录 / 文件 | 说明 |
|---|---|
| `main.rs` | Tauri 入口，`invoke_handler` 注册所有命令 |
| `commands/` | `auth` / `clipboard` / `files` / `icons` / `processes` / `process_management` / `screenshot` / `work_report` |
| `office/`、`commands/office.rs`（规划） | Office 任务调度、SQLite、扫描、输出事务、Worker 与凭据适配 |
| `src/bin/office_ooxml_worker.rs`（规划） | 跨平台 OOXML 隔离 Worker |
| `clipboard/` | 剪贴板内部服务 |
| `platform/` | 平台适配（`linux/` / `macos/` / `windows_file_matcher`） |
| `types/` `utils/` | Rust 共享类型 / 工具 |

## 后端 `server/src/main/java/com/superprogrammer/`

| 包 | 职责 |
|---|---|
| `common/` | 基础设施：`BaseEntity` / `R` / `PageResult` / `BusinessException` / `ErrorCode` / `GlobalExceptionHandler` / `JsonUtils` |
| `security/` | `AuthConstants`（`MODULE_FILES/PROCESSES/CLIPBOARD/...` 常量） |
| `authorization/` | 授权核心：`AuthorizationService`（离线 Token 签发 / 模块权益快照） |
| `audit/` | 审计日志 |
| `device/` `user/` `settings/` `stats/` `admin/` `ai/` `config/` `bootstrap/` | 设备 / 用户 / 设置 / 统计 / 后台 / AI / 配置 / 启动 |
| `workreport/` | 工作汇报模块：`controller/`（含 admin）/ `service/`（含 `push/` 飞书推送、`webhook/`、`CredentialEncryptor`）/ `repository/` / `entity/` / `dto/` |
| `office/`（规划） | Office Pro 实时校验和管理员授予 |
| `officeai/`（规划） | AI 网关、积分账本、用量、限流和供应商适配 |

迁移脚本：`server/src/main/resources/db/migration/V1..V15`（Flyway，已执行不可改）。

## Office Worker（规划）

| 目录 | 说明 |
|---|---|
| `tools/office-worker-windows/` | Windows Office COM/Interop 高保真 Worker，处理旧格式、宏、外链和主题 |
| `src-tauri/src/bin/office_ooxml_worker.rs` | 标准 OOXML ZIP/XML 安全处理 Worker |
| 应用数据 `office/office_tasks.db` | 本地任务历史；与剪贴板数据库隔离 |

## 管理后台 `admin-web/src/`

| 目录 | 说明 |
|---|---|
| `main.ts` | 管理后台入口 |
| `api/` | 8 个：`anonymousDevices` / `auth` / `devices` / `entitlements` / `request` / `settings` / `stats` / `users` |
| `router/index.ts` | vue-router 路由 |
| `stores/` `views/` `components/` `assets/` `types/` | 状态 / 视图 / 组件 / 资源 / 类型 |

## 测试命令

| 子系统 | 命令 |
|---|---|
| 后端 | `mvn -f server/pom.xml test` |
| 桌面端前端 | `npm --prefix . test` |
| 管理后台 | `npm --prefix admin-web test` |
| Rust | `cargo test --manifest-path src-tauri/Cargo.toml` |

## 端口

前端 dev **1420**（`vite.config.ts` strictPort）/ 后端 **8088**（`application.yml` + `.env.development` 的 `VITE_FILE_KEEPER_SERVER_URL`）。

## 关键文件清单（AI 必读优先级）

1. **`AGENTS.md`**（根）—— 每次开工必读。
2. **`workflow_output/docs/specs/PRD.md`** —— 做任何功能前必读。
3. 本文件 —— 找文件时读。
4. **`workflow_output/docs/plans/<功能>.plan.md`** —— 实现时按它走。
5. **`workflow_output/项目规范约束/新增业务模块规范.md`** —— 新增模块必读。
6. 各 specs / plans **底部的术语表** + `workflow_output/开发进度/<功能>/README.md`。
