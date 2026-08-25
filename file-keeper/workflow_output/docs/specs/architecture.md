# Architecture · 系统架构（索引）

> Phase 1 产出。本文件是架构**索引与结论**，详细论证见同目录设计文档。≤5000 tokens。

## 系统架构（五件套全栈）

```
┌─────────────────────────────────────────────────┐
│  桌面客户端 (Tauri2)                              │
│  ├─ 渲染层 src/        Vue3 + TS + Tailwind(单App.vue) │
│  └─ 原生层 src-tauri/  Rust(commands + platform)     │
└───────────────┬─────────────────────────────────┘
                │ HTTP(JWT) / Tauri invoke
┌───────────────▼─────────────────────────────────┐
│  后端服务 server/  Spring Boot 3.2.5 (com.superprogrammer) │
│  auth/JWT user device audit office-pro/ai-ledger   │
│  workreport(汇报/推送/AI) ai settings stats admin  │
│  PostgreSQL + Redis + Flyway                      │
└───────────────┬─────────────────────────────────┘
                │
┌───────────────▼──────────┐   ┌──────────────────┐
│  管理后台 admin-web/      │   │  OCR 边车 tools/ │
│  Vue3 + Naive UI + router │   │  Python          │
└───────────────────────────┘   └──────────────────┘
```

## 技术选型结论

详见 [技术选型.md](技术选型.md)：选 **Tauri2 + Vue3 + TS + Vite + Pinia**，体积小（Win ≤12MB）、启动快、Rust 调系统级文件/进程能力强；排除 Electron（体积破 80MB）、Flutter（自绘无优势）。

## 关键技术决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 桌面外壳 | Tauri2（系统 WebView，不打包 Chromium） | 体积底线低 |
| 桌面端 UI | Tailwind + 单 App.vue（无 router） | 轻量；Tab 由 currentTab 控制 |
| 管理后台 UI | Naive UI + vue-router | 后台需要完整组件库 + 路由 |
| 持久化 | 桌面 Store + Office/剪贴板独立 SQLite；服务端 PostgreSQL | 大批任务明细需分页与恢复 |
| 权益 | 本地模块开放；Office Pro 超额任务实时校验；AI 积分账本 | 不复活旧 moduleCode 门禁或离线套餐 Token |
| 结构变更 | Flyway 版本脚本 | 可追溯可回滚，禁止手敲 SQL |

## 详细设计文档（按主题）

- [技术选型.md](技术选型.md) — 候选方案对比、体积预估、风险对策。
- [项目技术架构文档.md](项目技术架构文档.md) — 整体架构详述。
- [开发思路与落地规划.md](开发思路与落地规划.md) — 落地路径。
- [Office 架构规格](Office效率增强功能.architecture.md) — 双引擎 Worker、AI 网关和降级策略。
- 同目录 4 份 `*-design.md` — streaming-thinking / server-phase-1 / ai-config 等专项设计。

## 数据流要点

- **Office 权益流**：免费规模本地判定；超额任务携 JWT 实时校验 Office Pro，成功后只允许本次任务进入队列，不缓存离线 Pro 权限。
- **Office 处理流**：Vue 向导 → Rust 调度/SQLite/输出事务 → OOXML 或 Windows Office Worker → 校验并原子发布。
- **Office AI 流**：本地最小化/脱敏数据 → 服务端套餐、积分和限流 → 模型供应商 → schema 校验 → 返回待确认建议。
- **工作汇报流**：Rust 读 Git 日志 → 后端 AI 总结 → 定时推送飞书/企微 webhook。
- **安全边界**：业务数据（文件/剪贴板/截图/进程）只在本地，不上传；服务端只处理授权元数据 + 工作汇报文本。
