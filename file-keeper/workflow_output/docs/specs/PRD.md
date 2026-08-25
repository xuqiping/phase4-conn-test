# PRD · File Keeper 产品需求文档

> SDD 唯一真相源。所有实现必须与本文件对齐；冲突时要么改实现，要么改本文档（并注明原因）。
> Phase 1 产出，迭代维护。本文件 ≤5000 tokens，详细内容指向各子文档，不在此重复堆砌。

## 1. 项目概述

- **一句话定位**：跨平台（Windows / macOS / Linux）桌面效率工具——一键打开 / 关闭 / 批量管理常用文件与程序，叠加剪贴板、截图、Office 批处理、工作汇报与安全 AI 助手。本地模块开放；Office Pro 仅提升批量规模并提供 AI 积分。
- **背景与动机**：详见 [项目分析报告](../项目分析/项目分析报告.md)（需求挖掘）+ [竞品优势](../项目分析/竞品优势.md) + [用户画像与场景](../项目分析/用户画像与场景.md)。
- **成功指标**：① 核心桌面启动和常用模块保持轻快；② Office 批处理源文件损坏事故为 0、真实任务成功率 ≥95%；③ 服务端身份隔离、Office Pro 实时校验和 AI 防滥用可靠。

## 2. 用户故事（摘要）

- 作为**个人办公者**，我希望一键打开/关闭常用文件与程序，以便减少重复操作。
- 作为**个人办公者**，我希望自动汇总工作汇报（Git 日志 + AI 总结）并推送到飞书/企业微信，以便省去手写周报。
- 作为**未登录用户**，我希望所有本地模块可用，并能在免费额度内执行 Office 批处理。
- 作为**Office Pro 用户**，我希望在线校验后执行大批量任务，并使用每月 AI 积分生成安全建议。
- 作为**管理员**，我希望授予/撤销 Office Pro、调整 AI 积分并审计高风险操作。
- 完整画像见 [用户画像与场景](../项目分析/用户画像与场景.md)。

## 3. 功能需求（当前全貌 · 详细见 Feature Map）

| 编号 | 模块（moduleCode） | 描述 | 匿名试用 | 离线 | 代码速查 |
|---|---|---|---|---|---|
| M1 | 文件管理 `files` | 收藏 / 分组 / 搜索 / 批量打开、一键用默认程序打开 | ✅ | ✅ | [02-文件管理](../feature-map/02-文件管理.feature-map.md) |
| M2 | 进程管理 `processes` | 查看某文件被哪些进程打开、精确关闭（窗口标题+路径匹配，非整进程 kill） | ✅ | ✅ | [03-进程管理](../feature-map/03-进程管理.feature-map.md) |
| M3 | 剪贴板 `clipboard` | 剪贴板监听 / 历史 / 管理 | ✅ | ✅ | [04-剪贴板](../feature-map/04-剪贴板.feature-map.md) |
| M4 | 截图 | 截屏 / 标注 / 覆盖层 | — | — | [05-截图](../feature-map/05-截图.feature-map.md) |
| M5 | 认证与服务端身份 | 登录 / JWT / 设备 active 状态 / 用户数据隔离 | — | ❌ | [06-认证与商业授权](../feature-map/06-认证与商业授权.feature-map.md) |
| M6 | 工作汇报 `work-report` | Git 日志读取 + AI 总结 + 定时推送（飞书/企微 webhook）+ 灵感笔记 | ❌ | ✅ | [07-工作汇报](../feature-map/07-工作汇报.feature-map.md) |
| M7 | AI 接入 | AI 配置 / 流式思考 / 会话 | — | — | [08-AI接入](../feature-map/08-AI接入.feature-map.md) |
| M8 | 互动式 AI 工作助手 | 收件箱 / 意图识别 / 提醒 / 多轮交互 | — | — | [开发进度/interactive-ai-work-assistant](../../开发进度/interactive-ai-work-assistant/README.md) |
| M9 | Office 效率增强 | Excel 拆分合并 / Word 批量替换 / PPT 合并外链 / AI 安全建议 | 免费规模 | ✅ | [Office 专项 PRD](Office效率增强功能.PRD.md) |
| — | 公共基础设施 | 全局快捷键 / 托盘 / 主题 / i18n / 设置 | — | — | [01-公共基础设施](../feature-map/01-公共基础设施.feature-map.md) |

