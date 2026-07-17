# 互动式 AI 工作助手 — 傻瓜式全功能测试流程

> **适用版本**：Phase 1 ~ Phase 4 全部完成后（2026-06-30 状态）。  
> **目标**：不读代码、不翻文档，按顺序执行即可验证 AI 工作助手全链路。  
> **预计耗时**：后端启动 2 分钟 + 执行约 30 分钟（不含等待定时任务）。

---

## 一、测试前准备

### 1.1 环境要求

| 组件 | 要求 |
|------|------|
| 后端 | `file-keeper/server` 已启动，Flyway 迁移执行到 V15 |
| 数据库 | PostgreSQL 可访问，能执行 SQL |
| 前端 | `file-keeper` 桌面端可登录（用于可视化验证） |
| 网络 | 能访问 IM 平台回调地址（内网穿透如 ngrok 或公网域名） |
| LLM | `ai_configs` 中已配置可用模型（Phase 2 复杂句需要） |

启动后端：

```bash
cd file-keeper/server
./mvnw spring-boot:run
```

### 1.2 变量说明

以下变量贯穿全文，执行前请一次性替换：

```bash
BASE_URL="http://localhost:8080"          # 后端地址
DEVICE_ID="test-device-001"               # 任意设备 ID
USERNAME="your-username"                  # 登录用户名
PASSWORD="your-password"                  # 登录密码
TOKEN=""                                  # 登录后回填
USER_ID=""                                # 登录后回填
FEISHU_CHAT_ID="oc_123"                   # 飞书群 chat_id
DINGTALK_CHAT_ID="cid123"                 # 钉钉群 chat_id
SLACK_CHANNEL="C123"                      # Slack 频道 ID
WECOM_CHAT_ID="ww123"                     # 企业微信应用 AgentId / 群 ID
```

### 1.3 登录获取 Token

```bash
curl -s -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" | tee /tmp/login.json
```

回填变量（Windows 用 Git Bash 可直接执行，CMD/PowerShell 请手动从 JSON 复制）：

```bash
TOKEN=$(jq -r '.data.accessToken' /tmp/login.json)
USER_ID=$(jq -r '.data.userId' /tmp/login.json)
echo "TOKEN=$TOKEN"
echo "USER_ID=$USER_ID"
```

**预期结果**：`code=200`，`data.accessToken` 与 `data.userId` 均非空。

---

## 二、基础数据准备

### 2.1 确认 work-report 模块已授权

进入桌面端「权限管理」→ 给用户勾选 **工作汇报(work-report)** 模块；或调用管理后台接口授权。

验证（应返回 `code=200`，列表中包含 `work-report`）：

```bash
curl -s "$BASE_URL/api/client/work-report/configs?deviceId=$DEVICE_ID" \
  -H "Authorization: Bearer $TOKEN" | jq '.code, .msg'
```

### 2.2 创建固定工作项

创建两条固定工作，用于后续 IM 标记完成：

```bash
# 固定工作 A：日报设计
curl -s -X POST "$BASE_URL/api/client/work-report/fixed-work?deviceId=$DEVICE_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "日报设计",
    "recurrenceType": "DAILY",
    "reminderTime": "09:00:00",
    "reminderEnabled": true
  }' | jq '.'

# 固定工作 B：周会汇报
curl -s -X POST "$BASE_URL/api/client/work-report/fixed-work?deviceId=$DEVICE_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "周会汇报",
    "recurrenceType": "WEEKLY",
    "reminderDays": "1",
    "reminderTime": "10:00:00",
    "reminderEnabled": true
  }' | jq '.'
```

**预期结果**：两条均返回 `code=200`，记录 `data.id`。

### 2.3 创建推送凭据与目标

#### 2.3.1 飞书

