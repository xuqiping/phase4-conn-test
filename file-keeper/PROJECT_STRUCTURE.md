# File Keeper 项目结构

本文档描述 `file-keeper` 项目的源码与关键目录结构。

## 顶层目录

```
file-keeper/
├── src/                 # 桌面端前端 Vue 源码
├── src-tauri/           # Tauri + Rust 桌面端原生层
├── server/              # Spring Boot 授权与账号服务
├── admin-web/           # 管理后台 Vue 前端
├── tools/               # 周边工具（ocr-sidecar 等）
├── scripts/             # 图标生成等 Node 脚本
├── dist/                # 前端构建产物
├── docs/                # 项目文档
└── 项目相关文档/         # 设计规范、实施计划等
```

> 构建产物与依赖目录：`dist/`、`node_modules/`、`src-tauri/target/`、`server/target/` 不属于源码。

## 桌面端前端 `src/`

| 目录/文件 | 说明 |
|-----------|------|
| `main.ts` | Vue 应用入口 |
| `App.vue` | 主应用根组件 |
| `api/` | 后端 API 与 Tauri Rust 命令封装 |
| `components/` | Vue 组件库 |
| `composables/` | 组合式函数 |
| `locales/` | 国际化语言包 |
| `plugins/` | 自定义插件 |
| `stores/` | Pinia 状态管理 |
| `styles/` | 全局样式 |
| `types/` | TypeScript 类型定义 |
| `utils/` | 通用工具函数 |

### 工作汇报模块（work-report）新增文件

| 文件 | 说明 |
|------|------|
| `src/api/workReport.ts` | 工作汇报后端 API 封装 |
| `src/api/rustWorkReport.ts` | 工作汇报 Rust 命令封装（Git 日志、通知、导出） |
| `src/stores/workReportStore.ts` | 工作汇报状态管理 |
| `src/types/workReport.ts` | 工作汇报类型定义 |
| `src/api/__tests__/workReport.test.ts` | API 层单元测试 |
| `src/stores/__tests__/workReportStore.test.ts` | Store 单元测试 |

## Tauri Rust 后端 `src-tauri/src/`

| 目录/文件 | 说明 |
|-----------|------|
| `main.rs` | Tauri 应用入口 |
| `commands/` | 暴露给前端的 Tauri 命令 |
| `commands/work_report.rs` | 工作汇报 Rust 命令：Git 日志读取、本地通知、Markdown 导出 |
| `commands/auth.rs` | 离线 Token 设置/清除/校验 |
| `clipboard/` | 剪贴板内部服务 |
| `platform/` | 平台适配层 |
| `types/` | Rust 共享类型 |
| `utils/` | 通用工具 |

## Java 服务端 `server/src/main/java/com/superprogrammer/`

| 包/目录 | 说明 |
|---------|------|
| `admin/` | 后台管理接口 |
| `audit/` | 审计日志 |
| `authorization/` | 授权核心 |
| `common/` | 公共基础设施 |
| `config/` | 配置类 |
| `device/` | 设备管理 |
| `security/` | 安全认证 |
| `settings/` | 系统设置 |
| `stats/` | 统计数据 |
| `user/` | 用户模块 |
| `workreport/` | **工作汇报模块业务代码** |

### 工作汇报模块（workreport）主要文件

| 目录 | 说明 |
|------|------|
| `workreport/controller/` | 客户端与管理端 Controller |
| `workreport/service/` | 业务 Service：记录、计划、配置、报告、AI 总结、推送 |
| `workreport/repository/` | MyBatis-Plus Mapper |
| `workreport/entity/` | 数据实体 |
| `workreport/dto/` | 请求/响应 DTO |
| `workreport/service/push/` | 飞书等推送实现 |
| `workreport/service/CredentialEncryptor.java` | 推送凭据加密 |

## 管理后台 `admin-web/src/`

| 目录/文件 | 说明 |
|-----------|------|
| `main.ts` | 管理后台入口 |
| `api/` | 后台接口封装 |
| `views/` | 页面视图 |
| `stores/` | Pinia 状态 |

## 数据库迁移

| 目录 | 说明 |
|------|------|
| `server/src/main/resources/db/migration/` | Flyway 迁移脚本 |

工作汇报模块相关迁移：
- `V5__work_report_schema.sql`（假设版本号，以实际文件为准）

## 测试

| 子系统 | 测试命令 |
|--------|----------|
| 后端 | `mvn -f "file-keeper/server/pom.xml" test` |
| 桌面端前端 | `npm --prefix "file-keeper" test` |
| 管理后台 | `npm --prefix "file-keeper/admin-web" test` |
| Rust | `cargo test --manifest-path "file-keeper/src-tauri/Cargo.toml"` |

---

> 维护提示：新增模块或调整目录时，请同步更新本文件与 `项目相关文档/1_项目代码目录文档.md`。
