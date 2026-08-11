# 视频生成第三轮问题修复

类型：C（用户可见功能 + 后端任务链路）。日期：2026-08-11。

## 用户地图

- 视频生成用户：筛选历史、直接预览结果、恢复旧参数与附件、连续引用多个素材、查看实际发送参数。
- 无限画布用户：长时间生成不被本地轮数误判失败，多次 `@` 与视频页行为一致。
- 运维人员：Worker 不被长任务占死；单次网络异常退避重试；请求快照可审计但不落媒体正文。

## 技术结果

- 历史筛选在 SQL 层同时执行 ownership、提示词字面子串和 `[from,to)` 时间范围。
- 详情返回完整历史参数、输入附件摘要，以及平台参数/Provider 脱敏快照。
- 历史视频和资产图片/视频使用鉴权懒预览并释放 objectURL。
- Worker 每次认领只 create/query 一次，RUNNING 或查询异常安排下次认领；无业务总超时。
- Provider 实际 body 只构建一次，POST 前保存同源脱敏快照；data URI 替换为 fileId/MIME/bytes/SHA-256。

## 文档入口

- [实现计划](../../docs/plans/视频生成第三轮人工测试问题修复.plan.md)
- [API 契约](../../docs/api/媒体生成.md)
- [Feature Map](../../docs/feature-map/SeedDance视频生成.feature-map.md)
- [用户操作手册](../../docs/user-ops/SeedDance视频生成用户操作手册.md)
- [Phase 4 测试方案](../../docs/测试方案/视频生成第三轮问题修复测试方案.md)
- [Phase 3 进度](开发进度2-Phase3.md)

## DoD

- [x] 计划内 7 个问题已实现。
- [x] 安全边界：ownership、参数绑定、附件复检、data URI 脱敏。
- [x] API/Feature Map/User-Ops/问题单已同步。
- [x] 自动化测试与前后端构建已执行。
- [ ] Phase 4 浏览器真任务与韧性实测。