```bash
# 创建凭据
curl -s -X POST "$BASE_URL/api/client/work-report/push-credentials?deviceId=$DEVICE_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "飞书测试机器人",
    "platform": "FEISHU",
    "credential": "{"webhookUrl":"https://open.feishu.cn/open-apis/bot/v2/hook/xxx","secret":"yyy"}"
  }' | jq '.'

# 记录 credentialId，例如 100
FEISHU_CRED_ID=100

# 创建推送目标
curl -s -X POST "$BASE_URL/api/client/work-report/push-targets?deviceId=$DEVICE_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"飞书测试群\",
    \"platform\": \"FEISHU\",
    \"targetType\": \"GROUP\",
    \"targetId\": \"$FEISHU_CHAT_ID\",
    \"credentialId\": $FEISHU_CRED_ID
  }" | jq '.'
```

**预期结果**：`code=200`，目标创建成功。

> 钉钉、Slack、企业微信同理，platform 分别填 `DINGTALK`、`SLACK`、`WECHAT_WORK`。

### 2.4 创建报告配置

```bash
curl -s "$BASE_URL/api/client/work-report/templates?deviceId=$DEVICE_ID" \
  -H "Authorization: Bearer $TOKEN" | jq '.data[] | {id,name}'
```

选一个模板 ID（例如 `1`），创建配置：

```bash
curl -s -X POST "$BASE_URL/api/client/work-report/configs?deviceId=$DEVICE_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "测试周报",
    "reportType": "WEEKLY",
    "templateId": 1,
    "cronExpression": "0 0 9 ? * MON",
    "timezone": "Asia/Shanghai",
    "enabled": true,
    "aiEnabled": true,
    "includeInspirationDigest": true,
    "inspirationReviewEnabled": true,
    "pushTargetIds": []
  }' | jq '.'
```

**预期结果**：`code=200`，记录 `data.id` 作为 `CONFIG_ID`。

---

## 三、Phase 1 测试：IM 入站 + Inbox + 飞书固定工作完成

### 3.1 模拟飞书 Webhook 消息

由于真实飞书回调需要公网地址，测试时用 curl 模拟平台回调：

```bash
curl -s -X POST "$BASE_URL/api/client/work-report/webhook/feishu" \
  -H "Content-Type: application/json" \
  -d "{
    \"header\": {\"event_type\": \"im.message.receive_v1\"},
    \"event\": {
      \"message\": {
        \"message_id\": \"om_test_$(date +%s)\",
        \"chat_id\": \"$FEISHU_CHAT_ID\",
        \"content\": \"{\\\"text\\\":\\\"完成日报设计\\\"}\"
      },
      \"sender\": {
        \"sender_id\": \"ou_test\",
        \"sender_info\": {\"name\": \"测试员\"}
      }
    }
  }" | jq '.'
```

**预期结果**：`code=200`。

### 3.2 数据库验证

```sql
SELECT id, user_id, platform, raw_text, intent, confidence, status, target_module
FROM inbound_messages
WHERE platform = 'FEISHU'
ORDER BY created_at DESC LIMIT 1;
```

**预期结果**：
- `intent` = `complete_fixed_work`
- `confidence` >= 0.85
- `status` = `CONFIRMED`
- `target_module` = `fixed_work`

```sql
SELECT c.*, i.content
FROM fixed_work_completions c
JOIN fixed_work_items i ON c.item_id = i.id
WHERE c.completion_source = 'IM'
ORDER BY c.created_at DESC LIMIT 1;
```

**预期结果**：`completed=true`，`completion_source='IM'`，`i.content` 包含“日报设计”。

### 3.3 查询 Inbox

```bash
curl -s "$BASE_URL/api/client/work-report/inbox?deviceId=$DEVICE_ID&limit=50" \
  -H "Authorization: Bearer $TOKEN" | jq '.data[] | {id,rawText,status,intent}'
```

**预期结果**：能看到上一条消息，状态为 `CONFIRMED`。

### 3.4 低置信度消息进入 Inbox 待确认

模拟一条模糊消息：

