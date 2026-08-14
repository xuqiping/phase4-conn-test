// agent-platform/backend/src/main/java/com/superprogrammer/common/security/entity/SecurityEvent.java
package com.superprogrammer.common.security.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 安全事件（V104 security_events，11x 加固）。
 *
 * <p>不继承 BaseEntity：本表半年留存运维可物理删，无 deleted/version/updated_* 列
 * （与 audit_logs append-only 铁证隔离——本表可 DELETE，audit 不可）。</p>
 */
@Data
@TableName(value = "security_events", autoResultMap = true)
public class SecurityEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private OffsetDateTime createdAt;

    /** 检测规则码（SecurityEventTypes.LOGIN_BRUTE_FORCE 等 15 个）。 */
    private String eventType;

    /** LOW/MEDIUM/HIGH/CRITICAL。 */
    private String severity;

    /** 涉及用户（匿名 IP 探测可空）。 */
    private Long userId;

    /** 归一化后的客户端 IP。 */
    private String clientIp;

    /** 串 app.log 全请求日志（MDC traceId）。 */
    private String traceId;

    /** 命中的规则配置 ID（可空）。 */
    private String ruleId;

    /** 上下文 JSON（已脱敏，禁 PII 原文）。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String detailJson;

    /** 自动响应结果：NONE/IP_BLOCKED/ACCOUNT_LOCKED/ACCOUNT_BANNED/TOKEN_REVOKED。 */
    private String autoAction;

    /** 运维是否已处置（ACK）。 */
    private Boolean handled;

    private String handledBy;

    private OffsetDateTime handledAt;

    /** 13x-1：展示用回填字段（按 userId 批量 join 用户表），非表列。 */
    @TableField(exist = false)
    private String username;
}
