---
mode: "agent"
description: "运行应用并修复问题（Run 步）。启动、用 Playwright 验证、修错。"
---

# 3 · Run —— 运行应用并修错

你的任务：本地运行应用做功能测试，修复发现的问题。

## 规则
- 运行前先检查应用是否已在运行。
- **Web 应用**：用 **Playwright** 导航到默认端口。
- **桌面端应用**：按技术栈选工具——
  - PyQt / PySide：用 `pytest-qt` 测逻辑，`PyAutoGUI`/`lackey` 做 GUI 点击与截图回归，优先用 MSAA/UI Automation 或 `objectName` 定位，不用固定坐标；
  - Tauri：用 `cargo test` + Playwright 测 WebView + `tauri-driver` 做端到端；
  - Electron：用 Playwright Electron 模式；
  - 必须在目标系统跑「安装 → 启动 → 核心功能 → 退出/卸载」。
- 没运行就启动，再导航。**耐心等启动**：等 10 秒，最多重试 3 次再尝试解决启动问题。
- 应用跑起来后，导航/操作到目标模块/功能，验证是否正常工作。
- **若存在 `workflow_output/docs/user-ops/<功能名>用户操作手册.md`，按手册中「步骤 → 用户操作 → 界面变化 → 预期结果」逐项执行，记录实际结果并截图/录屏。**
- 发现问题就修，形成「运行→报错→修→再运行」循环。
- 参照以下文件理解既有实现，再动手修：
  - 目录结构 → `/agent-platform/workflow_output/docs/file_structure.md`
  - 需求规格 → `/agent-platform/workflow_output/docs/specs`
  - 功能代码速查表 → `/agent-platform/workflow_output/docs/feature-map`
  - 用户操作手册 → `/agent-platform/workflow_output/docs/user-ops`
  - 其他 `/docs` 下相关文件

> 非 Web / 桌面应用（如 Terraform）改用对应的 plan/apply 到沙箱环境验证。