```bash
curl -s -X POST "$BASE_URL/api/client/work-report/webhook/feishu" \
  -H "Content-Type: application/json" \
  -d "{
    \"header\": {\"event_type\": \"im.message.receive_v1\"},
    \"event\": {
      \"message\": {
        \"message_id\": \"om_test_low_$(date +%s)\",
        \"chat_id\": \"$FEISHU_CHAT_ID\",
        \"content\": \"{\\\"text\\\":\\\"搞了一下那个\\\"}\"
      },
      \"sender\": {\"sender_id\": \"ou_test\",\"sender_info\": {\"name\": \"测试员\"}}
    }
  }" | jq '.'
```

桌面端操作：
1. 打开「工作汇报」→「互动收件箱」Tab。
2. 应看到状态为 `PENDING` 的卡片。
3. 点击「确认」，选择或自动匹配到「日报设计」。

或接口确认：

```bash
# 先拿到 pending 消息的 id，例如 200
PENDING_ID=200
curl -s -X POST "$BASE_URL/api/client/work-report/inbox/$PENDING_ID/confirm?deviceId=$DEVICE_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "action": "CONFIRM",
    "correctedPayload": {"task_name":"日报设计","date":"2026-06-30"}
  }' | jq '.'
```

**预期结果**：`code=200`，`data.status=CONFIRMED`。

---

## 四、Phase 2 测试：NLP + 灵感随记 + 多平台

### 4.1 复杂句 NLP 与相对日期

测试 LLM 兜底 + 相对日期解析，发送：

```bash
for TEXT in "昨天完成了日报设计" "上周一搞定了周会汇报" "6月25日做完日报设计"; do
  curl -s -X POST "$BASE_URL/api/client/work-report/webhook/feishu" \
    -H "Content-Type: application/json" \
    -d "{
      \"header\": {\"event_type\": \"im.message.receive_v1\"},
      \"event\": {
        \"message\": {
          \"message_id\": \"om_test_$(date +%s%N)\",
          \"chat_id\": \"$FEISHU_CHAT_ID\",
          \"content\": \"{\\\"text\\\":\\\"$TEXT\\\"}\"
        },
        \"sender\": {\"sender_id\": \"ou_test\",\"sender_info\": {\"name\": \"测试员\"}}
      }
    }" | jq '.code'
done
```

**数据库验证**：

```sql
SELECT raw_text, parsed_payload->>'date' as parsed_date, status
FROM inbound_messages
WHERE raw_text IN ('昨天完成了日报设计','上周一搞定了周会汇报','6月25日做完日报设计')
ORDER BY created_at DESC;
```

**预期结果**：
- `parsed_date` 为正确 ISO 日期（如昨天、上周一、2026-06-25）。
- 若 LLM 正常，`status=CONFIRMED`。
- `fixed_work_completions` 对应日期有完成记录。

### 4.2 工作记录 NLP

```bash
curl -s -X POST "$BASE_URL/api/client/work-report/webhook/feishu" \
  -H "Content-Type: application/json" \
  -d "{
    \"header\": {\"event_type\": \"im.message.receive_v1\"},
    \"event\": {
      \"message\": {
        \"message_id\": \"om_test_log_$(date +%s)\",
        \"chat_id\": \"$FEISHU_CHAT_ID\",
        \"content\": \"{\\\"text\\\":\\\"今天做了需求评审和接口设计\\\"}\"
      },
      \"sender\": {\"sender_id\": \"ou_test\",\"sender_info\": {\"name\": \"测试员\"}}
    }
  }" | jq '.'
```

**验证**：

```sql
SELECT * FROM work_logs
WHERE source = 'IM'
ORDER BY created_at DESC LIMIT 1;
```

**预期结果**：`content` 包含“需求评审和接口设计”，`source='IM'`，`platform_message_id` 非空。

### 4.3 灵感随记 NLP

```bash
curl -s -X POST "$BASE_URL/api/client/work-report/webhook/feishu" \
  -H "Content-Type: application/json" \
  -d "{
    \"header\": {\"event_type\": \"im.message.receive_v1\"},
    \"event\": {
      \"message\": {
        \"message_id\": \"om_test_insp_$(date +%s)\",
        \"chat_id\": \"$FEISHU_CHAT_ID\",
        \"content\": \"{\\\"text\\\":\\\"灵感：AI 日报应支持情感分析 #产品/灵感\\\"}\"
      },
      \"sender\": {\"sender_id\": \"ou_test\",\"sender_info\": {\"name\": \"测试员\"}}
    }
  }" | jq '.'
```

