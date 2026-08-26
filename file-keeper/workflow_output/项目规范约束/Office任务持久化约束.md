# Office 任务持久化约束

> 适用于本地 `office_tasks.db`。该数据库只保存任务事实和恢复信息，不保存 Office 正文、密码或模型密钥。

## 1. 迁移与事务

- Office 使用独立 SQLite 数据库，不与剪贴板数据库混用。
- 本地迁移通过 `office_schema_migrations` 记录版本；已发布版本不得修改，只能增加下一版本。
- 首次迁移已有数据库前创建一次 `pre-migration-v1.bak` 备份。
- 任务主记录、输入和问题必须在同一事务保存；任一子记录失败则全部回滚。
- 外键始终开启，任务删除依靠级联清理明细。

## 2. 数据安全

- 表结构禁止出现密码、API Key、Authorization、Token、文档正文或完整 Prompt 字段。
- `rule_json/details_json` 必须是版本化结构；写入前递归拒绝敏感键。
- 路径是仅本机恢复信息，可以进入 SQLite，但不得进入服务端请求或普通错误文本。
- Repository 错误只返回稳定错误码，不拼接 SQL、路径或 JSON 内容。

## 3. 查询与清理

- 历史列表必须分页，单页最大 200；禁止一次加载全部任务和明细。
- 可恢复任务只查询 `preflight/awaitingConfirmation/queued/running`。
- 默认清理 90 天前已结束任务；运行中和等待确认任务不得自动清理。
- 常用查询必须使用 `status + created_at`、`task_id + status/resolved/sequence` 索引，避免 N+1。

## 术语表

| 术语 | 大白话 |
|---|---|
| 级联清理 | 删除任务时数据库自动删除它的输入、问题和事件 |
| 版本化 JSON | JSON 带明确结构版本，升级时知道该怎样兼容旧记录 |
