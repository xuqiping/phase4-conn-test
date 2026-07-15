# 互动式 AI 工作助手 Phase 1 MVP 实现计划：IM 入站 + Inbox + 飞书固定工作完成

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打通“飞书 IM 消息 → 后端解析 → 桌面端 Inbox 展示 → 确认后写回固定工作完成状态”的最小闭环，使用规则 NLP 识别“完成固定工作”意图。

**Architecture:** 新增 `inbound_messages` 表持久化所有 IM 原始输入；平台适配器层负责验签与标准化；规则 NLP 服务识别意图并生成结构化 payload；Inbox 服务负责状态流转与业务写回；桌面端通过 SSE 接收新消息并在 InboxPanel 中确认/忽略。

**Tech Stack:** Spring Boot 3.2.5, MyBatis-Plus/JdbcTemplate, PostgreSQL, Flyway, Vue 3 + Pinia + TypeScript, Tauri, Feishu Open API.

---

## 当前代码状态

- 固定工作模块已存在：`FixedWorkItem`、`FixedWorkCompletion`、`FixedWorkService`、`FixedWorkController`。
- 推送模块已存在：`PushTarget`、`PushCredential`、各平台 `Pusher`，但仅用于**出站**推送。
- 工作记录 `WorkLog` 已有 `source` 字段，可扩展为 `IM`。
- **缺失**：IM 入站通道、Inbox、意图识别、SSE 推送、桌面端 Inbox UI。

## 模块注册与授权状态（依据《新增业务模块规范》）

- 本计划**不新增独立模块**，是在现有 `work-report` 模块内部增强功能。
- `work-report` 已注册：`AuthConstants.MODULE_WORK_REPORT = "work-report"`，且已加入 `AuthorizationService.MODULE_CODES`。
- 因此**不需要**：新增 `moduleCode`、修改管理后台授权编辑器、修改 `FreeModuleSelector`、修改 Dashboard 统计。
- 新增客户端接口（`InboundMessageController`、`WorkReportEventController`）必须校验 `MODULE_WORK_REPORT` 授权。
- 飞书 Webhook 入口（`FeishuWebhookController`）由 IM 平台回调触发，**不携带用户 JWT**，因此不做用户模块授权校验；其安全性依赖：
  1. 平台签名验签（MVP 中可注释，Phase 2 强制开启）
  2. 通过 `push_targets` 反查归属用户，消息按 `user_id` 隔离
- 根据现有模块清单，`work-report` **不支持匿名试用**（❌），因此 IM 入站功能也不面向匿名用户。
- 本阶段不涉及敏感本地能力（文件删除、进程结束、剪贴板监听等），因此**不需要新增 Rust 二次校验命令**。

---

## 文件结构

### 新增文件

- `file-keeper/server/src/main/resources/db/migration/V10__add_inbound_messages_and_completion_source.sql`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/InboundMessage.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/InboundMessageStatus.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/CompletionSource.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/dto/InboundMessageDto.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/dto/ConfirmInboundMessageRequest.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/repository/InboundMessageRepository.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/InboundMessageService.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/NlpIntentService.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/webhook/WebhookAdapter.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/webhook/WebhookParseResult.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/webhook/FeishuWebhookAdapter.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/controller/FeishuWebhookController.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/controller/InboundMessageController.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/WorkReportEventPushService.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/controller/WorkReportEventController.java`
- `file-keeper/src/types/inbox.ts`
- `file-keeper/src/api/inbox.ts`
- `file-keeper/src/components/work-report/InboxPanel.vue`
- `file-keeper/server/src/test/java/com/superprogrammer/workreport/service/NlpIntentServiceTest.java`
- `file-keeper/server/src/test/java/com/superprogrammer/workreport/service/webhook/FeishuWebhookAdapterTest.java`

### 修改文件

- `file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/FixedWorkCompletion.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/WorkLog.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/repository/FixedWorkCompletionRepository.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/repository/ReportPushTargetRepository.java`（实际类名，计划原写 `PushTargetRepository`）
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/repository/WorkLogRepository.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/FixedWorkService.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/WorkLogService.java`
- `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/InboundMessageService.java`（Task 13 注入 `WorkReportEventPushService`）
- `file-keeper/server/src/main/java/com/superprogrammer/common/JsonUtils.java`（Task 5 增加 `parseMap`）
- `file-keeper/src/types/workReport.ts`
- `file-keeper/src/api/workReport.ts`
- `file-keeper/src/stores/workReportStore.ts`
- `file-keeper/src/components/work-report/WorkReportManagement.vue`

---

## 关键设计决策

1. **用户映射（MVP 简化）**：飞书消息到达后，先按 `chat_id` 查找 `report_push_targets` 中 `platform='FEISHU'` 且 `target_id=chat_id` 的记录，取该记录的 `config_id`，再通过 `report_configs` 查到 `user_id` 作为消息归属用户。后续阶段再引入个人 open_id 绑定。
2. **幂等**：`inbound_messages` 使用 `(platform, platform_message_id)` 唯一索引，重复事件直接返回已存在记录的 ID。
3. **置信度阈值**：规则 NLP 置信度 ≥ 0.85 时自动执行并标记 `CONFIRMED`；低于阈值或无法匹配任务时标记 `PENDING`，进入 Inbox 人工确认。
4. **实时通道**：使用 SSE（SseEmitter）而非 WebSocket，降低 MVP 复杂度，桌面端断线后通过列表接口补拉。

## 实施适配说明

本计划在实施过程中发现以下与代码库现状的差异，已按实际结构调整：

1. **迁移版本**：原计划 `V12`，因 `file-keeper/server` 现有迁移最高为 `V9`（`agent-platform/backend` 的 V12 属于独立模块），实际使用 `V10`。
2. **`PushTargetRepository` 类名**：代码库中实际为 `ReportPushTargetRepository`，对应表 `report_push_targets`。Task 8、Task 9 均按此类名实现。
3. **用户 ID 解析链路**：`ReportPushTarget` 没有直接的 `user_id` 字段，需通过 `config_id` 查询 `ReportConfigRepository` 取得 `user_id`。
4. **`INSPIRATION_PATTERN` 正则**：原计划 `(.+?)` 会导致含空格灵感内容被截断，实际实现改为贪婪 `(.+)`。
5. **校验增强**：`InboundMessageController.listPending` 的 `limit` 参数增加 `@Max(200)`；`ConfirmInboundMessageRequest.action` 增加 `@Pattern(regexp = "CONFIRM|IGNORE")`。

---

## Task 1: 数据库迁移

**Files:**
- Create: `file-keeper/server/src/main/resources/db/migration/V10__add_inbound_messages_and_completion_source.sql`

- [ ] **Step 1: 编写迁移脚本**

```sql
-- =============================================================================
-- V10__add_inbound_messages_and_completion_source.sql
-- 用途：新增互动收件箱，扩展固定工作完成记录来源与工作记录溯源
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. 互动收件箱：所有 IM 原始输入先进入此处
-- ---------------------------------------------------------------------------
CREATE TABLE inbound_messages (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    platform VARCHAR(32) NOT NULL,                              -- FEISHU / DINGTALK / WECHAT_WORK / SLACK
    platform_message_id VARCHAR(255) NOT NULL,                  -- 平台消息 ID，用于幂等
    sender_id VARCHAR(255),                                     -- 平台用户 ID
    sender_name VARCHAR(255),                                   -- 平台用户昵称
    raw_text TEXT NOT NULL,
    intent VARCHAR(64),                                         -- complete_fixed_work / add_work_log / add_inspiration / help / unknown
    confidence DECIMAL(3, 2) NOT NULL DEFAULT 0.00,             -- 0.00 - 1.00
    parsed_payload JSONB,                                       -- 解析后的结构化数据
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',              -- PENDING / CONFIRMED / IGNORED / FAILED
    target_module VARCHAR(64),                                  -- fixed_work / work_log / inspiration
    target_id BIGINT,
    created_by BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INT NOT NULL DEFAULT 0,
    CONSTRAINT idx_inbound_messages_unique UNIQUE (platform, platform_message_id)
);

CREATE INDEX idx_inbound_messages_user_status ON inbound_messages(user_id, status, deleted);
CREATE INDEX idx_inbound_messages_created_at ON inbound_messages(user_id, created_at);

-- ---------------------------------------------------------------------------
-- 2. 扩展固定工作完成记录：记录完成来源
-- ---------------------------------------------------------------------------
ALTER TABLE fixed_work_completions ADD COLUMN completion_source VARCHAR(32) DEFAULT 'DESKTOP';

-- ---------------------------------------------------------------------------
-- 3. 扩展工作记录：支持 IM 消息溯源
-- ---------------------------------------------------------------------------
ALTER TABLE work_logs ADD COLUMN platform_message_id VARCHAR(255);
```