**验证**：

```sql
SELECT * FROM inspiration_notes
WHERE source = 'IM'
ORDER BY created_at DESC LIMIT 1;
```

**预期结果**：`content` 完整保存，`tags` 包含 `产品/灵感`，`platform_message_id` 非空。

桌面端验证：
1. 打开「工作汇报」→「灵感随记」Tab。
2. 应看到刚保存的灵感，标签显示为 `#产品/灵感`。

### 4.4 钉钉 Webhook

```bash
curl -s -X POST "$BASE_URL/api/client/work-report/webhook/dingtalk" \
  -H "Content-Type: application/json" \
  -d "{
    \"msgtype\": \"text\",
    \"text\": {\"content\": \"完成周会汇报\"},
    \"senderStaffId\": \"test_user\",
    \"conversationId\": \"$DINGTALK_CHAT_ID\",
    \"msgId\": \"dt_test_$(date +%s)\"
  }" | jq '.'
```

**预期结果**：`code=200`。

### 4.5 Slack Webhook

```bash
curl -s -X POST "$BASE_URL/api/client/work-report/webhook/slack" \
  -H "Content-Type: application/json" \
  -H "X-Slack-Signature: v0=fake_sig" \
  -H "X-Slack-Request-Timestamp: $(date +%s)" \
  -d "{
    \"event\": {
      \"type\": \"message\",
      \"channel\": \"$SLACK_CHANNEL\",
      \"user\": \"U123\",
      \"text\": \"今天做了代码重构\",
      \"ts\": \"$(date +%s).000000\"
    }
  }" | jq '.'
```

**预期结果**：`code=200`。

### 4.6 IM 确认回复验证

如果真实凭据有效，以上 4.1 ~ 4.5 步骤执行后，IM 机器人应回复类似：

```
✅ 已记录完成：日报设计
📝 已记录工作日志：需求评审和接口设计
💡 已保存灵感：AI 日报应支持情感分析 #产品/灵感
```

若使用 mock 凭据，查看后端日志确认 `sendConfirmation` 输出：

```bash
grep -E "已记录|已保存|确认回复" file-keeper/server/logs/*.log
```

### 4.7 固定工作完成日历

桌面端：
1. 打开「工作汇报」→「固定工作」Tab。
2. 切换到日历视图。
3. 点击不同日期，应能看到已完成/未完成的标记。

接口验证：

```bash
# 查询今天
curl -s "$BASE_URL/api/client/work-report/fixed-work?deviceId=$DEVICE_ID&type=DAILY&date=2026-06-30" \
  -H "Authorization: Bearer $TOKEN" | jq '.data[] | {content,completed}'
```

**预期结果**：「日报设计」和「周会汇报」至少部分日期为 `completed=true`。

---

## 五、Phase 3 测试：AI 报告增强

### 5.1 生成周报

使用 2.4 创建的配置 ID：

```bash
CONFIG_ID=1  # 替换为实际 id

curl -s -X POST "$BASE_URL/api/client/work-report/reports/generate?deviceId=$DEVICE_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"configId\": $CONFIG_ID}" | jq '.'
```

**预期结果**：`code=200`，`data.aiSummary` 非空。

### 5.2 验证完成率与逾期

获取报告详情：

```bash
REPORT_ID=1  # 替换为实际 report id
curl -s "$BASE_URL/api/client/work-report/reports/$REPORT_ID?deviceId=$DEVICE_ID" \
  -H "Authorization: Bearer $TOKEN" | jq '.'
```

**预期结果**：
- `data.fixedWorkCompletionRate` 或正文包含完成率。
- 正文列出本周逾期任务（如有）。

桌面端：
1. 打开「报告查看器」。
2. 正文下方应显示「固定工作完成率」小卡片。

### 5.3 验证灵感摘要