> 早期 UI 基线（仅 M1/M2，v1.0）见 [功能需求总结](功能需求总结.md)；后续想法见 [我希望的额外功能](我希望的额外功能.md)。
> Feature Map 总索引：[00-索引](../feature-map/00-索引.md)。

## 4. 非功能需求

- **性能目标**：安装包 Win ≤12MB / macOS ≤14MB / Linux ≤18MB；毫秒级启动；收藏量数百条无卡顿。（详细目标可拆 `performance_goals.md`）
- **安全策略**：JWT access 15min + refresh 7天；推送凭据加密；业务数据默认不上传服务端；Office 密码进入系统凭据保险库；平台模型 Key 仅服务端持有；高风险操作审计。详见根 [AGENTS.md](../../../AGENTS.md) 与 [Office 安全规格](Office效率增强功能.security_strategy.md)。
- **跨平台**：Win / macOS / Linux；平台差异下沉 Rust `platform/`，绝不用固定屏幕坐标。
- **国际化**：中英双语（`src/locales/{zh-CN,en.ts}`）。

## 5. 架构

详见 [architecture.md](architecture.md)（索引到技术选型 / 技术架构文档）。一句话：**Tauri2 桌面外壳 + Vue3 渲染 + Rust 原生能力 + Spring Boot 授权/账号/工作汇报服务 + Naive UI 管理后台 + Python OCR 边车**。

## 6. 数据模型

详见 [db_schema.md](db_schema.md)。核心实体：users / user_devices / admin_audit_logs / work_report 系列；Office 新增套餐、AI 钱包、只追加账本和用量记录。旧模块授权/匿名试用表仅处于兼容清理范围，不得供新功能使用。

## 7. 测试策略

- 后端 JUnit（mvn test）：授权判定 / 匿名试用 / 离线 Token / 工作汇报业务。
- 桌面端 Vitest（npm test）：api 层 / store / 组件门禁。
- 管理后台 Vitest + build。
- Rust cargo test：命令授权通过/拒绝两条路径。
- 桌面端 GUI：关键路径自动化（tauri-driver / 可访问性 API）+ 次要路径人工走查；按 user-ops 手册逐项验证。范例见 [开发进度/interactive-ai-work-assistant/*-test-guide.md](../../开发进度/interactive-ai-work-assistant/)。
- Office 专项按 [测试策略](Office效率增强功能.testing_strategy.md) 建立真实 Office 兼容样本、Worker 故障注入和人工交互测试。

## 8. 边界与不做（防 scope creep）

- ❌ 不做全盘文件搜索（只搜收藏夹）。
- ❌ 业务数据不上传服务端（只传授权校验元数据）。
- ❌ 桌面端不引入 Naive UI / vue-router（破坏单 App.vue 架构；Naive UI 只在 admin-web）。
- ❌ 关闭进程不直接 kill 整进程，用窗口标题 + 路径精确匹配。
- ❌ AI 直接修改文件；❌ API Key 下发客户端；❌ Office 超额权益离线缓存。

## 9. 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-07-15 | 建立 PRD 真相源，反映当前 8 模块全貌 | 引入编程类可迭代工作流（specs before code） |
| 2026-08-25 | 同步取消旧模块门禁，并加入 Office 效率增强与 Office Pro 规模权益 | 用户批准 Office Phase 1 设计 |

## 10. 术语表（专业术语 · 大白话 · 案例）

| 术语 | 大白话 | 简单案例 |
|---|---|---|
| **Office Pro** | 不锁功能、只提升 Office 单任务规模并赠送 AI 积分的套餐 | 超过 100 文件时在线校验 |
| **AI 积分** | 服务端计量模型成本的整数额度 | 请求前预扣，成功后结算 |
| **Flyway** | 数据库结构版本管理工具，结构变更走版本脚本而非手敲 SQL | `V15__add_inspiration_review_config.sql` |
| **R<T>** | 后端统一响应壳：`{code,msg,data}` | `R.ok(data)` / `R.fail(ErrorCode)` |
| **Feature Map** | 功能-代码速查表：某功能涉及哪些前后端文件、调用链、技术原理 | feature-map/02-文件管理.feature-map.md |
