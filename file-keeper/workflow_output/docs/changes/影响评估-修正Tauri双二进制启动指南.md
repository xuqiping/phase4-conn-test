# 影响评估：修正 Tauri 双二进制启动指南

> 日期：2026-08-27
>
> 变更类型：小型文档变更
>
> 回滚锚点：`e7e8656482a389b61c02e4f5e219778c28b9fde0`

## 1. 变更目标与原因

新增 `office_ooxml_worker` 后，Rust crate 同时包含 `file-keeper` 和 `office_ooxml_worker` 两个二进制。原运行指南中的 `npm run tauri:dev` 会让 Cargo 因无法选择主程序而中止。本次将开发启动命令更正为显式指定 `file-keeper` 二进制。

## 2. 引用与影响范围

- `workflow_output/docs/run-guide/0_项目启动命令.md`：更新桌面端启动命令和常见错误处理。
- `workflow_output/docs/run-guide/快速开始.md`：更新 Tauri 开发启动命令和排错说明。
- `workflow_output/docs/run-guide/快速启动速查表.md`：更新命令、端口说明和报错速查。
- `workflow_output/docs/run-guide/Phase4-认证与token刷新修复-验证记录.md`：属于历史证据，不追溯修改。
- 不影响业务逻辑、Tauri API、Office Worker 协议、数据库或网络端口。

## 3. 联动与运维检查

- Tauri 仍通过 `beforeDevCommand` 自动启动 Vite。
- Office Worker 仍由桌面主程序按需调用，不增加手动启动步骤。
- 桌面端 Vite 开发端口仍为 `1420`。
- 无日志、监控、健康检查或降级路径变更。

## 4. 验证与回滚

- 已执行 `npm run tauri:dev -- --no-watch -- --bin file-keeper`。
- Tauri 实际生成 `cargo run --bin file-keeper --no-default-features --color always --`。
- Rust 编译完成并启动 `target/debug/file-keeper.exe`；之后为结束验证由人工发送 `Ctrl+C`。
- 如需回滚，恢复上述三份运行指南即可，无数据迁移或业务状态恢复需求。