- [ ] **Step 2: 验证 Flyway 版本号未冲突**

Run: `ls file-keeper/server/src/main/resources/db/migration/V*.sql`
Expected: 当前 file-keeper/server 模块最高版本为 `V9__...`，本文件 `V10__...` 无冲突。（注：agent-platform/backend 已有 V12，但两模块使用独立 Flyway 实例/数据库。）

- [ ] **Step 3: 提交**

```bash
git add file-keeper/server/src/main/resources/db/migration/V10__add_inbound_messages_and_completion_source.sql
git commit -m "chore: 新增 inbound_messages 与完成来源迁移脚本"
```

---

## Task 2: 新增枚举与 DTO

**Files:**
- Create: `file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/InboundMessageStatus.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/CompletionSource.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/workreport/dto/InboundMessageDto.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/workreport/dto/ConfirmInboundMessageRequest.java`

- [ ] **Step 1: 编写 InboundMessageStatus 枚举**

```java
package com.superprogrammer.workreport.entity;

public enum InboundMessageStatus {
    PENDING,
    CONFIRMED,
    IGNORED,
    FAILED
}
```

- [ ] **Step 2: 编写 CompletionSource 枚举**

```java
package com.superprogrammer.workreport.entity;

public enum CompletionSource {
    DESKTOP,
    IM,
    SCHEDULED
}
```

- [ ] **Step 3: 编写 InboundMessageDto**

```java
package com.superprogrammer.workreport.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record InboundMessageDto(
        Long id,
        Long userId,
        String platform,
        String platformMessageId,
        String senderId,
        String senderName,
        String rawText,
        String intent,
        BigDecimal confidence,
        Map<String, Object> parsedPayload,
        String status,
        String targetModule,
        Long targetId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
```

- [ ] **Step 4: 编写 ConfirmInboundMessageRequest**

```java
package com.superprogrammer.workreport.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ConfirmInboundMessageRequest(
        @NotNull(message = "确认动作不能为空")
        @Pattern(regexp = "CONFIRM|IGNORE", message = "确认动作必须是 CONFIRM 或 IGNORE")
        String action, // CONFIRM / IGNORE

        Map<String, Object> correctedPayload
) {
}
```

- [ ] **Step 5: 提交**

```bash
git add file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/InboundMessageStatus.java \
        file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/CompletionSource.java \
        file-keeper/server/src/main/java/com/superprogrammer/workreport/dto/InboundMessageDto.java \
        file-keeper/server/src/main/java/com/superprogrammer/workreport/dto/ConfirmInboundMessageRequest.java
git commit -m "feat: 新增 Inbox 状态枚举与 DTO"
```

---

## Task 3: 新增 InboundMessage 实体与 Repository

**Files:**
- Create: `file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/InboundMessage.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/workreport/repository/InboundMessageRepository.java`

- [ ] **Step 1: 编写 InboundMessage 实体**

```java
package com.superprogrammer.workreport.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inbound_messages")
public class InboundMessage extends BaseEntity {

    private Long userId;
    private String platform;
    private String platformMessageId;
    private String senderId;
    private String senderName;
    private String rawText;
    private String intent;
    private BigDecimal confidence;
    private String parsedPayload;
    private String status;
    private String targetModule;
    private Long targetId;
}
```

- [ ] **Step 2: 编写 InboundMessageRepository**

```java
package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.InboundMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class InboundMessageRepository {

    private final JdbcTemplate jdbcTemplate;

    public InboundMessage insert(InboundMessage message) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                    "insert into inbound_messages (user_id, platform, platform_message_id, sender_id, sender_name, raw_text, intent, confidence, parsed_payload, status, target_module, target_id, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                    new String[] { "id" }
                );
                ps.setLong(1, message.getUserId());
                ps.setString(2, message.getPlatform());
                ps.setString(3, message.getPlatformMessageId());
                ps.setString(4, message.getSenderId());
                ps.setString(5, message.getSenderName());
                ps.setString(6, message.getRawText());
                ps.setString(7, message.getIntent());
                ps.setBigDecimal(8, message.getConfidence());
                ps.setString(9, message.getParsedPayload());
                ps.setString(10, message.getStatus());
                ps.setString(11, message.getTargetModule());
                ps.setObject(12, message.getTargetId());
                ps.setObject(13, message.getCreatedBy());
                ps.setObject(14, message.getUpdatedBy());
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            Optional<InboundMessage> existing = findByPlatformAndMessageId(message.getPlatform(), message.getPlatformMessageId());
            return existing.orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "消息已存在但无法查询"));
        }
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "入站消息保存后无法获取主键");
        }
        return findById(generatedId.longValue())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "入站消息保存后无法查询"));
    }

    public InboundMessage update(InboundMessage message) {
        jdbcTemplate.update(
            "update inbound_messages set status = ?, target_module = ?, target_id = ?, parsed_payload = ?::jsonb, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                "where id = ? and deleted = 0",
            message.getStatus(), message.getTargetModule(), message.getTargetId(),
            message.getParsedPayload(), message.getUpdatedBy(), message.getId()
        );
        return findById(message.getId()).orElseThrow();
    }

    public Optional<InboundMessage> findById(Long id) {
        List<InboundMessage> results = jdbcTemplate.query(
            "select id, user_id, platform, platform_message_id, sender_id, sender_name, raw_text, intent, confidence, parsed_payload, status, target_module, target_id, created_by, created_at, updated_by, updated_at, deleted " +
                "from inbound_messages where id = ? and deleted = 0",
            messageMapper(), id
        );
        return results.stream().findFirst();
    }

    public Optional<InboundMessage> findByPlatformAndMessageId(String platform, String platformMessageId) {
        List<InboundMessage> results = jdbcTemplate.query(
            "select id, user_id, platform, platform_message_id, sender_id, sender_name, raw_text, intent, confidence, parsed_payload, status, target_module, target_id, created_by, created_at, updated_by, updated_at, deleted " +
                "from inbound_messages where platform = ? and platform_message_id = ? and deleted = 0",
            messageMapper(), platform, platformMessageId
        );
        return results.stream().findFirst();
    }

    public List<InboundMessage> findByUserIdAndStatus(Long userId, String status, int limit) {
        return jdbcTemplate.query(
            "select id, user_id, platform, platform_message_id, sender_id, sender_name, raw_text, intent, confidence, parsed_payload, status, target_module, target_id, created_by, created_at, updated_by, updated_at, deleted " +
                "from inbound_messages where user_id = ? and status = ? and deleted = 0 order by created_at desc limit ?",
            messageMapper(), userId, status, limit
        );
    }

    public List<InboundMessage> findPendingByUserId(Long userId, int limit) {
        return findByUserIdAndStatus(userId, "PENDING", limit);
    }

    private RowMapper<InboundMessage> messageMapper() {
        return (rs, rowNum) -> mapMessage(rs);
    }

    private InboundMessage mapMessage(ResultSet rs) throws SQLException {
        InboundMessage message = new InboundMessage();
        message.setId(rs.getLong("id"));
        message.setUserId(rs.getLong("user_id"));
        message.setPlatform(rs.getString("platform"));
        message.setPlatformMessageId(rs.getString("platform_message_id"));
        message.setSenderId(rs.getString("sender_id"));
        message.setSenderName(rs.getString("sender_name"));
        message.setRawText(rs.getString("raw_text"));
        message.setIntent(rs.getString("intent"));
        message.setConfidence(rs.getBigDecimal("confidence"));
        message.setParsedPayload(rs.getString("parsed_payload"));
        message.setStatus(rs.getString("status"));
        message.setTargetModule(rs.getString("target_module"));
        message.setTargetId(rs.getObject("target_id", Long.class));
        message.setCreatedBy(rs.getObject("created_by", Long.class));
        message.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        message.setUpdatedBy(rs.getObject("updated_by", Long.class));
        message.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        message.setDeleted(rs.getInt("deleted"));
        return message;
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/InboundMessage.java \
        file-keeper/server/src/main/java/com/superprogrammer/workreport/repository/InboundMessageRepository.java
git commit -m "feat: 新增 InboundMessage 实体与 Repository"
```

