# Office 效率增强功能数据规格

## 1. 本地 SQLite：`office_tasks.db`

| 表 | 关键字段 | 用途 |
|---|---|---|
| `office_tasks` | id UUID、type、status、engine、rule_json、input_count、total_bytes、output_dir、created/started/finished_at | 任务主记录 |
| `office_task_inputs` | task_id、path、fingerprint、format、size、risk_flags、status、error_code | 输入清单与预检结果 |
| `office_task_outputs` | task_id、input_id、temp_path、published_path、checksum、status | 输出发布和恢复 |
| `office_task_issues` | task_id、scope、severity、code、message_key、details_json、resolved | 公式、字段、宏、链接等问题 |
| `office_task_events` | task_id、sequence、stage、progress、event_code、created_at | 可恢复进度与诊断事件 |

约束：不存密码、Office 正文、模型 Key、未脱敏 AI 请求或完整提示词；路径字段仅本机使用。

## 2. 服务端关系表

新增 Flyway 版本，不修改已执行脚本：

| 表 | 关键字段 | 约束 |
|---|---|---|
| `office_subscriptions` | user_id、plan_code、status、starts_at、expires_at、granted_by、reason | 一个用户最多一个 active Office Pro；管理员变更审计 |
| `office_ai_wallets` | user_id、available_credits、reserved_credits、version | 乐观锁，余额不得为负 |
| `office_ai_ledger` | user_id、type、amount、balance_after、request_id、operator_id、created_at | 只追加；request_id 唯一防重复结算 |
| `office_ai_usage` | request_id、user_id、feature、provider、model、input_units、output_units、charged_credits、status、latency_ms | 不保存正文和 Key |
| `office_ai_monthly_grants` | user_id、grant_month、amount、ledger_id | user_id + grant_month 唯一，月度赠送幂等 |

所有业务表包含项目统一审计字段与逻辑删除字段；金额/积分使用整数最小单位，禁止浮点。

## 3. 状态枚举

- 任务：`draft/preflight/awaiting_confirmation/queued/running/partial_success/succeeded/failed/cancelled`。
- 订阅：`active/expired/revoked`。
- 账本：`monthly_grant/top_up/reserve/settle/release/admin_adjustment`。
- AI 使用：`reserved/running/succeeded/failed/rejected`。

## 4. 保留与清理

- 本地任务历史默认保留 90 天，用户可设置 7/30/90/永久并手动清除。
- 临时目录在成功发布后清理；失败/取消任务保留 7 天供诊断，超过期限自动清理。
- 服务端账本和用量记录作为财务/防滥用证据长期保留；日志不含请求正文。

## 5. 术语表

| 术语 | 大白话 | 示例 |
|---|---|---|
| 乐观锁 | 更新余额前确认没有被别人抢先改过 | `version` 不一致就重试 |
| 只追加账本 | 不改旧记录，用新记录表达变化 | 充值、预扣、退回各一条 |
