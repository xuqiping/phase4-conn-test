# P05 建造期：任务编排与大白话层 · README

## 功能一句话

把审批后的施工计划变成可运行的任务流：AI 按 chunk 写代码、跑测试、提交存档点；用户能看懂每一步改了什么，随时回滚或追加指令继续跑。

## 受众

- **技术**：负责维护/扩展 DevPilot 客户端的开发者。
- **用户**：使用 DevPilot 自动 coding 的非技术/半技术用户。

## 已交付内容

- S0：L8 迁移（tasks 扩展 + task_events + checkpoints 增强）。
- S1：任务事件流持久化与 Tauri 实时推送。
- S2：chunk 任务编排器 + `execute_build`。
- S3：diff 大白话摘要 `summarize_diff`。
- S4：大白话翻译层 `PlainText` / `TermBubble`。
- S5：存档点列表与回滚。
- S6：追加指令续跑 `continue_task`。
- S7：Build 视图 + Dashboard 真实数据。

## 核心文件索引

| 模块 | 文件 | 说明 |
|---|---|---|
| 任务编排 | `src-tauri/crates/core-orchestrator/src/task_scheduler.rs` | Scheduler、HttpLlmClient、mock trait |
| diff 摘要 | `src-tauri/crates/core-orchestrator/src/diff_summarizer.rs` | git diff → LLM → 结构化摘要 |
| 事件流 | `src-tauri/crates/core-state/src/task_event.rs` | task_events 表访问 |
| 存档点 | `src-tauri/crates/core-state/src/checkpoint.rs` | checkpoints 查询与回滚 DB 逻辑 |
| IPC | `src-tauri/src/commands.rs` | execute_build/summarize_diff/list_checkpoints/rollback/continue_task/list_tasks/rounds/events |
| Build 视图 | `src-ui/views/Build.tsx` | 任务流、事件日志、追加续跑、时间轴 |
| 驾驶舱 | `src-ui/views/dashboard/Dashboard.tsx` | 缺陷/覆盖率/消耗 HUD |
| 大白话层 | `src-ui/lib/translator.ts`, `src-ui/components/plain/PlainText.tsx` | 术语表 + cheap 模型翻译 |

## 本地运行

```bash
cd DevPilot/PROJECT/desktop
npm install
npm run tauri dev
```

质量门：

```bash
# Rust
cd src-tauri
cargo fmt --all
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace

# 前端
cd ..
npm run test
npx tsc --noEmit
```

## 注意事项

- `execute_build` / `continue_task` / `summarize_diff` 需要 access token（走 `cloudApi.setAccessToken` 或本地 mock 网关）。
- rollback 会删除下游 checkpoints 并重置 tasks 为 pending，但目标 checkpoint 保留。
- 大白话翻译失败时自动回退原文，不阻塞界面。
