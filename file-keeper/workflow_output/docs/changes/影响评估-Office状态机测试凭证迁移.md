# 影响评估：Office 状态机测试凭证迁移

> 日期：2026-09-03｜规模：小型测试一致性修复｜回滚锚点：`4982142`（最近一笔 file-keeper 提交）

## 变更目标与原因

完整 `cargo test` 无法编译，因为 `src-tauri/src/office/tests/state_machine_tests.rs` 仍调用已删除的 `complete_with_validated_summary`。生产状态机在提交 `3d137390` 后只接受 `OutputTransaction` 签发的 `PublicationReceipt`，旧测试没有同步迁移。

本次修复测试与当前安全边界的一致性：有效完成路径使用真实输出事务凭证；纯计数边界直接验证状态推导函数。定向测试同时暴露 Windows `canonicalize` 返回 `\\?\` 路径、而磁盘挂载点使用普通盘符路径，导致可用空间查询恒定失败；一并统一磁盘比较路径。禁止恢复裸 `OutputSummary` 完成入口。

## 引用与影响范围

| 对象 | 引用/影响 | 处理 |
|---|---|---|
| `office/state_machine.rs` | `complete_with_publication` 消费凭证；内部 `derive_completion_status` | 最多放宽到 `pub(super)` 供同模块测试，不改变 crate 外 API |
| `office/tests/state_machine_tests.rs` | 10 处旧方法调用导致编译失败 | 迁移测试辅助函数和断言 |
| `office/output.rs` | 真实凭证唯一签发处；Windows 路径前缀不一致导致磁盘识别失败 | 不新增绕过入口；仅规范化磁盘匹配路径，不改变空间阈值和发布规则 |
| Office 输出事务约束 | 明确禁止裸摘要 | 保持不变 |
| Office Feature Map / testing strategy | 记录 Phase 4 前迁移要求 | 修复后同步状态与测试口径 |
| 人工测试问题 1—4 Phase 4 | 被完整 Rust 测试阻断 | 修复后解除自动化入口阻断 |

## 联动、运维与安全

- 不改变 Office 用户流程、任务状态、SQLite、Worker、文件输出或 Tauri API；Windows 输出事务恢复正常磁盘识别。
- 回归状态机成功/部分成功/失败、非法计数、重复完成、任务 ID 不匹配。
- 不删除现有日志、降级、恢复或安全校验；不新增日志、依赖、网络请求或敏感数据。
- 测试临时输出只能位于系统临时目录，结束后清理；不得使用真实用户 Office 文件。

## 回滚

- 代码和文档独立小提交；失败时回退本次提交即可。
- 不回退 `PublicationReceipt` 安全边界，不恢复 `complete_with_validated_summary`。