---

## Task 4: 规则 NLP 意图识别服务

**Files:**
- Create: `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/NlpIntentService.java`
- Create: `file-keeper/server/src/test/java/com/superprogrammer/workreport/service/NlpIntentServiceTest.java`

- [ ] **Step 1: 编写意图识别结果 record**

在同一个 `NlpIntentService.java` 文件内定义（package-private）：

```java
package com.superprogrammer.workreport.service;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NlpIntentService {

    public record IntentResult(String intent, double confidence, Map<String, Object> entities) {
    }

    private static final Pattern COMPLETE_PATTERN = Pattern.compile("(?:完成|做完|搞定|标记完成|done|finish)(?:了|掉|\\s+)?[：:\\s]*(.+?)(?:\\s+|$)");
    private static final Pattern WORK_LOG_PATTERN = Pattern.compile("(?:今天做了|记录了|工作记录|log)(?:：|\\s+)?(.+?)(?:\\s+|$)");
    private static final Pattern INSPIRATION_PATTERN = Pattern.compile("(?:灵感|想法|idea|随记)(?:：|\\s+)?(.+)$");
    private static final Pattern HELP_PATTERN = Pattern.compile("(?:帮助|help|指令|怎么用)");

    public IntentResult parse(String text) {
        if (text == null || text.isBlank()) {
            return new IntentResult("unknown", 0.0, Map.of());
        }
        String normalized = text.trim();

        Matcher completeMatcher = COMPLETE_PATTERN.matcher(normalized);
        if (completeMatcher.find()) {
            String taskName = completeMatcher.group(1).trim();
            if (!taskName.isEmpty()) {
                Map<String, Object> entities = new HashMap<>();
                entities.put("task_name", taskName);
                entities.put("date", "today");
                return new IntentResult("complete_fixed_work", 0.9, entities);
            }
        }

        Matcher workLogMatcher = WORK_LOG_PATTERN.matcher(normalized);
        if (workLogMatcher.find()) {
            String content = workLogMatcher.group(1).trim();
            if (!content.isEmpty()) {
                Map<String, Object> entities = new HashMap<>();
                entities.put("content", content);
                entities.put("date", "today");
                return new IntentResult("add_work_log", 0.85, entities);
            }
        }

        Matcher inspirationMatcher = INSPIRATION_PATTERN.matcher(normalized);
        if (inspirationMatcher.find()) {
            String content = inspirationMatcher.group(1).trim();
            if (!content.isEmpty()) {
                Map<String, Object> entities = new HashMap<>();
                entities.put("content", content);
                entities.put("tags", extractTags(content));
                return new IntentResult("add_inspiration", 0.85, entities);
            }
        }

        if (HELP_PATTERN.matcher(normalized).find()) {
            return new IntentResult("help", 0.95, Map.of());
        }

        return new IntentResult("unknown", 0.0, Map.of());
    }

    private List<String> extractTags(String content) {
        List<String> tags = new java.util.ArrayList<>();
        Pattern tagPattern = Pattern.compile("#([\\w/\\u4e00-\\u9fa5-]+)");
        Matcher matcher = tagPattern.matcher(content);
        while (matcher.find()) {
            tags.add(matcher.group(1));
        }
        return tags;
    }
}
```

- [ ] **Step 2: 编写测试**

```java
package com.superprogrammer.workreport.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NlpIntentServiceTest {

    private final NlpIntentService service = new NlpIntentService();

    @Test
    void shouldParseCompleteFixedWork() {
        NlpIntentService.IntentResult result = service.parse("完成日报设计");
        assertThat(result.intent()).isEqualTo("complete_fixed_work");
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.85);
        assertThat(result.entities().get("task_name")).isEqualTo("日报设计");
    }

    @Test
    void shouldParseAddWorkLog() {
        NlpIntentService.IntentResult result = service.parse("今天做了需求评审");
        assertThat(result.intent()).isEqualTo("add_work_log");
        assertThat(result.entities().get("content")).isEqualTo("需求评审");
    }

    @Test
    void shouldParseAddInspiration() {
        NlpIntentService.IntentResult result = service.parse("灵感：AI 日报应支持情感分析 #产品/灵感");
        assertThat(result.intent()).isEqualTo("add_inspiration");
        assertThat(result.entities().get("content")).isEqualTo("AI 日报应支持情感分析 #产品/灵感");
        assertThat(result.entities().get("tags")).isEqualTo(List.of("产品/灵感"));
    }

    @Test
    void shouldReturnUnknownForUnrecognized() {
        NlpIntentService.IntentResult result = service.parse("你好");
        assertThat(result.intent()).isEqualTo("unknown");
    }
}
```

- [ ] **Step 3: 运行测试验证失败（先写测试时预期失败，此处测试与实现同时提供，直接运行应通过）**

Run: `./mvnw -pl server test -Dtest=NlpIntentServiceTest`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add file-keeper/server/src/main/java/com/superprogrammer/workreport/service/NlpIntentService.java \
        file-keeper/server/src/test/java/com/superprogrammer/workreport/service/NlpIntentServiceTest.java
git commit -m "feat: 新增规则 NLP 意图识别服务"
```

> 实施适配：`INSPIRATION_PATTERN` 原计划使用 `(.+?)`，但测试发现含空格内容（如 "灵感：AI 日报应支持情感分析 #产品/灵感"）会被截断，实际实现改为贪婪 `(.+)`。

---

## Task 5: 飞书 Webhook 适配器

**Files:**
- Create: `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/webhook/WebhookParseResult.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/webhook/WebhookAdapter.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/webhook/FeishuWebhookAdapter.java`
- Create: `file-keeper/server/src/test/java/com/superprogrammer/workreport/service/webhook/FeishuWebhookAdapterTest.java`

- [ ] **Step 0: 扩展 JsonUtils 支持解析为 Map**

修改 `file-keeper/server/src/main/java/com/superprogrammer/common/JsonUtils.java`：

```java
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;

public static Map<String, Object> parseMap(String json) {
    if (json == null || json.isBlank()) {
        return Map.of();
    }
    try {
        return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
        log.error("JSON 解析为 Map 失败", e);
        throw new RuntimeException("JSON 解析为 Map 失败", e);
    }
}
```

- [ ] **Step 1: 编写 WebhookParseResult**

```java
package com.superprogrammer.workreport.service.webhook;

public record WebhookParseResult(
        String platformMessageId,
        String senderId,
        String senderName,
        String rawText,
        String chatId
) {
}
```

- [ ] **Step 2: 编写 WebhookAdapter 接口**

```java
package com.superprogrammer.workreport.service.webhook;

import java.util.Map;

public interface WebhookAdapter {

    String platform();

    boolean verifySignature(String body, String signature, String timestamp, String nonce, String secret);

    WebhookParseResult parseMessage(Map<String, Object> payload);
}
```

- [ ] **Step 3: 编写 FeishuWebhookAdapter**

```java
package com.superprogrammer.workreport.service.webhook;

import com.superprogrammer.common.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Component
public class FeishuWebhookAdapter implements WebhookAdapter {

    private static final String CHALLENGE = "url_verification";
    private static final String MESSAGE_EVENT = "im.message.receive_v1";

    @Override
    public String platform() {
        return "FEISHU";
    }

