# Phase 1 端到端手动验证清单

## 前置条件

1. 本地 PostgreSQL 已启动，数据库已初始化。
2. 后端已启动：`./mvnw -pl server spring-boot:run`（Windows 用 `mvn -pl server spring-boot:run`）。
3. 桌面端前端已启动：`npm run dev` 或 `npm run tauri:dev`。
4. 飞书开放平台已创建企业自建应用，并开启机器人能力。

## 飞书机器人配置

1. 订阅事件：`im.message.receive_v1`。
2. 设置请求 URL：`https://<你的域名>/api/client/work-report/webhook/feishu`。
   - 本地开发可使用内网穿透工具（如 ngrok）。
3. 在桌面端工作汇报 → 推送配置中：
   - 新增 Feishu 凭据（App ID / App Secret）。
   - 新增推送目标：平台选择 Feishu，目标类型 GROUP，目标 ID 填入飞书群 `chat_id`。

## 验证步骤

### 1. 高置信度消息自动完成固定工作

1. 在飞书群中发送：`完成日报设计`。
2. 等待 5 秒。
3. 检查数据库：
   ```sql
   select * from inbound_messages where platform = 'FEISHU' order by created_at desc limit 1;
   ```
   期望：
   - `status` = `CONFIRMED`
   - `intent` = `complete_fixed_work`
   - `parsed_payload` 包含 `{"task_name":"日报设计","date":"today"}`

4. 检查固定工作完成记录：
   ```sql
   select * from fixed_work_completions where completion_source = 'IM' order by created_at desc limit 1;
   ```
   期望：
   - `completed` = true
   - `completion_source` = `IM`
   - `item_id` 对应名为包含"日报设计"的固定工作

### 2. 低置信度消息进入 Inbox

1. 在飞书群中发送：`搞定`。
2. 检查数据库：
   ```sql
   select * from inbound_messages where raw_text = '搞定' order by created_at desc limit 1;
   ```
   期望：
   - `status` = `PENDING`
   - `intent` = `complete_fixed_work`
   - `confidence` < 0.85

3. 打开桌面端工作汇报 → 互动收件箱 Tab。
4. 应看到"搞定"消息卡片，状态为"低置信度"。
5. 点击"确定"，检查数据库中该消息 `status` 变为 `CONFIRMED`。

### 3. 工作记录意图

1. 在飞书群中发送：`今天做了需求评审`。
2. 检查数据库：
   ```sql
   select * from work_logs where source = 'IM' order by created_at desc limit 1;
   ```
   期望：
   - `content` = `需求评审`
   - `source` = `IM`
   - `platform_message_id` 已填充

### 4. 授权校验

1. 使用未授权 `work-report` 模块的用户登录桌面端。
2. 访问工作汇报模块。
3. 期望：页面提示未授权，无法看到 Inbox 内容。
4. 或直接调用 API：
   ```bash
   curl -H "Authorization: Bearer <未授权用户token>" \
        "http://localhost:8088/api/client/work-report/inbox?deviceId=test"
   ```
   期望返回：
   ```json
   {"code":403,"msg":"未授权访问工作汇报模块","data":null}
   ```

## 常见问题

- **Q: 飞书回调返回 401**
  A: MVP 阶段验签逻辑默认注释，请确认 URL 可访问且未启用强制验签配置。

- **Q: 消息进入 InboundMessage 但无法匹配固定工作**
  A: 确认固定工作内容包含消息中的任务名称，且 `push_targets` 中 `target_id` 与飞书群 `chat_id` 一致。

- **Q: Inbox 面板不刷新**
  A: MVP 使用 30 秒轮询，可手动点击"刷新"按钮；SSE 端点已就绪，后续可替换为 EventSource。