由于报告中包含 `includeInspirationDigest=true`，AI 总结应出现「灵感速览」段落。

**预期结果**：`data.aiSummary` 包含类似：

```text
灵感速览：
- AI 日报应支持情感分析 #产品/灵感
```

---

## 六、Phase 4 测试：体验优化

### 6.1 /help 指令菜单

```bash
curl -s -X POST "$BASE_URL/api/client/work-report/webhook/feishu" \
  -H "Content-Type: application/json" \
  -d "{
    \"header\": {\"event_type\": \"im.message.receive_v1\"},
    \"event\": {
      \"message\": {
        \"message_id\": \"om_test_help_$(date +%s)\",
        \"chat_id\": \"$FEISHU_CHAT_ID\",
        \"content\": \"{\\\"text\\\":\\\"/help\\\"}\"
      },
      \"sender\": {\"sender_id\": \"ou_test\",\"sender_info\": {\"name\": \"测试员\"}}
    }
  }" | jq '.'
```

**验证**：

```sql
SELECT * FROM inbound_messages
WHERE raw_text = '/help'
ORDER BY created_at DESC LIMIT 1;
```

**预期结果**：`intent='help'`，`status='CONFIRMED'`，`target_module='help'`。

IM 应收到菜单回复：

```text
可用指令：
- 完成 [任务名]
- 今天做了 [工作内容]
- 灵感：[内容] #标签
- /help
```

### 6.2 每日灵感回顾

#### 6.2.1 准备未回顾灵感

确保 `inspiration_notes` 中有 `reviewed_at` 为空的记录：

```sql
SELECT id, content, reviewed_at FROM inspiration_notes
WHERE user_id = $USER_ID AND reviewed_at IS NULL
LIMIT 5;
```

#### 6.2.2 触发灵感回顾（可选）

若不想等到每天 9 点，可临时调用调度器或直接执行服务方法。测试阶段可通过修改系统时间或调用管理接口触发。

检查后端日志：

```bash
grep "灵感回顾" file-keeper/server/logs/*.log
```

**预期结果**：日志显示推送成功，且 `inspiration_notes.reviewed_at` 被更新。

### 6.3 企业微信 Webhook

#### 6.3.1 URL 验证

```bash
curl -s "$BASE_URL/api/client/work-report/webhook/wecom?msg_signature=abc&timestamp=123&nonce=xyz&echostr=hello" \
  -H "Authorization: Bearer $TOKEN"
```

**预期结果**：返回 `hello`（echostr 原样返回）。

#### 6.3.2 接收消息

```bash
curl -s -X POST "$BASE_URL/api/client/work-report/webhook/wecom?msg_signature=abc&timestamp=123&nonce=xyz" \
  -H "Content-Type: text/xml" \
  -d "<xml>
    <ToUserName>corp</ToUserName>
    <FromUserName>user001</FromUserName>
    <CreateTime>$(date +%s)</CreateTime>
    <MsgType>text</MsgType>
    <Content>完成日报设计</Content>
    <MsgId>wecom_test_$(date +%s)</MsgId>
    <AgentID>$WECOM_CHAT_ID</AgentID>
  </xml>" | jq '.'
```

**验证**：

```sql
SELECT * FROM inbound_messages
WHERE platform = 'WECHAT_WORK'
ORDER BY created_at DESC LIMIT 1;
```

**预期结果**：新增一条 `WECHAT_WORK` 记录，`intent='complete_fixed_work'`。

---

## 七、安全与异常测试

### 7.1 未授权访问 Inbox

```bash
curl -s "$BASE_URL/api/client/work-report/inbox?deviceId=$DEVICE_ID&limit=50" \
  -H "Authorization: Bearer invalid_token"
```

**预期结果**：HTTP 401 或 `R` 包装 code=401/403。

### 7.2 无模块权限访问

用一个**已登录但没有 work-report 权限**的用户 Token 访问：

```bash
curl -s "$BASE_URL/api/client/work-report/inbox?deviceId=$DEVICE_ID&limit=50" \
  -H "Authorization: Bearer $NO_AUTH_TOKEN" | jq '.'
```

