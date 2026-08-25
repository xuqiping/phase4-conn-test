# Office 效率增强功能架构规格

## 1. 架构基线

采用“主程序调度 + 双引擎隔离 Worker”：

```text
Vue Office 工作台
  ↕ Tauri invoke
Rust Office Orchestrator
  ├─ SQLite 任务库 / 输出事务 / 凭据适配
  ├─ OOXML Worker：标准 xlsx/docx/pptx 扫描与安全子集处理
  └─ Windows Office Worker：xls/xlsm/doc/ppt/pptm、宏、外链、高保真导出

Vue AI 助手 → Spring Boot AI Gateway → 模型供应商
                ├─ Office Pro 实时校验
                ├─ AI 积分预扣/结算
                └─ Key 仅在服务端密钥存储
```

## 2. 模块边界

- `src/components/office/`：向导、预览、字段/宏/链接冲突确认、历史和报告。
- `src/stores/office*`：前端任务、权益、AI 状态；不得持有密码和模型 Key。
- `src-tauri/src/office/`：任务状态机、扫描、SQLite、输出事务、Worker 协议、系统凭据。
- `src-tauri/src/bin/office_ooxml_worker.rs`：跨平台 OOXML ZIP/XML 处理；未修改部件原样保留。
- `tools/office-worker-windows/`：Windows 高保真 Worker；通过 Office COM/Interop 操作旧格式、宏和外链。
- `server/.../office/`：Office Pro 套餐、管理员授予、实时校验。
- `server/.../officeai/`：AI 网关、积分账本、限流、供应商调用和脱敏审计。

## 3. Worker 协议

- Rust 主进程以 JSON Lines（逐行 JSON）通过 stdin/stdout 通信，不在命令行参数中放路径、密码或正文。
- 请求含 `taskId`、操作类型、临时工作目录、规则文件引用和取消令牌；响应含阶段、进度、警告、校验摘要和错误码。
- Worker 只可访问任务白名单路径；输出只能写入任务临时目录。
- 心跳超时后先请求优雅退出，再终止 Worker；Office 子进程按任务实例追踪，禁止误杀用户原有 Office。

## 4. 双引擎路由

- 标准 OOXML 且风险扫描通过：优先 OOXML Worker。
- `.xls/.doc/.ppt`、宏、数字签名、复杂外链、复杂母版、导出 PDF：Windows Office Worker。
- 无 Office 或非 Windows：高保真功能显示不可用原因；安全子集仍可使用。
- 技术尖峰必须建立兼容矩阵，未达到保真标准的文件类型自动路由到高保真 Worker 或阻止执行。

## 5. AI 数据流

1. 桌面端本地提取列名、类型、统计摘要或用户明确选择的文本。
2. 本地敏感扫描并展示将发送的内容；默认脱敏。
3. 服务端验证 JWT、Office Pro、余额、请求大小、并发和幂等键。
4. 事务内预扣积分，服务端用密钥管理系统中的供应商 Key 发起请求。
5. 只接受白名单 schema；按供应商实际用量结算，失败释放预扣。
6. 返回建议，用户确认后才生成本地确定性执行计划。

## 6. 关键技术决策

- OOXML 采用 ZIP/XML 透传策略：未编辑部件不重新序列化，降低样式、宏附件和自定义 XML 丢失风险。
- Office COM 仅存在于隔离 Worker，不进入 Tauri 主进程。
- 本地任务库复用 `rusqlite`，单独建立 `office_tasks.db`，避免与剪贴板库互相影响。
- 系统凭据通过平台适配层访问 Windows Credential Manager、macOS Keychain、Linux Secret Service。
- Office Pro 不是模块开关：功能始终可见，只有超过免费额度的“开始执行”需要在线套餐校验。

## 7. 降级策略

- 服务端不可用：免费规模本地任务可执行；超额和 AI 请求不可执行。
- AI 不可用或余额不足：保留手工规则和确定性处理功能。
- Office Worker 不可用：提示安装/修复 Office，并列出仍可由 OOXML Worker 完成的操作。
- 磁盘不足、Worker 崩溃或取消：不发布最终文件，保留可诊断日志和可清理临时目录。

## 8. 术语表

| 术语 | 大白话 | 示例 |
|---|---|---|
| COM/Interop | Windows 上控制已安装 Office 的官方自动化接口 | 让 Excel 打开并保存 `.xlsm` |
| JSON Lines | 每行一条 JSON 消息 | Worker 持续报告进度 |
| 幂等键 | 防止同一请求重复扣费的编号 | 网络重试只结算一次 |
