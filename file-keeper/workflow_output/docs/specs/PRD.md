# PRD · File Keeper 产品需求文档

> SDD 唯一真相源。所有实现必须与本文件对齐；冲突时要么改实现，要么改本文档（并注明原因）。
> Phase 1 产出，迭代维护。本文件 ≤5000 tokens，详细内容指向各子文档，不在此重复堆砌。

## 1. 项目概述

- **一句话定位**：跨平台（Windows / macOS / Linux）桌面效率工具——一键打开 / 关闭 / 批量管理常用文件与程序，叠加剪贴板、截图、工作汇报、AI 接入等模块；按账号 / 设备 / 时间售卖的模块授权 + 匿名试用 + 离线 Token。
- **背景与动机**：详见 [项目分析报告](../项目分析/项目分析报告.md)（需求挖掘）+ [竞品优势](../项目分析/竞品优势.md) + [用户画像与场景](../项目分析/用户画像与场景.md)。
- **成功指标**：① 安装包体积 Win ≤12MB、毫秒级启动；② 商业授权体系跑通（付费转化 + 离线可用）；③ 核心模块（文件 / 进程 / 剪贴板 / 工作汇报）稳定可用。

## 2. 用户故事（摘要）

- 作为**个人办公者**，我希望一键打开/关闭常用文件与程序，以便减少重复操作。
- 作为**个人办公者**，我希望自动汇总工作汇报（Git 日志 + AI 总结）并推送到飞书/企业微信，以便省去手写周报。
- 作为**匿名用户**，我希望试用核心模块，试用后选一个免费模块继续用。
- 作为**付费用户**，我希望按账号授权、支持离线使用，以便断网也能用已购模块。
- 作为**管理员**，我希望在后台配置用户授权/到期时间、查看统计、审计高风险操作。
- 完整画像见 [用户画像与场景](../项目分析/用户画像与场景.md)。

## 3. 功能需求（当前全貌 · 详细见 Feature Map）

| 编号 | 模块（moduleCode） | 描述 | 匿名试用 | 离线 | 代码速查 |
|---|---|---|---|---|---|
| M1 | 文件管理 `files` | 收藏 / 分组 / 搜索 / 批量打开、一键用默认程序打开 | ✅ | ✅ | [02-文件管理](../feature-map/02-文件管理.feature-map.md) |
| M2 | 进程管理 `processes` | 查看某文件被哪些进程打开、精确关闭（窗口标题+路径匹配，非整进程 kill） | ✅ | ✅ | [03-进程管理](../feature-map/03-进程管理.feature-map.md) |
| M3 | 剪贴板 `clipboard` | 剪贴板监听 / 历史 / 管理 | ✅ | ✅ | [04-剪贴板](../feature-map/04-剪贴板.feature-map.md) |
| M4 | 截图 | 截屏 / 标注 / 覆盖层 | — | — | [05-截图](../feature-map/05-截图.feature-map.md) |
| M5 | 认证与商业授权 | 登录 / 离线 Token / 模块权益门禁 / 匿名试用 | — | ✅ | [06-认证与商业授权](../feature-map/06-认证与商业授权.feature-map.md) |
| M6 | 工作汇报 `work-report` | Git 日志读取 + AI 总结 + 定时推送（飞书/企微 webhook）+ 灵感笔记 | ❌ | ✅ | [07-工作汇报](../feature-map/07-工作汇报.feature-map.md) |
| M7 | AI 接入 | AI 配置 / 流式思考 / 会话 | — | — | [08-AI接入](../feature-map/08-AI接入.feature-map.md) |
| M8 | 互动式 AI 工作助手 | 收件箱 / 意图识别 / 提醒 / 多轮交互 | — | — | [开发进度/interactive-ai-work-assistant](../../开发进度/interactive-ai-work-assistant/README.md) |
| — | 公共基础设施 | 全局快捷键 / 托盘 / 主题 / i18n / 设置 | — | — | [01-公共基础设施](../feature-map/01-公共基础设施.feature-map.md) |