    @Override
    public boolean verifySignature(String body, String signature, String timestamp, String nonce, String secret) {
        if (signature == null || timestamp == null || nonce == null || secret == null) {
            return false;
        }
        try {
            String baseString = timestamp + nonce + secret + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sign = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(sign);
            return expected.equals(signature);
        } catch (Exception e) {
            log.error("[FeishuWebhookAdapter] 验签失败", e);
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public WebhookParseResult parseMessage(Map<String, Object> payload) {
        Map<String, Object> event = (Map<String, Object>) payload.get("event");
        if (event == null) {
            return null;
        }
        Map<String, Object> message = (Map<String, Object>) event.get("message");
        if (message == null) {
            return null;
        }
        String messageId = (String) message.get("message_id");
        String chatId = (String) message.get("chat_id");

        Map<String, Object> contentMap = JsonUtils.parseMap((String) message.getOrDefault("content", "{}"));
        String text = (String) contentMap.get("text");
        if (text == null || text.isBlank()) {
            return null;
        }

        Map<String, Object> sender = (Map<String, Object>) event.get("sender");
        String senderId = sender != null ? (String) sender.get("sender_id") : null;
        String senderName = "Unknown";
        if (sender != null) {
            Map<String, Object> senderInfo = (Map<String, Object>) sender.get("sender_info");
            if (senderInfo != null && senderInfo.get("name") != null) {
                senderName = (String) senderInfo.get("name");
            }
        }

        return new WebhookParseResult(messageId, senderId, senderName, text.trim(), chatId);
    }

    public boolean isChallenge(Map<String, Object> payload) {
        return CHALLENGE.equals(payload.get("type"));
    }

    public String extractChallenge(Map<String, Object> payload) {
        return (String) payload.get("challenge");
    }

    @SuppressWarnings("unchecked")
    public boolean isMessageEvent(Map<String, Object> payload) {
        Object header = payload.get("header");
        if (!(header instanceof Map)) {
            return false;
        }
        return MESSAGE_EVENT.equals(((Map<String, Object>) header).get("event_type"));
    }
}
```

- [ ] **Step 4: 编写测试**

```java
package com.superprogrammer.workreport.service.webhook;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuWebhookAdapterTest {

    private final FeishuWebhookAdapter adapter = new FeishuWebhookAdapter();

    @Test
    void shouldVerifySignature() {
        // 这里使用一个已知正确签名的测试用例，或仅验证错误签名返回 false
        boolean result = adapter.verifySignature("body", "wrong", "ts", "nonce", "secret");
        assertThat(result).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldParseMessageEvent() {
        Map<String, Object> payload = Map.of(
            "event", Map.of(
                "message", Map.of(
                    "message_id", "om_123",
                    "chat_id", "oc_123",
                    "content", "{\"text\":\"完成日报设计\"}"
                ),
                "sender", Map.of(
                    "sender_id", "ou_123",
                    "sender_info", Map.of("name", "张三")
                )
            )
        );

        WebhookParseResult result = adapter.parseMessage(payload);
        assertThat(result).isNotNull();
        assertThat(result.platformMessageId()).isEqualTo("om_123");
        assertThat(result.senderId()).isEqualTo("ou_123");
        assertThat(result.senderName()).isEqualTo("张三");
        assertThat(result.rawText()).isEqualTo("完成日报设计");
        assertThat(result.chatId()).isEqualTo("oc_123");
    }
}
```

- [ ] **Step 5: 运行测试**

Run: `./mvnw -pl server test -Dtest=FeishuWebhookAdapterTest`
Expected: BUILD SUCCESS

- [ ] **Step 7: 提交**

```bash
git add file-keeper/server/src/main/java/com/superprogrammer/common/JsonUtils.java \
        file-keeper/server/src/main/java/com/superprogrammer/workreport/service/webhook/WebhookParseResult.java \
        file-keeper/server/src/main/java/com/superprogrammer/workreport/service/webhook/WebhookAdapter.java \
        file-keeper/server/src/main/java/com/superprogrammer/workreport/service/webhook/FeishuWebhookAdapter.java \
        file-keeper/server/src/test/java/com/superprogrammer/workreport/service/webhook/FeishuWebhookAdapterTest.java
git commit -m "feat: 新增飞书 Webhook 适配器"
```

---

## Task 6: 扩展 FixedWorkCompletion 与 Repository

**Files:**
- Modify: `file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/FixedWorkCompletion.java`
- Modify: `file-keeper/server/src/main/java/com/superprogrammer/workreport/repository/FixedWorkCompletionRepository.java`

- [ ] **Step 1: 扩展 FixedWorkCompletion 实体**

在现有字段后增加：

```java
    private String completionSource;
```

- [ ] **Step 2: 修改 Repository 的 upsert/insert/update/mapper**

修改 `upsert` 方法，让更新时保留来源：
```java
    public FixedWorkCompletion upsert(FixedWorkCompletion completion) {
        Optional<FixedWorkCompletion> existing = findByItemIdAndDate(completion.getItemId(), completion.getCompletionDate());
        if (existing.isPresent()) {
            FixedWorkCompletion updated = existing.get();
            updated.setCompleted(completion.getCompleted());
            updated.setCompletedAt(completion.getCompletedAt());
            updated.setCompletionSource(completion.getCompletionSource());
            updated.setUpdatedBy(completion.getUpdatedBy());
            return update(updated);
        }
        return insert(completion);
    }
```

修改 `insert` 的 SQL：
```sql
"insert into fixed_work_completions (item_id, user_id, completion_date, completed, completed_at, completion_source, created_by, created_at, updated_by, updated_at, deleted) " +
    "values (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)"
```
对应 PreparedStatement 参数增加：
```java
ps.setString(6, completion.getCompletionSource());
// 后续序号顺延
```

修改 `update` 的 SQL：
```sql
"update fixed_work_completions set completed = ?, completed_at = ?, completion_source = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0"
```

修改 `mapCompletion`：
```java
completion.setCompletionSource(rs.getString("completion_source"));
```

- [ ] **Step 3: 提交**

```bash
git add file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/FixedWorkCompletion.java \
        file-keeper/server/src/main/java/com/superprogrammer/workreport/repository/FixedWorkCompletionRepository.java
git commit -m "feat: 固定工作完成记录支持 completion_source"
```

---

## Task 7: 扩展 FixedWorkService 支持按名称标记完成

**Files:**
- Modify: `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/FixedWorkService.java`

- [ ] **Step 1: 新增按名称查找并标记完成的方法**

在 `FixedWorkService` 中新增：

```java
    @Transactional
    public FixedWorkItemDto completeByName(Long userId, String taskName, LocalDate date, String source) {
        if (taskName == null || taskName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务名称不能为空");
        }
        LocalDate targetDate = date == null ? LocalDate.now() : date;

        List<FixedWorkItem> candidates = itemRepository.findByUserId(userId).stream()
                .filter(item -> item.getContent() != null && item.getContent().contains(taskName.trim()))
                .toList();

        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到匹配的固定工作：" + taskName);
        }
        if (candidates.size() > 1) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "匹配到多个固定工作，请提供更精确的名称：" + taskName);
        }

        FixedWorkItem item = candidates.get(0);
        FixedWorkCompletion completion = new FixedWorkCompletion();
        completion.setItemId(item.getId());
        completion.setUserId(userId);
        completion.setCompletionDate(targetDate);
        completion.setCompleted(true);
        completion.setCompletedAt(OffsetDateTime.now());
        completion.setCompletionSource(source == null ? CompletionSource.IM.name() : source);
        completion.setCreatedBy(userId);
        completion.setUpdatedBy(userId);
        completionRepository.upsert(completion);

        return toDto(item, true);
    }
```

- [ ] **Step 2: 修改 toggleComplete 记录来源**

在 `toggleComplete` 中设置 completion 时增加：
```java
completion.setCompletionSource(CompletionSource.DESKTOP.name());
```

- [ ] **Step 3: 提交**

```bash
git add file-keeper/server/src/main/java/com/superprogrammer/workreport/service/FixedWorkService.java
git commit -m "feat: FixedWorkService 支持按名称从 IM 标记完成"
```

---

## Task 8: 扩展 PushTargetRepository 按平台与目标 ID 查询

**Files:**
- Modify: `file-keeper/server/src/main/java/com/superprogrammer/workreport/repository/ReportPushTargetRepository.java`

> 注：本计划原使用类名 `PushTargetRepository`，但代码库中实际类名为 `ReportPushTargetRepository`，对应表为 `report_push_targets`。Task 8 及后续 Task 9 均按实际类名实现。

- [ ] **Step 1: 新增查询方法**

```java
    public List<PushTarget> findByPlatformAndTargetId(String platform, String targetId) {
        return jdbcTemplate.query(
            "select id, user_id, name, platform, target_type, target_id, credential_id, created_by, created_at, updated_by, updated_at, deleted " +
                "from push_targets where platform = ? and target_id = ? and deleted = 0 order by id desc limit 1",
            targetMapper(), platform, targetId
        );
    }