**预期结果**：`code=403`，`msg` 包含“未授权访问工作汇报模块”。

### 7.3 越权确认他人消息

假设存在另一条 `user_id` 不同的 `inbound_messages` 记录（id=999）：

```bash
curl -s -X POST "$BASE_URL/api/client/work-report/inbox/999/confirm?deviceId=$DEVICE_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"CONFIRM"}' | jq '.'
```

**预期结果**：`code=403` 或 `404`，提示无权处理。

### 7.4 Webhook 签名验证（Phase 2 后开启）

若后端已启用飞书验签，使用错误签名请求：

```bash
curl -s -X POST "$BASE_URL/api/client/work-report/webhook/feishu" \
  -H "Content-Type: application/json" \
  -H "X-Lark-Signature: wrong" \
  -H "X-Lark-Request-Timestamp: $(date +%s)" \
  -H "X-Lark-Request-Nonce: nonce" \
  -d '{"test":"bad"}'
```

**预期结果**：HTTP 401。

---

## 八、数据库清理（可选）

测试完成后清理测试数据：

```sql
-- 按 platform_message_id 删除测试消息
DELETE FROM inbound_messages WHERE platform_message_id LIKE 'om_test_%' OR platform_message_id LIKE 'dt_test_%' OR platform_message_id LIKE 'wecom_test_%';

-- 删除测试灵感
DELETE FROM inspiration_notes WHERE platform_message_id LIKE 'om_test_%';

-- 删除测试工作记录
DELETE FROM work_logs WHERE platform_message_id LIKE 'om_test_%';

-- 删除测试固定工作
DELETE FROM fixed_work_completions WHERE item_id IN (
  SELECT id FROM fixed_work_items WHERE content IN ('日报设计','周会汇报')
);
DELETE FROM fixed_work_items WHERE content IN ('日报设计','周会汇报');

-- 删除测试推送目标与凭据（请按实际 id）
-- DELETE FROM report_push_targets WHERE target_id IN ('oc_123','cid123','C123','ww123');
-- DELETE FROM push_credentials WHERE name = '飞书测试机器人';

-- 删除测试报告配置与报告
DELETE FROM work_reports WHERE config_id = $CONFIG_ID;
DELETE FROM report_configs WHERE id = $CONFIG_ID;
```

---

## 九、前端一键验收清单

| 模块 | 操作 | 预期 |
|------|------|------|
| Inbox | 打开「互动收件箱」 | 显示 IM 消息卡片，含平台、发送人、意图、置信度 |
| Inbox | 点击「确认」 | 卡片状态变 CONFIRMED，固定工作/日志/灵感被写回 |
| Inbox | 点击「忽略」 | 卡片状态变 IGNORED |
| 灵感随记 | 添加标签筛选 | 只显示对应标签的灵感 |
| 灵感随记 | 快捷输入 | 输入「灵感：xxx #标签」后保存成功 |
| 固定工作 | 日历视图 | 不同日期显示完成/未完成状态 |
| 报告 | 生成周报 | 正文含完成率、逾期、灵感速览 |
| 报告配置 | 开启「灵感回顾」 | 配置保存成功，每天 9 点触发 |

---

## 十、常见问题速查

| 现象 | 排查 |
|------|------|
| Webhook 返回 401 | 检查验签是否开启；MVP 阶段飞书验签被注释，需关闭或提供正确签名 |
| Webhook 返回 404 | 检查 `push_targets` 中 platform + target_id 是否匹配 chat_id |
| Inbox 为空 | 检查 `inbound_messages` 中 `user_id` 是否等于当前登录用户 |
| LLM 复杂句未识别 | 检查 `ai_configs` 是否配置且可用；查看 `LlmIntentClient` 日志 |
| 灵感回顾未推送 | 检查 `report_configs.inspiration_review_enabled=true`；检查当前时间是否为配置时区 9:00 |
| 企业微信 URL 验证失败 | 当前 MVP 直接返回 echostr；生产环境需配置 encodingAESKey |