> 早期 UI 基线（仅 M1/M2，v1.0）见 [功能需求总结](功能需求总结.md)；后续想法见 [我希望的额外功能](我希望的额外功能.md)。
> Feature Map 总索引：[00-索引](../feature-map/00-索引.md)。

## 4. 非功能需求

- **性能目标**：安装包 Win ≤12MB / macOS ≤14MB / Linux ≤18MB；毫秒级启动；收藏量数百条无卡顿。（详细目标可拆 `performance_goals.md`）
- **安全策略**：JWT access 15min + refresh 7天；推送凭据 `CredentialEncryptor` 加密；业务数据（文件路径/剪贴板/截图/进程）不上传服务端；离线 Token 签名校验；高风险操作审计 `admin_audit_logs`。详见根 [AGENTS.md](../../../AGENTS.md) + [新增业务模块规范](../../项目规范约束/新增业务模块规范.md)。
- **跨平台**：Win / macOS / Linux；平台差异下沉 Rust `platform/`，绝不用固定屏幕坐标。
- **国际化**：中英双语（`src/locales/{zh-CN,en.ts}`）。

## 5. 架构

详见 [architecture.md](architecture.md)（索引到技术选型 / 技术架构文档）。一句话：**Tauri2 桌面外壳 + Vue3 渲染 + Rust 原生能力 + Spring Boot 授权/账号/工作汇报服务 + Naive UI 管理后台 + Python OCR 边车**。

## 6. 数据模型

详见 [db_schema.md](db_schema.md)（索引到 Flyway `V1..V15`）。核心实体：users / user_devices / user_module_entitlements（模块授权）/ anonymous_device_trials（匿名试用）/ admin_audit_logs / work_report 系列（report_config / work_plan / inbound_message / inspiration_note 等）。

## 7. 测试策略

- 后端 JUnit（mvn test）：授权判定 / 匿名试用 / 离线 Token / 工作汇报业务。
- 桌面端 Vitest（npm test）：api 层 / store / 组件门禁。
- 管理后台 Vitest + build。
- Rust cargo test：命令授权通过/拒绝两条路径。
- 桌面端 GUI：关键路径自动化（tauri-driver / 可访问性 API）+ 次要路径人工走查；按 user-ops 手册逐项验证。范例见 [开发进度/interactive-ai-work-assistant/*-test-guide.md](../../开发进度/interactive-ai-work-assistant/)。

## 8. 边界与不做（防 scope creep）

- ❌ 不做全盘文件搜索（只搜收藏夹）。
- ❌ 业务数据不上传服务端（只传授权校验元数据）。
- ❌ 桌面端不引入 Naive UI / vue-router（破坏单 App.vue 架构；Naive UI 只在 admin-web）。
- ❌ 关闭进程不直接 kill 整进程，用窗口标题 + 路径精确匹配。

## 9. 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-07-15 | 建立 PRD 真相源，反映当前 8 模块全貌 | 引入编程类可迭代工作流（specs before code） |

## 10. 术语表（专业术语 · 大白话 · 案例）

| 术语 | 大白话 | 简单案例 |
|---|---|---|
| **moduleCode** | 模块代号，授权体系按它开关功能 | `files`/`processes`/`work-report` |
| **离线 Token** | 登录时签发的一份"离线通行证"，断网也能验已购模块 | JWT，含 allowed 模块清单 |
| **匿名试用** | 不登录先全功能试用 7 天，到期后只能用选定的 1 个免费模块 | anonymous_device_trials 表 |
| **Flyway** | 数据库结构版本管理工具，结构变更走版本脚本而非手敲 SQL | `V15__add_inspiration_review_config.sql` |
| **R<T>** | 后端统一响应壳：`{code,msg,data}` | `R.ok(data)` / `R.fail(ErrorCode)` |
| **Feature Map** | 功能-代码速查表：某功能涉及哪些前后端文件、调用链、技术原理 | feature-map/02-文件管理.feature-map.md |
