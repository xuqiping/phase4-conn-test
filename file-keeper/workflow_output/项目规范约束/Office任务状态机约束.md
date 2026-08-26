# Office 任务状态机约束

> 适用于所有 Office 本地批处理功能的领域层约定。状态机不负责 SQLite、文件扫描、Worker 生命周期、日志或 UI。

## 1. 状态与迁移

- 状态固定为：`draft/preflight/awaiting_confirmation/queued/running/partial_success/succeeded/failed/cancelled`。
- 正常路径：`draft → preflight → awaiting_confirmation → queued → running`。
- `preflight` 可因预检失败进入 `failed`；`queued/running` 可因启动或执行失败进入 `failed`。
- 确认前、排队和运行阶段可取消；取消后进入 `cancelled`。
- `partial_success/succeeded/failed/cancelled` 均为终态，禁止再次跳转、完成或发布。
- 禁止跳过用户确认直接执行，禁止 `draft → running`，禁止重复发布和失败后发布。

## 2. 文件与输出不变量

- 源输入在领域模型中只能是 `readOnly`；若创建请求要求可写，返回 `OFFICE_SOURCE_WRITE_FORBIDDEN`。
- 单输出策略 `singleAtomic` 只允许一个预期输出：发布 1、失败 0 才成功；发布 0、失败 1 为失败；不得产生部分成功。
- 多输出策略 `multipleIndependent`：全部发布为成功，部分发布且部分失败为部分成功，全部失败为失败。
- 完成摘要必须满足预期数量大于 0、发布数加失败数等于预期数且加法不溢出；矛盾摘要不得改变当前状态。
- 只有 `running` 状态可提交完成摘要。

## 3. 边界与敏感信息

- Rust/JSON 使用 serde `camelCase`；前端 TypeScript 使用同名 camelCase 字段。
- `taskId/requestId` 使用 UUID 字符串；`requestId` 预留给未来服务端幂等与追踪，本约束不提前建立监控系统。
- 跨 Rust/TypeScript 的文件大小和计数不得超过 JavaScript 安全整数 `9_007_199_254_740_991`。
- 输入可保存仅限本机使用的路径，但领域错误的 `Display/Debug` 只输出稳定错误码，不拼接路径或正文。
- `messageKey/detailsJson` 只保存结构化诊断元数据；禁止放文档正文、密码、Token 或模型 Key。
- `OfficeTask` 领域聚合不允许从 JSON 直接反序列化；Chunk 2 必须通过边界 DTO + 校验恢复，禁止绕过状态机拼出伪成功状态。
- Chunk 1 的完成摘要是内部受信边界；Chunk 5 必须改为消费输出事务签发的发布凭证，普通调用方不得只凭计数把任务标为成功。
- Chunk 1 是纯领域层，不记录日志；后续持久化和运维能力必须消费这些稳定状态与错误码，不另造冲突枚举。

## 术语表

| 术语 | 大白话 |
|---|---|
| 终态 | 任务已经结束，不能再继续操作的状态 |
| 完成摘要 | 预期、成功发布和失败输出的数量汇总 |
| 全有或全无 | 单个最终文件要么完整发布，要么完全不发布 |
