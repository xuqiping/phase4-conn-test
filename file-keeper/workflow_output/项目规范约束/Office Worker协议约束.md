# Office Worker 协议约束

> 适用于 OOXML Worker 和后续 Windows Office Worker。协议承载本地敏感路径，因此任何实现都必须遵守本文件。

## 1. 通信与消息

- 主进程与 Worker 只使用 stdin/stdout JSON Lines；路径、正文、密码和规则内容不得放入命令行参数。
- 每行最大 1 MiB，超限、非 UTF-8、畸形 JSON 和错误 Schema 必须转换为稳定错误码，禁止回显原始消息。
- Worker 开始处理前必须完成协议版本握手；主进程核对 Worker 返回 PID 与自己启动的 `Child` PID 一致。
- 请求至少具有 requestId；进入真实任务调度后同时具有 taskId。进度和终态响应必须回传原 requestId，避免串任务。

## 2. 生命周期

- 支持 handshake、heartbeat、cancel、shutdown 控制操作；业务操作可发送 progress，最终必须发送 result、cancelled 或 error。
- 心跳超时后先请求优雅关闭；取消默认等待最多 10 秒，再终止该会话自己持有的子进程。
- 绝不按进程名批量终止 Office/Worker，绝不清理用户在任务前已启动的 Word、Excel 或 PowerPoint。
- Worker 退出、管道断开、协议畸形和超时必须映射为稳定错误码，不得让 Tauri 主进程崩溃。

## 3. 脱敏与诊断

- Worker stderr 必须持续排空，主进程只记录行数或稳定诊断码，不保存或回显原文。
- `Debug/Display` 不得包含可执行文件路径、输入路径、输出路径、正文、密码或 Worker 原始错误。
- 可记录 taskId、requestId、PID、事件类型、耗时和稳定错误码；不得记录业务文件内容。

## 4. 当前取消语义

- OOXML inspect 当前为同步扫描；扫描期间不能抢占读取 cancel，主进程使用“优雅等待 10 秒后终止自有 Worker”保证可取消。
- 后续长耗时操作必须在内部循环检查取消令牌，不能永久依赖强制终止。

## 术语表

| 术语 | 大白话 |
|---|---|
| JSON Lines | 一行一条 JSON 消息，双方可以持续收发 |
| 握手 | 开工前先确认双方协议版本和进程身份 |
| PID 归属 | 只认主程序亲自启动并持有的那个子进程编号 |
