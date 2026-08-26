# Office 统一安全底座实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: `phase3-implement`。只含伪代码，按复选框执行。

**目标：** 提供所有 Office 功能共用的任务状态机、SQLite、预检、Worker 管理、输出事务、凭据和恢复能力。

**架构：** Vue 向导调用 Tauri 命令；Rust 主进程持有任务库和 Worker 生命周期；Worker 只能写临时目录。

---

### Chunk 1：领域类型与失败用例（已完成）

- [x] **目标：先用测试锁定状态和安全不变量。**
  - 动作：定义任务/输入/输出/问题/引擎/状态类型；编写状态跳转、源只读、单输出全有或全无、多输出部分成功的失败测试。
  - 涉及文件：`src/types/office.ts`、`src-tauri/src/office/types.rs`、`src-tauri/src/office/state_machine.rs`、`src-tauri/src/office/tests/state_machine_tests.rs`、`src-tauri/src/office/mod.rs`。
  - 依赖：技术尖峰。
  - 伪代码：`transition(current,event) -> allowed next state | DOMAIN_ERROR`。
  - 验证：测试先因实现缺失失败；非法跳转、重复发布、失败后发布均被拒绝。

### Chunk 2：SQLite 任务库

- [x] **目标：任务可分页、重启恢复且不存敏感正文。**
  - 动作：创建 `office_tasks.db` 迁移、Repository、分页查询和 90 天清理；规则/问题使用版本化 JSON。
  - 涉及文件：`src-tauri/src/office/db.rs`、`src-tauri/src/office/repository.rs`、`src-tauri/src/office/migrations.rs`、`src-tauri/src/office/tests/repository_tests.rs`。
  - 依赖：Chunk 1。
  - 伪代码：`transaction(save task + inputs + issues); query summary page; never persist password/body/key`。
  - 验证：10,000 任务分页；中断重开恢复；敏感字符串扫描为零。
  - 当前状态：实现与编译检查已完成；按用户要求，分页规模、恢复和敏感字段自动化测试统一留到 Phase 4。

### Chunk 3：路径扫描与风险预检

- [x] **目标：在执行前产生完整、可解释的问题清单。**
  - 动作：规范化路径、解析符号链接、识别格式/大小/指纹/占用/密码/宏/外链；实现免费额度聚合。
  - 涉及文件：`src-tauri/src/office/scanner.rs`、`src-tauri/src/office/path_policy.rs`、`src-tauri/src/office/risk.rs`、`src-tauri/src/office/tests/scanner_tests.rs`。
  - 依赖：Chunk 2。
  - 伪代码：`scan each path streaming -> append input + issues -> aggregate count/bytes/max`。
  - 验证：目录穿越、软链接越界、输入输出重叠、100/101 文件边界准确。
  - 当前状态：路径策略、风险扫描和免费额度聚合已实现并通过编译检查；按用户要求，目录穿越/符号链接/额度边界自动化测试留到 Phase 4。

### Chunk 4：Worker 生命周期与协议

- [x] **目标：处理进程崩溃不拖垮主程序。**
  - 动作：实现 JSON Lines 协议、心跳、进度、取消、超时、PID 归属和 stderr 脱敏；注册 OOXML Worker 骨架。
  - 涉及文件：`src-tauri/src/office/worker.rs`、`src-tauri/src/office/protocol.rs`、`src-tauri/src/bin/office_ooxml_worker.rs`、`src-tauri/src/office/tests/worker_contract_tests.rs`、`src-tauri/Cargo.toml`。
  - 依赖：Chunk 1。
  - 伪代码：`spawn with stdin/stdout pipes -> handshake -> heartbeat -> events; cancel -> graceful 10s -> kill owned pid`。
  - 验证：假 Worker 卡死/崩溃/输出畸形均转换为稳定错误码；用户 Office 进程不受影响。
  - 当前状态：共享协议、握手/PID 校验、心跳、进度回调、取消、超时、优雅关闭、stderr 脱敏计数和自有子进程终止已实现并通过两个 Rust 二进制编译检查；卡死/崩溃/畸形输出契约测试按用户要求留到 Phase 4。

### Chunk 5：输出事务与恢复

- [x] **目标：不暴露半成品，不损坏源文件。**
  - 动作：估算空间、创建临时目录、校验输出、原子发布；由输出事务签发不可由普通调用方伪造的 `PublicationReceipt`（发布凭证），状态机只消费凭证完成任务；实现多输出部分成功和单输出回滚；替换原件独立命令。
  - 涉及文件：`src-tauri/src/office/output.rs`、`src-tauri/src/office/recovery.rs`、`src-tauri/src/office/tests/output_tests.rs`。
  - 依赖：Chunk 2、4。
  - 伪代码：`write temp -> reopen/checksum/invariants -> atomic rename; replace source -> verify fingerprint -> backup -> swap`。
  - 验证：没有有效发布凭证不能把任务标为成功；磁盘不足、目标重名、外部修改、取消和断电模拟不产生伪成功文件。
  - 当前状态：空间预算、任务临时目录、格式/业务校验、SHA-256 复核、不覆盖原子发布、不可构造发布凭证、部分成功汇总、7 天恢复清理和带备份回滚的独立替换操作已实现并通过编译检查；故障注入与断电模拟留到 Phase 4。

### Chunk 6：系统凭据保险库

- [ ] **目标：支持记住 Office 密码且不落普通存储。**
  - 动作：实现跨平台凭据接口；绑定规范化路径+文件指纹；文件变化后要求重新确认；设置页可删除。
  - 涉及文件：`src-tauri/src/office/credentials.rs`、`src-tauri/src/platform/windows/office_credentials.rs`、`src-tauri/src/platform/macos/office_credentials.rs`、`src-tauri/src/platform/linux/office_credentials.rs`、`src-tauri/src/office/tests/credentials_tests.rs`。
  - 依赖：Chunk 3。
  - 验证：SQLite/日志无密码；删除后不可恢复；指纹变化不自动使用旧密码。

### Chunk 7：Tauri API 与可访问向导

- [ ] **目标：前端能创建、预检、确认、执行、取消、恢复和查看历史。**
  - 动作：注册命令/API/store；制作分步向导、问题列表、确认页、进度页和历史页；所有控件具备 label、焦点和键盘顺序。
  - 涉及文件：`src-tauri/src/commands/office.rs`、`src-tauri/src/commands/mod.rs`、`src-tauri/src/main.rs`、`src/api/office.ts`、`src/stores/officeTaskStore.ts`、`src/components/office/OfficeWorkspace.vue`、`src/components/office/OfficeTaskWizard.vue`、`src/components/office/OfficeIssueList.vue`、`src/components/office/OfficeTaskHistory.vue`、`src/locales/zh-CN.ts`、`src/locales/en.ts`。
  - 依赖：Chunk 1–6。
  - 验证：Vitest + cargo test；键盘完成全流程；取消/返回不会丢失已确认规则；批量问题支持筛选但不隐藏阻断项。

### Chunk 8：文档检查点

- [ ] 更新底座 Feature Map、人工测试方案、用户手册与开发进度；运行 `npm test`、`cargo test`；创建只含本 Chunk 文件的存档提交。

## 术语表

| 术语 | 大白话 | 示例 |
|---|---|---|
| 状态机 | 规定任务允许怎样前进或失败 | 未确认不能直接 running |
| 文件指纹 | 判断文件是否还是原来那份的摘要 | 大小、修改时间和哈希 |