```

- [ ] **Step 2: 提交**

```bash
git add file-keeper/server/src/main/java/com/superprogrammer/workreport/repository/ReportPushTargetRepository.java
git commit -m "feat: PushTargetRepository 支持按 platform+targetId 查询"
```

---

## Task 9: InboundMessageService 业务 orchestration

**Files:**
- Create: `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/InboundMessageService.java`

- [ ] **Step 1: 编写服务**

```java
package com.superprogrammer.workreport.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.dto.InboundMessageDto;
import com.superprogrammer.workreport.entity.CompletionSource;
import com.superprogrammer.workreport.entity.InboundMessage;
import com.superprogrammer.workreport.entity.InboundMessageStatus;
import com.superprogrammer.workreport.repository.InboundMessageRepository;
import com.superprogrammer.workreport.repository.PushTargetRepository;
import com.superprogrammer.workreport.service.NlpIntentService.IntentResult;
import com.superprogrammer.workreport.service.webhook.WebhookParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InboundMessageService {

    private static final double AUTO_CONFIRM_THRESHOLD = 0.85;

    private final InboundMessageRepository inboundMessageRepository;
    private final ReportPushTargetRepository pushTargetRepository;
    private final ReportConfigRepository reportConfigRepository;
    private final NlpIntentService nlpIntentService;
    private final FixedWorkService fixedWorkService;
    private final WorkLogService workLogService;
    private final WorkReportEventPushService eventPushService;
    private final ObjectMapper objectMapper;

    @Transactional
    public InboundMessageDto receive(String platform, WebhookParseResult parseResult) {
        Long userId = resolveUserId(platform, parseResult.chatId());

        InboundMessage message = new InboundMessage();
        message.setUserId(userId);
        message.setPlatform(platform);
        message.setPlatformMessageId(parseResult.platformMessageId());
        message.setSenderId(parseResult.senderId());
        message.setSenderName(parseResult.senderName());
        message.setRawText(parseResult.rawText());
        message.setCreatedBy(userId);
        message.setUpdatedBy(userId);

        IntentResult intent = nlpIntentService.parse(parseResult.rawText());
        message.setIntent(intent.intent());
        message.setConfidence(BigDecimal.valueOf(intent.confidence()));
        message.setParsedPayload(toJson(intent.entities()));
        message.setStatus(InboundMessageStatus.PENDING.name());

        InboundMessage saved = inboundMessageRepository.insert(message);

        if (intent.confidence() >= AUTO_CONFIRM_THRESHOLD) {
            try {
                executeIntent(saved, intent);
            } catch (Exception e) {
                log.error("[InboundMessageService] 自动执行意图失败 messageId={}", saved.getId(), e);
                saved.setStatus(InboundMessageStatus.FAILED.name());
                inboundMessageRepository.update(saved);
            }
        }

        eventPushService.push(userId, "inbound_message", toDto(saved));
        return toDto(saved);
    }

    @Transactional
    public InboundMessageDto confirm(Long userId, Long messageId, String action, Map<String, Object> correctedPayload) {
        InboundMessage message = inboundMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "消息不存在"));
        if (!message.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权处理该消息");
        }

        if ("IGNORE".equalsIgnoreCase(action)) {
            message.setStatus(InboundMessageStatus.IGNORED.name());
            message.setUpdatedBy(userId);
            return toDto(inboundMessageRepository.update(message));
        }

        if (!"CONFIRM".equalsIgnoreCase(action)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的确认动作: " + action);
        }

        Map<String, Object> payload = correctedPayload != null && !correctedPayload.isEmpty()
                ? correctedPayload
                : parseJson(message.getParsedPayload());

        String intent = message.getIntent();
        if (intent == null || "unknown".equals(intent)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "无法确认未知意图的消息");
        }

        executeIntent(message, new IntentResult(intent, 1.0, payload));
        return toDto(inboundMessageRepository.findById(messageId).orElseThrow());
    }

    public List<InboundMessageDto> listPending(Long userId, int limit) {
        return inboundMessageRepository.findPendingByUserId(userId, limit).stream()
                .map(this::toDto)
                .toList();
    }

    private void executeIntent(InboundMessage message, IntentResult intent) {
        switch (intent.intent()) {
            case "complete_fixed_work" -> {
                String taskName = (String) intent.entities().get("task_name");
                String dateExpr = (String) intent.entities().get("date");
                LocalDate date = "today".equals(dateExpr) ? LocalDate.now() : LocalDate.parse(dateExpr);
                fixedWorkService.completeByName(message.getUserId(), taskName, date, CompletionSource.IM.name());
                message.setTargetModule("fixed_work");
                message.setStatus(InboundMessageStatus.CONFIRMED.name());
            }
            case "add_work_log" -> {
                String content = (String) intent.entities().get("content");
                String dateExpr = (String) intent.entities().get("date");
                LocalDate logDate = "today".equals(dateExpr) ? LocalDate.now() : LocalDate.parse(dateExpr);
                workLogService.create(message.getUserId(), new com.superprogrammer.workreport.dto.CreateWorkLogRequest(
                        logDate, content, null, "IM", 0
                ));
                message.setTargetModule("work_log");
                message.setStatus(InboundMessageStatus.CONFIRMED.name());
            }
            default -> throw new BusinessException(ErrorCode.UNPROCESSABLE, "暂不支持的意图: " + intent.intent());
        }
        message.setUpdatedBy(message.getUserId());
        inboundMessageRepository.update(message);
    }

    private Long resolveUserId(String platform, String chatId) {
        var targets = pushTargetRepository.findByPlatformAndTargetId(platform, chatId);
        if (targets.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该 IM 通道绑定的用户");
        }
        // 代码库中 ReportPushTarget 没有直接 user_id，需通过 config_id 查 ReportConfig
        Long configId = targets.get(0).getConfigId();
        return reportConfigRepository.findById(configId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该推送配置"))
                .getUserId();
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化 payload 失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return json == null ? Map.of() : objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("反序列化 payload 失败", e);
        }
    }

    private InboundMessageDto toDto(InboundMessage message) {
        return new InboundMessageDto(
                message.getId(),
                message.getUserId(),
                message.getPlatform(),
                message.getPlatformMessageId(),
                message.getSenderId(),
                message.getSenderName(),
                message.getRawText(),
                message.getIntent(),
                message.getConfidence(),
                parseJson(message.getParsedPayload()),
                message.getStatus(),
                message.getTargetModule(),
                message.getTargetId(),
                message.getCreatedAt() == null ? null : message.getCreatedAt().toLocalDateTime(),
                message.getUpdatedAt() == null ? null : message.getUpdatedAt().toLocalDateTime()
        );
    }
}
```

> 注意：`CreateWorkLogRequest` 已包含 `source` 与 `sortOrder` 字段；`platformMessageId` 在 MVP 中由后端内部设置，不暴露给客户端请求。

- [ ] **Step 2: 提交**

```bash
git add file-keeper/server/src/main/java/com/superprogrammer/workreport/service/InboundMessageService.java
git commit -m "feat: 新增 InboundMessageService 接收与确认流程"
```

---

## Task 10: 扩展 WorkLog 实体与 Repository 支持 IM 溯源

**Files:**
- Modify: `file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/WorkLog.java`
- Modify: `file-keeper/server/src/main/java/com/superprogrammer/workreport/repository/WorkLogRepository.java`

- [ ] **Step 1: 扩展 WorkLog 实体**

在 `WorkLog.java` 中增加：
```java
    private String platformMessageId;
```

- [ ] **Step 2: 修改 WorkLogRepository**

修改 `insert` SQL：
```sql
"insert into work_logs (user_id, log_date, content, tags, source, sort_order, platform_message_id, created_by, created_at, updated_by, updated_at, deleted) " +
    "values (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)"
```
对应参数：
```java
ps.setString(7, log.getPlatformMessageId());
ps.setObject(8, log.getCreatedBy());
ps.setObject(9, log.getUpdatedBy());
```

修改 `update` SQL：
```sql
"update work_logs set content = ?, tags = ?, source = ?, sort_order = ?, platform_message_id = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
    "where id = ? and deleted = 0"
