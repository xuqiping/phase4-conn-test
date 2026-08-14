# ADR-001 · 桌面框架选型 Tauri 2

> 状态：已决定 ｜ 日期：2026-08-13 ｜ 决策人：用户确认 + AI 建议

## 背景

产品定调为 Win/Mac 桌面客户端（用户决策 2026-08-13），需承载：复杂定制 UI（管道条/卡片流/HUD）、系统级能力（沙箱/托盘/全局快捷键/深链/CLI）、双端打包分发。

## 备选方案

| 方案 | 优势 | 劣势 |
|---|---|---|
| **Tauri 2**（Rust + 系统 Webview） | 包体 ~10MB 级（Electron 1/10）；内存占用低；Rust 内核天然承接沙箱/CLI/MCP/状态机等系统能力；安全模型好 | Webview 碎片化（Win 依赖 WebView2）；Rust 学习成本；生态较新 |
| Electron | 生态最成熟、调试工具全、团队熟悉度普遍高 | 包体 150MB+、内存常驻 500MB+；Node 层做系统安全隔离较弱 |
| 原生双端（WinUI/SwiftUI） | 体验最佳 | 两套代码，一人公司不可承受 |

## 决定

选 **Tauri 2**：UI 用 React 19 + TS（Webview 内），内核用 Rust workspace。

## 理由

1. 本产品的差异化能力（本地沙箱、Token 计量、状态机、CLI/MCP）都是系统级活儿，Rust 内核一步到位，Electron 则要在 Node 侧长期对抗安全问题。
2. 包体/内存是零代码用户对「轻」的直接感知（对照性能目标 PERF-01/04/08）。
3. 本机已有 Rust/MSVC 编译环境（voice-to-text 项目验证），环境风险已排除。

## 后果与代价

- Win 端需处理 WebView2 检测与引导安装（纳入安装器需求）。
- 复杂 Webview 交互（如预览点选层）需双端真机回归（已入 testing_strategy E2E matrix）。
- 若 Tauri 遇阻塞性坑，回退方案 Electron 已评估，前端代码（React）可平移。
