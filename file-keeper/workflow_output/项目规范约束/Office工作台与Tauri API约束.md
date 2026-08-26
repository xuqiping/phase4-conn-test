# Office 工作台与 Tauri API 约束

## 1. 命令与状态

- Office 命令统一放在 `commands/office.rs`，错误只返回稳定错误码，不拼接路径、密码或底层错误文本。
- 状态变化必须先由 `OfficeTaskStateMachine` 校验，再用数据库“预期旧状态”条件更新；禁止直接写任意状态。
- 未解决 blocking 问题时不能确认入队；具体处理器未注册时必须返回 `OFFICE_EXECUTION_ENGINE_NOT_READY`，不得进入 running。
- 应用启动只恢复发现未结束任务，不擅自重跑；用户可查看并取消，真正续跑由后续处理器实现。

## 2. 前端边界

- 前端 Pinia 可保存任务摘要、问题和状态，不得保存密码、文档正文或模型 Key。
- 阻断项在任何筛选条件下都必须显示；警告可以筛选，但不能把 warning 当作已解决。
- “已排队”只代表确认状态已持久化，不得翻译或展示为“处理中/成功”。
- 新控件必须使用语义化 button、label、select，支持 Tab/Enter/Space，文案进入中英文 locale。

## 3. 隐私与恢复

- 路径只在本机 Tauri IPC、内存和 `office_tasks.db` 中使用，不上传服务端、不进日志。
- 恢复列表只显示本地任务摘要；任何重新执行必须重新核对文件、输出目录、权益和风险。
- 取消只能作用于指定 taskId；接入 Worker 后还必须验证 PID 归属，禁止按进程名清理 Office。