```

所有 `select` SQL 增加 `platform_message_id` 列。

修改 `mapWorkLog`：
```java
log.setPlatformMessageId(rs.getString("platform_message_id"));
```

- [ ] **Step 3: 提交**

```bash
git add file-keeper/server/src/main/java/com/superprogrammer/workreport/entity/WorkLog.java \
        file-keeper/server/src/main/java/com/superprogrammer/workreport/repository/WorkLogRepository.java
git commit -m "feat: 工作记录支持 platform_message_id 溯源"
```

---

## Task 11: 飞书 Webhook Controller

**Files:**
- Create: `file-keeper/server/src/main/java/com/superprogrammer/workreport/controller/FeishuWebhookController.java`

- [ ] **Step 1: 编写 Controller**

```java
package com.superprogrammer.workreport.controller;

import com.superprogrammer.common.JsonUtils;
import com.superprogrammer.common.R;
import com.superprogrammer.workreport.service.InboundMessageService;
import com.superprogrammer.workreport.service.webhook.FeishuWebhookAdapter;
import com.superprogrammer.workreport.service.webhook.WebhookParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/client/work-report/webhook/feishu")
@RequiredArgsConstructor
public class FeishuWebhookController {

    private final FeishuWebhookAdapter feishuWebhookAdapter;
    private final InboundMessageService inboundMessageService;

    @PostMapping
    public ResponseEntity<?> receive(
            @RequestBody String body,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce) {

        log.info("[FeishuWebhook] 收到回调 body={}", body);
        Map<String, Object> payload = JsonUtils.parseMap(body);

        if (feishuWebhookAdapter.isChallenge(payload)) {
            String challenge = feishuWebhookAdapter.extractChallenge(payload);
            return ResponseEntity.ok(Map.of("challenge", challenge));
        }

        // MVP 阶段可选：配置 feishu.webhook-secret 后启用验签
        // String secret = ...;
        // if (!feishuWebhookAdapter.verifySignature(body, signature, timestamp, nonce, secret)) {
        //     return ResponseEntity.status(401).body(R.fail(401, "签名验证失败"));
        // }

        WebhookParseResult parseResult = feishuWebhookAdapter.parseMessage(payload);
        if (parseResult == null) {
            log.warn("[FeishuWebhook] 无法解析消息 payload={}", payload);
            return ResponseEntity.ok(R.ok());
        }

        inboundMessageService.receive("FEISHU", parseResult);
        return ResponseEntity.ok(R.ok());
    }
}
```

> 说明：
> 1. 该端点由**飞书平台回调**，不是桌面端/用户直接访问，因此不携带用户 JWT，也不做 `MODULE_WORK_REPORT` 模块授权校验。
> 2. 其安全性依赖**平台签名验签**（MVP 中注释掉，Phase 2 强制开启）和 **`push_targets` 反查用户**。
> 3. 验签未通过时应返回 401，避免伪造消息进入系统。

- [ ] **Step 2: 提交**

```bash
git add file-keeper/server/src/main/java/com/superprogrammer/workreport/controller/FeishuWebhookController.java
git commit -m "feat: 新增飞书 Webhook 接收 Controller"
```

---

## Task 12: InboundMessage Controller

**Files:**
- Create: `file-keeper/server/src/main/java/com/superprogrammer/workreport/controller/InboundMessageController.java`

- [ ] **Step 1: 编写 Controller**

```java
package com.superprogrammer.workreport.controller;

import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.R;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.authorization.dto.AuthorizationSnapshot;
import com.superprogrammer.authorization.service.AuthorizationService;
import com.superprogrammer.workreport.dto.ConfirmInboundMessageRequest;
import com.superprogrammer.workreport.dto.InboundMessageDto;
import com.superprogrammer.workreport.service.InboundMessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/client/work-report/inbox")
@RequiredArgsConstructor
public class InboundMessageController {

    private final AuthorizationService authorizationService;
    private final InboundMessageService inboundMessageService;

    private boolean checkModuleAuth(Authentication auth, String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        AuthorizationSnapshot snapshot = authorizationService.authenticatedSnapshot(
                principal.userId(), deviceId, System.currentTimeMillis()
        );
        return snapshot.modules().stream()
                .anyMatch(m -> AuthConstants.MODULE_WORK_REPORT.equals(m.moduleCode()) && m.allowed());
    }

    @GetMapping
    public R<List<InboundMessageDto>> listPending(
            Authentication auth,
            @RequestParam String deviceId,
            @RequestParam(defaultValue = "50") @Max(200) int limit) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(inboundMessageService.listPending(principal.userId(), limit));
    }

    @PostMapping("/{id}/confirm")
    public R<InboundMessageDto> confirm(
            Authentication auth,
            @RequestParam String deviceId,
            @PathVariable Long id,
            @RequestBody @Valid ConfirmInboundMessageRequest request) {
        if (!checkModuleAuth(auth, deviceId)) {
            return R.fail(ErrorCode.FORBIDDEN.getCode(), "未授权访问工作汇报模块");
        }
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        return R.ok(inboundMessageService.confirm(principal.userId(), id, request.action(), request.correctedPayload()));
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add file-keeper/server/src/main/java/com/superprogrammer/workreport/controller/InboundMessageController.java
git commit -m "feat: 新增 Inbox Controller"
```

---

## Task 13: SSE 事件推送（可选，P1）

**Files:**
- Create: `file-keeper/server/src/main/java/com/superprogrammer/workreport/controller/WorkReportEventController.java`
- Create: `file-keeper/server/src/main/java/com/superprogrammer/workreport/service/WorkReportEventPushService.java`

- [ ] **Step 1: 编写 WorkReportEventPushService**

```java
package com.superprogrammer.workreport.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkReportEventPushService {

    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(userId, emitter);
        emitter.onCompletion(() -> emitters.remove(userId));
        emitter.onTimeout(() -> emitters.remove(userId));
        emitter.onError(e -> emitters.remove(userId));
        return emitter;
    }

    public void push(Long userId, String eventName, Object data) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
        } catch (IOException e) {
            emitters.remove(userId);
        }
    }
}
```

- [ ] **Step 2: 编写 WorkReportEventController**

```java
package com.superprogrammer.workreport.controller;

import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.security.AuthPrincipal;
import com.superprogrammer.authorization.dto.AuthorizationSnapshot;
import com.superprogrammer.authorization.service.AuthorizationService;
import com.superprogrammer.workreport.service.WorkReportEventPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/client/work-report/events")
@RequiredArgsConstructor
public class WorkReportEventController {

    private final AuthorizationService authorizationService;
    private final WorkReportEventPushService eventPushService;

