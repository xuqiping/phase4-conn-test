# 互动式 AI 工作助手 — 开发计划总路由

本项目把所有相关计划拆分到本目录下，降低查看进度时的 token 消耗。

## 快速入口

| 文件 | 用途 | 建议查看时机 |
|------|------|-------------|
| [progress.md](progress.md) | 总进度一览 | 每次想了解整体进度 |
| [phase1/progress.md](phase1/progress.md) | Phase 1 进度 | 跟踪 MVP 实施进度 |
| [phase1/plan.md](phase1/plan.md) | Phase 1 详细实现计划 | 开始写代码时 |
| [phase2/progress.md](phase2/progress.md) | Phase 2 进度 | 跟踪 NLP/灵感阶段 |
| [phase2/plan.md](phase2/plan.md) | Phase 2 详细路线图 | Phase 2 启动前 |
| [phase3/progress.md](phase3/progress.md) | Phase 3 进度 | 跟踪 AI 报告增强 |
| [phase3/plan.md](phase3/plan.md) | Phase 3 详细路线图 | Phase 3 启动前 |
| [phase4/progress.md](phase4/progress.md) | Phase 4 进度 | 跟踪体验优化 |
| [phase4/plan.md](phase4/plan.md) | Phase 4 详细路线图 | Phase 4 启动前 |

## 阶段总览

- **Phase 1（MVP）**：IM 入站 + Inbox + 飞书固定工作完成 — 已输出完整实现计划
- **Phase 2**：LLM 兜底 NLP + 灵感随记 + 钉钉/Slack webhook + IM 确认回复
- **Phase 3**：AI 报告增强（完成率、逾期日志、IM 录入、灵感摘要）
- **Phase 4**：每日灵感回顾、/help 指令、移动端轻量化、企业微信自建应用

> **模块规范说明：** 本系列计划不新增独立业务模块，全部在已注册的 `work-report` 模块内部增强。因此不需要新增 `moduleCode`、管理后台授权编辑器或 `FreeModuleSelector` 改造。新增客户端接口均已按规范校验 `MODULE_WORK_REPORT` 授权。

> 旧文件位置已废弃，请使用本目录下的文件。
