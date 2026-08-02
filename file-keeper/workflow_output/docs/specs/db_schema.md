# DB Schema · 数据模型

> Phase 1 产出。**事实 schema = Flyway 迁移脚本**，本文件是**表用途索引 + 关键约束**。
> 迁移目录：`server/src/main/resources/db/migration/V1..V15`（**已执行不可改**，改结构加新版本号）。

## 核心实体（按域分组）

### 授权与账号域（地基）
| 表 | 用途（大白话） | 关键字段 / 约束 |
|---|---|---|
| `users` | 注册用户 | id PK IDENTITY；关联 user_devices / entitlements |
| `user_devices` | 用户绑定的设备（按设备授权） | user_id → users |
| `user_module_entitlements` | 用户×模块授权（到期时间 / allowed） | `module_code` CHECK 约束含全部已注册模块 |
| `anonymous_device_trials` | 匿名设备试用记录（7 天试用 + 免费模块选择） | `free_module_code` CHECK |
| `admin_audit_logs` | 高风险操作审计（授权变更 / 设备禁用 …） | — |

### 工作汇报域（work-report 模块）
| 表 | 用途 | 来源迁移 |
|---|---|---|
| work_plan / 工作计划增强 | 计划与固定工作项 | V5/V6/V7/V8/V9 |
| inbound_messages | 收件箱（飞书等入站消息） | V12 |
| inspiration_notes | 灵感笔记 | V13 |
| inspiration_review_config | 灵感审阅配置 | V15 |
| push_target / push_credential | 推送目标与凭据（凭据加密） | V11 |

### 配置与 AI 域
| 表 | 用途 | 来源迁移 |
|---|---|---|
| ai_config | AI 模型配置 | V10 |
| system_settings | 全局默认配置 | — |

> 完整字段以各 `V<n>__*.sql` 脚本为准（SQL 是结构最终真相，脚本本身带注释）。各模块表的字段用处 / 关联大白话注解见对应 [../feature-map/](../feature-map/) 速查表的「数据库表与 SQL 注解」节。

## 通用字段约定（所有业务表）

- 继承 `common.BaseEntity`：`id` / `created_by` / `created_at` / `updated_by` / `updated_at` / `deleted`（`@TableLogic` 逻辑删除）。
- 主键 `GENERATED ALWAYS AS IDENTITY`；金额用 `DECIMAL`；状态字段注释写明取值含义；常用查询字段加索引。

## 表关系（生活化比喻）

- `users` 1—N `user_devices`：一个顾客（用户）有多张手环（设备）。
- `users` 1—N `user_module_entitlements`：顾客买了哪些游乐项目（模块）的票，每张票有到期时间。
- `users` 1—N `work_plan` / `inspiration_notes`：顾客的工作记录本。

## 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-07-15 | 创建数据模型索引（指向 Flyway） | 引入工作流，确立结构真相源 |