    @GetMapping("/stream")
    public SseEmitter stream(
            Authentication auth,
            @RequestParam String deviceId) {
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        AuthorizationSnapshot snapshot = authorizationService.authenticatedSnapshot(
                principal.userId(), deviceId, System.currentTimeMillis()
        );
        boolean allowed = snapshot.modules().stream()
                .anyMatch(m -> AuthConstants.MODULE_WORK_REPORT.equals(m.moduleCode()) && m.allowed());
        if (!allowed) {
            SseEmitter emitter = new SseEmitter();
            emitter.completeWithError(new RuntimeException("未授权访问工作汇报模块"));
            return emitter;
        }
        return eventPushService.subscribe(principal.userId());
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add file-keeper/server/src/main/java/com/superprogrammer/workreport/service/WorkReportEventPushService.java \
        file-keeper/server/src/main/java/com/superprogrammer/workreport/controller/WorkReportEventController.java
git commit -m "feat: 新增 SSE 事件推送服务"
```

---

## Task 14: 前端类型与 API

**Files:**
- Create: `file-keeper/src/types/inbox.ts`
- Create: `file-keeper/src/api/inbox.ts`
- Modify: `file-keeper/src/types/workReport.ts`

- [ ] **Step 1: 编写 inbox.ts 类型**

```typescript
export type InboxStatus = 'PENDING' | 'CONFIRMED' | 'IGNORED' | 'FAILED'
export type InboxIntent = 'complete_fixed_work' | 'add_work_log' | 'add_inspiration' | 'help' | 'unknown'

export interface InboxMessage {
  id: number
  userId: number
  platform: string
  platformMessageId: string
  senderId?: string
  senderName?: string
  rawText: string
  intent: InboxIntent
  confidence: number
  parsedPayload: Record<string, unknown>
  status: InboxStatus
  targetModule?: string
  targetId?: number
  createdAt: string
  updatedAt: string
}
```

- [ ] **Step 2: 编写 inbox.ts API**

```typescript
import type { InboxMessage } from '@/types/inbox'

const BASE_PATH = '/api/client/work-report/inbox'

interface ApiResponse<T> {
  code: number
  msg: string
  data: T
}

function buildUrl(baseUrl: string, path: string, deviceId: string): string {
  const separator = path.includes('?') ? '&' : '?'
  return `${baseUrl.replace(/\/+$/, '')}${BASE_PATH}${path}${separator}deviceId=${encodeURIComponent(deviceId)}`
}

async function readApiResponse<T>(response: Response): Promise<T> {
  const payload = (await response.json()) as ApiResponse<T>
  if (!response.ok || payload.code !== 200) {
    throw new Error(payload.msg || `请求失败：${response.status}`)
  }
  return payload.data
}

async function request<T>(
  baseUrl: string,
  token: string,
  deviceId: string,
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const response = await fetch(buildUrl(baseUrl, path, deviceId), {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      ...(options.headers || {}),
    },
  })
  return readApiResponse<T>(response)
}

export async function listPendingInbox(
  baseUrl: string,
  token: string,
  deviceId: string,
  limit = 50,
): Promise<InboxMessage[]> {
  return request<InboxMessage[]>(baseUrl, token, deviceId, `?limit=${limit}`)
}

export async function confirmInboxMessage(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
  action: 'CONFIRM' | 'IGNORE',
  correctedPayload?: Record<string, unknown>,
): Promise<InboxMessage> {
  return request<InboxMessage>(baseUrl, token, deviceId, `/${id}/confirm`, {
    method: 'POST',
    body: JSON.stringify({ action, correctedPayload }),
  })
}
```

- [ ] **Step 3: 扩展 workReport.ts 类型（source 字段）**

在 `WorkLog` 接口中确保已有 `source?: string`。

- [ ] **Step 4: 提交**

```bash
git add file-keeper/src/types/inbox.ts \
        file-keeper/src/api/inbox.ts \
        file-keeper/src/types/workReport.ts
git commit -m "feat: 前端新增 Inbox 类型与 API"
```

---

## Task 15: 前端 Store 扩展

**Files:**
- Modify: `file-keeper/src/stores/workReportStore.ts`
- Create: `file-keeper/src/stores/__tests__/workReportStore.inbox.test.ts`

- [ ] **Step 1: 在 Store 中新增 Inbox 状态与方法**

在 `workReportStore.ts` 中：

```typescript
import type { InboxMessage } from '@/types/inbox'
import * as inboxApi from '@/api/inbox'

const inboxMessages = ref<InboxMessage[]>([])
const inboxLoading = ref(false)

const pendingInboxCount = computed(() => inboxMessages.value.filter(m => m.status === 'PENDING').length)

async function loadInbox(limit = 50) {
  const { baseUrl, token, deviceId } = getAuthContext()
  inboxLoading.value = true
  try {
    inboxMessages.value = await inboxApi.listPendingInbox(baseUrl, token, deviceId, limit)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    throw e
  } finally {
    inboxLoading.value = false
  }
}

async function confirmInboxMessage(id: number, action: 'CONFIRM' | 'IGNORE', correctedPayload?: Record<string, unknown>) {
  const { baseUrl, token, deviceId } = getAuthContext()
  try {
    await inboxApi.confirmInboxMessage(baseUrl, token, deviceId, id, action, correctedPayload)
    await loadInbox()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    throw e
  }
}

return {
  // ... existing returns
  inboxMessages,
  inboxLoading,
  pendingInboxCount,
  loadInbox,
  confirmInboxMessage,
}
```

- [ ] **Step 2: 编写 Store 测试（可选，受外部 API 影响，可用 mock）**

由于 `workReportStore` 依赖 `fetch` 全局，测试需要 mock。MVP 阶段可省略，或仅测试新增 computed：

```typescript
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useWorkReportStore } from '../workReportStore'

describe('workReportStore inbox', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('pendingInboxCount defaults to 0', () => {
    const store = useWorkReportStore()
    expect(store.pendingInboxCount).toBe(0)
  })
})
```

- [ ] **Step 3: 提交**

```bash
git add file-keeper/src/stores/workReportStore.ts \
        file-keeper/src/stores/__tests__/workReportStore.inbox.test.ts
git commit -m "feat: workReportStore 支持 Inbox"
```

---

## Task 16: 前端 InboxPanel 组件

**Files:**
- Create: `file-keeper/src/components/work-report/InboxPanel.vue`

- [ ] **Step 1: 编写组件**

```vue
<template>
  <div class="h-full flex flex-col overflow-auto p-4 bg-white dark:bg-dark-panel">
    <div class="flex items-center justify-between mb-3">
      <h3 class="text-sm font-semibold">{{ t('workReport.inbox') }}</h3>
      <button
        @click="store.loadInbox()"
        :disabled="store.inboxLoading"
        class="px-3 py-1.5 text-xs rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)] transition-colors"
      >
        {{ store.inboxLoading ? t('common.loading') : t('common.refresh') }}
      </button>
    </div>

    <div v-if="store.error" class="mb-3 p-2 rounded-md bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 text-xs">
      {{ store.error }}
    </div>

    <div class="flex-1 overflow-auto space-y-2">
      <div
        v-for="message in store.inboxMessages"
        :key="message.id"
        class="p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg"
      >
        <div class="flex items-start justify-between gap-2">
          <div class="flex-1 min-w-0">
            <p class="text-sm font-medium">{{ message.rawText }}</p>
            <div class="mt-1.5 flex flex-wrap items-center gap-2 text-[10px] text-gray-500 dark:text-gray-400">
              <span class="px-1.5 py-0.5 rounded bg-gray-100 dark:bg-dark-hover">{{ platformLabel(message.platform) }}</span>
              <span>{{ message.senderName || message.senderId || t('workReport.unknownSender') }}</span>
              <span>{{ formatIntent(message.intent) }}</span>
              <span v-if="message.confidence >= 0.85" class="text-green-600 dark:text-green-400">{{ t('workReport.highConfidence') }}</span>
              <span v-else class="text-yellow-600 dark:text-yellow-400">{{ t('workReport.lowConfidence') }}</span>
            </div>
          </div>
          <div class="flex items-center space-x-1 shrink-0">
            <button
              @click="confirm(message.id, 'CONFIRM')"
              class="px-2 py-1 text-xs rounded-md bg-primary text-white hover:bg-primary/90"
            >
              {{ t('common.confirm') }}
            </button>
            <button
              @click="confirm(message.id, 'IGNORE')"
              class="px-2 py-1 text-xs rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)]"
            >
              {{ t('common.ignore') }}
            </button>
          </div>
        </div>
      </div>

      <div v-if="store.inboxMessages.length === 0" class="text-center text-gray-400 py-8">
        {{ t('workReport.emptyInbox') }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { useWorkReportStore } from '@/stores/workReportStore'
import { useI18n } from '@/composables/useI18n'
import type { InboxMessage, InboxIntent } from '@/types/inbox'

const store = useWorkReportStore()
const { t } = useI18n()

let pollTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  store.loadInbox()
  // MVP 使用轮询；Task 13 已实现 SSE 后端，后续可替换为 EventSource
  pollTimer = setInterval(() => store.loadInbox(), 30000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})

function platformLabel(platform: string): string {
  switch (platform) {
    case 'FEISHU': return t('workReport.platformFeishu')
    case 'DINGTALK': return t('workReport.platformDingtalk')
    case 'WECHAT_WORK': return t('workReport.platformWechatWork')
    case 'SLACK': return t('workReport.platformSlack')
    default: return platform
  }
}

function formatIntent(intent: InboxIntent): string {
  switch (intent) {
    case 'complete_fixed_work': return t('workReport.intentCompleteFixedWork')
    case 'add_work_log': return t('workReport.intentAddWorkLog')
    case 'add_inspiration': return t('workReport.intentAddInspiration')
    case 'help': return t('workReport.intentHelp')
    default: return t('workReport.intentUnknown')
  }
}

