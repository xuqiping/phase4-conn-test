# 企业微信 Webhook 接入说明

## 重要差异

| 能力 | 企业微信普通群机器人 | 企业微信自建应用 |
|------|---------------------|-----------------|
| 发送消息 | ✅ 支持 webhook 推送 | ✅ 支持应用消息/机器人消息 |
| 接收用户消息 | ❌ 不支持 | ✅ 需配置「接收消息」回调 |
| 接收 @机器人 消息 | ❌ 不支持 | ✅ 支持 |
| 文件/图片收发 | ❌ 不支持 | ✅ 支持（需额外开发） |
| 签名验证 | 加签（较简单） | msg_signature + AES 加密 |
| 适用场景 | 单向推送通知 | 完整 IM 交互（AI 助手） |

**结论：** 若要让 AI 工作助手在企业微信中实现「用户发消息 → 机器人识别意图 → 自动记录/回复」，必须使用**自建应用**的回调模式，不能使用普通群机器人 webhook。

## 后端入口

- URL：`POST /api/client/work-report/webhook/wecom`
- 同样支持 GET 用于 URL 验证
- 平台标识：`WECHAT_WORK`

## 自建应用配置步骤

1. 登录企业微信管理后台 → 应用管理 → 自建 → 创建应用。
2. 记录 `AgentId`、`Secret`。
3. 进入应用详情 → 接收消息 → 设置 API：
   - URL：`https://<你的域名>/api/client/work-report/webhook/wecom`
   - Token：自定义 token，用于签名验证
   - EncodingAESKey：点击随机生成，43 位
4. 保存后企业微信会发送 GET 验证请求，后端需返回解密后的 `echostr`。
5. 在桌面端工作汇报 → 推送配置中：
   - 新增 WeCom 凭据（Credential）：名称随意，平台选「企业微信」，凭据内容暂不使用。
   - 新增推送目标：平台选「企业微信」，目标类型 GROUP，目标 ID 填入企业微信群的 `chat_id`（或测试阶段先填应用 AgentId）。

## 当前实现

- `WeComWebhookAdapter`：解析明文/加密消息，提供 `decrypt` 与 `verifySignature` 方法。
- `WeComWebhookController`：处理 URL 验证与消息回调。

## 生产环境 TODO

1. 在 `application.yml` 中配置 `work-report.wecom.token` 与 `work-report.wecom.encoding-aes-key`。
2. 在 `WeComWebhookController.verifyUrl` 中调用 `weComWebhookAdapter.decrypt(echostr, encodingAesKey)` 并返回明文。
3. 在 `WeComWebhookController.receive` 中先调用 `weComWebhookAdapter.verifySignature(...)` 验签，再调用 `decrypt` 解密 `Encrypt` 字段。
4. 目前 MVP 阶段直接解析明文 XML，可正常用于未加密的测试回调。
