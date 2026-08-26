# Office 统一安全底座 Feature Map

## 1. 代码位置

| 层 | 文件 | 作用 |
|---|---|---|
| 入口 | `src/App.vue` | Office 标签与工作台挂载 |
| UI | `src/components/office/OfficeWorkspace.vue` | 新建/历史总容器 |
| UI | `OfficeTaskWizard.vue` / `OfficeIssueList.vue` / `OfficeTaskHistory.vue` | 选择、预检、阻断项、入队、恢复和历史 |
| 前端状态 | `src/stores/officeTaskStore.ts` | 当前任务、历史、恢复和错误码 |
| IPC | `src/api/office.ts`、`src/types/office.ts` | Tauri 调用与跨层 DTO |
| Tauri | `src-tauri/src/commands/office.rs` | 预检、确认、取消、历史、恢复、凭据命令 |
| 领域 | `office/types.rs`、`state_machine.rs` | ID、状态、事件和合法跳转 |
| 本地库 | `office/db.rs`、`migrations.rs`、`repository.rs` | SQLite 迁移、分页、恢复、敏感字段拒绝 |
| 安全预检 | `path_policy.rs`、`scanner.rs`、`risk.rs` | 真实路径、格式、指纹、风险与免费额度 |
| Worker | `protocol.rs`、`worker.rs`、`bin/office_ooxml_worker.rs` | JSONL、握手、心跳、取消和 OOXML 检查 |
| 输出 | `output.rs`、`recovery.rs` | 临时目录、校验、原子发布、清理和替换原件 |
| 凭据 | `credentials.rs`、`platform/*/office_credentials.rs` | 系统保险库、指纹绑定、清零和删除 |

## 2. 调用链

```text
OfficeTaskWizard
  → api/office.ts invoke
  → commands/office.rs
  → scanner + risk + path_policy
  → state_machine 校验状态
  → repository 写 office_tasks.db
  → DTO 返回问题、额度、引擎和是否可确认
```

执行链后续由具体处理器接入：`queued → Worker → OutputTransaction → PublicationReceipt → 状态机完成`。当前没有处理器时拒绝 start。

## 3. 技术原理大白话

| 技术 | 一句话原理 | 大白话案例 |
|---|---|---|
| 状态机 | 只允许任务沿批准路线前进 | 像机场登机：没安检、没验票就进不了登机口 |
| SQLite 事务 | 任务、输入和问题一起保存 | 像一次盖章整套归档，不能只存封面漏掉附件 |
| JSON Lines Worker | 主程序与隔离进程逐行交换消息 | 像对讲机每次说完整一句，坏一句不影响下一句 |
| 原子发布 | 文件完整验货后一次出现 | 像后厨做好整盘菜再端上桌，不给半盘生菜 |
| 系统凭据保险库 | 密码交给操作系统安全存储 | 像把钥匙放酒店保险箱，应用只拿取件编号 |
| SHA-256 指纹 | 用摘要确认文件没有被换掉 | 像核对封条编号，不打开内容也能发现包裹变化 |

> 旧格式、宏、签名、密码包和复杂外链仍受兼容矩阵闸门约束；路由到 Windows Worker 不等于当前已经支持执行。

## 4. 维护重点

- 新处理器必须消费 `PublicationReceipt` 完成任务，禁止直接写成功状态。
- 新问题码同时更新 Rust、TypeScript、中文和英文文案。
- 任何密码 UI 只能临时持有，不进入 Pinia、localStorage、日志或错误。
- Phase 4 必须迁移旧状态机测试到真实输出事务凭证流程。