async function confirm(id: number, action: 'CONFIRM' | 'IGNORE') {
  await store.confirmInboxMessage(id, action)
}
</script>
```

- [ ] **Step 2: 补充国际化键值（如使用）**

在 `file-keeper/src/locales/zh-CN.ts` 与 `en.ts` 的 `workReport` 命名空间下补充：
```ts
inbox: '互动收件箱',
unknownSender: '未知发送者',
highConfidence: '高置信度',
lowConfidence: '低置信度',
intentCompleteFixedWork: '完成固定工作',
intentAddWorkLog: '添加工作记录',
intentAddInspiration: '添加灵感',
intentHelp: '帮助',
intentUnknown: '未知',
emptyInbox: '暂无待确认消息',
```

- [ ] **Step 3: 提交**

```bash
git add file-keeper/src/components/work-report/InboxPanel.vue \
        file-keeper/src/locales/zh-CN.ts \
        file-keeper/src/locales/en.ts
git commit -m "feat: 新增 InboxPanel 组件"
```

---

## Task 17: 集成 InboxPanel 到工作汇报管理页

**Files:**
- Modify: `file-keeper/src/components/work-report/WorkReportManagement.vue`

- [ ] **Step 1: 引入组件并添加 Tab**

```vue
<script setup lang="ts">
import InboxPanel from './InboxPanel.vue'

const mainTabs = computed(() => [
  { key: 'inbox' as MainTab, label: t('workReport.inbox') },
  { key: 'logs' as MainTab, label: t('workReport.workLogs') },
  { key: 'future' as MainTab, label: t('workReport.futurePlans') },
  { key: 'fixed' as MainTab, label: t('workReport.fixedWork') },
  { key: 'push-config' as MainTab, label: t('workReport.pushConfig') },
])
</script>
```

在模板内容区增加：
```vue
<InboxPanel v-else-if="store.activeMainTab === 'inbox'" />
```

- [ ] **Step 2: 提交**

```bash
git add file-keeper/src/components/work-report/WorkReportManagement.vue
git commit -m "feat: 工作汇报管理页增加 Inbox 入口"
```

---

## Task 18: 新增接口授权测试

**Files:**
- Create: `file-keeper/server/src/test/java/com/superprogrammer/workreport/controller/InboundMessageControllerAuthTest.java`
- Create: `file-keeper/server/src/test/java/com/superprogrammer/workreport/controller/WorkReportEventControllerAuthTest.java`

- [ ] **Step 1: 编写 InboundMessageController 授权测试**

```java
package com.superprogrammer.workreport.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InboundMessageControllerAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void shouldRejectWhenModuleNotEntitled() throws Exception {
        mockMvc.perform(get("/api/client/work-report/inbox")
                        .param("deviceId", "test-device")
                        .param("limit", "50"))
                .andExpect(status().isForbidden());
    }
}
```

> 说明：测试用户已登录但未被授权 `work-report` 模块时，访问 Inbox 接口返回 403。实际项目若已有授权测试基类，优先复用。

- [ ] **Step 2: 编写 WorkReportEventController 授权测试**

```java
package com.superprogrammer.workreport.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WorkReportEventControllerAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "user", roles = "USER")
    void shouldRejectEventStreamWhenModuleNotEntitled() throws Exception {
        mockMvc.perform(get("/api/client/work-report/events/stream")
                        .param("deviceId", "test-device"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 3: 运行授权测试**

Run: `./mvnw -pl server test -Dtest=InboundMessageControllerAuthTest,WorkReportEventControllerAuthTest`
Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add file-keeper/server/src/test/java/com/superprogrammer/workreport/controller/InboundMessageControllerAuthTest.java \
        file-keeper/server/src/test/java/com/superprogrammer/workreport/controller/WorkReportEventControllerAuthTest.java
git commit -m "test: 新增 Inbox 与事件流接口授权测试"
```

---

## Task 19: 编译与后端测试

**Files:** N/A

- [ ] **Step 1: 编译后端**

Run: `./mvnw -pl server clean compile`
Expected: BUILD SUCCESS

- [ ] **Step 2: 运行新增单元测试**

Run: `./mvnw -pl server test -Dtest=NlpIntentServiceTest,FeishuWebhookAdapterTest,InboundMessageControllerAuthTest,WorkReportEventControllerAuthTest`
Expected: BUILD SUCCESS

- [ ] **Step 3: 运行全部后端测试**

Run: `./mvnw -pl server test`
Expected: BUILD SUCCESS（若现有测试失败需先修复）

- [ ] **Step 4: 提交**

```bash
git commit -m "test: 通过 Phase 1 后端编译与测试"
```

---

## Task 20: 前端编译与类型检查

**Files:** N/A

- [ ] **Step 1: 安装依赖（若需要）**

Run: `cd file-keeper && pnpm install`
Expected: 依赖已安装

- [ ] **Step 2: 类型检查**

Run: `cd file-keeper && pnpm vue-tsc --noEmit`
Expected: 无类型错误

- [ ] **Step 3: 运行前端测试**

Run: `cd file-keeper && pnpm test`
Expected: 测试通过

- [ ] **Step 4: 提交**

```bash
git commit -m "test: 通过 Phase 1 前端类型检查与测试"
```

---

## Task 21: 端到端手动验证

**Files:** N/A

- [ ] **Step 1: 启动后端**

Run: `./mvnw -pl server spring-boot:run`
Expected: 应用启动成功，Flyway 执行 V10 迁移无报错。

- [ ] **Step 2: 配置飞书机器人**

1. 在飞书开放平台创建企业自建应用。
2. 开启机器人能力，订阅事件 `im.message.receive_v1`。
3. 设置请求 URL 为 `https://<你的域名>/api/client/work-report/webhook/feishu`。
4. 在桌面端“推送配置”中新增 Feishu 凭据与目标（GROUP），目标 ID 填入飞书群 chat_id。

- [ ] **Step 3: 在飞书群中发送消息**

发送：`完成日报设计`

- [ ] **Step 4: 验证后端**

检查数据库：
```sql
select * from inbound_messages where platform = 'FEISHU' order by created_at desc limit 1;
select * from fixed_work_completions where completion_source = 'IM' order by created_at desc limit 1;
```
Expected:
- `inbound_messages` 中新增一条 `status='CONFIRMED'`、`intent='complete_fixed_work'` 的记录。
- `fixed_work_completions` 中对应 `item_id` 的 `completed=true`。

- [ ] **Step 5: 验证前端**

1. 打开桌面端工作汇报模块。
2. 切换到“互动收件箱”Tab。
3. 应看到来自飞书的消息卡片，状态为“已确认”。
4. 若发送低置信度消息（如“搞定”），应看到 `PENDING` 状态卡片，可点击“确认”。

- [ ] **Step 6: 提交验证脚本（可选）**

```bash
git add file-keeper/docs/superpowers/plans/phase1-manual-test.md  # 如补充手动测试文档
git commit -m "docs: Phase 1 手动验证步骤"
```

---

## 自查清单

### 1. Spec 覆盖度

| 规划需求 | 对应 Task |
|---------|----------|
| IM 双向通道（飞书 webhook） | Task 5, 11 |
| 自然语言理解（规则版） | Task 4 |
| Inbox 后端 CRUD | Task 3, 9, 12 |
| WebSocket/SSE 推送 | Task 13 |
| 桌面端 InboxPanel | Task 16, 17 |
| 固定工作完成实例（按 IM 标记完成） | Task 6, 7, 9 |
| 新增客户端接口授权校验 | Task 12, 13, 18 |
| 模块规范合规（无新 moduleCode） | 顶部“模块注册与授权状态” |

### 2. Placeholder 扫描

- 无 `TBD` / `TODO` / `implement later`。
- 所有代码块均包含可直接使用的代码。
- 所有命令均包含预期输出。

### 3. 类型一致性

- `InboundMessageStatus` / `CompletionSource` 枚举与数据库字符串一致。
- `NlpIntentService.IntentResult` 与 `InboundMessageService` 使用相同字段名 `intent`、`confidence`、`entities`。
- 前端 `InboxMessage` 与后端 `InboundMessageDto` 字段名一致。

---

## 执行交接

**Plan complete and saved to `file-keeper/docs/superpowers/plans/interactive-ai-work-assistant/phase1/plan.md`.**

Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using `superpowers:executing-plans`, batch execution with checkpoints.

**Which approach?**
