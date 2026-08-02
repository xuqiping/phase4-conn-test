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
│  authorization(授权/离线Token) user device audit  │
│  workreport(汇报/推送/AI) ai settings stats admin │
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
| 持久化 | 桌面 JSON/tauri-plugin-store；服务端 PostgreSQL | 收藏量小无需 SQLite；服务端用关系库 |
| 授权 | JWT + 离线 Token + moduleCode | 支持售卖/设备/到期/离线 |
| 结构变更 | Flyway 版本脚本 | 可追溯可回滚，禁止手敲 SQL |

## 详细设计文档（按主题）

- [技术选型.md](技术选型.md) — 候选方案对比、体积预估、风险对策。
- [项目技术架构文档.md](项目技术架构文档.md) — 整体架构详述。
- [开发思路与落地规划.md](开发思路与落地规划.md) — 落地路径。
- 同目录 4 份 `*-design.md` — streaming-thinking / server-phase-1 / ai-config 等专项设计。

## 数据流要点

- **授权流**：桌面端登录 → 后端签 JWT + 离线 Token（含 allowed 模块）→ 桌面端 `commercialAuthStore.isModuleAllowed()` 门禁 + Rust 二次校验。
- **工作汇报流**：Rust 读 Git 日志 → 后端 AI 总结 → 定时推送飞书/企微 webhook。
- **安全边界**：业务数据（文件/剪贴板/截图/进程）只在本地，不上传；服务端只处理授权元数据 + 工作汇报文本。
