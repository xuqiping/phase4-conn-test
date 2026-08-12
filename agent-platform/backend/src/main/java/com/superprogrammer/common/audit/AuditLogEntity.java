package com.superprogrammer.common.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 操作审计实体（日志系统 LOG-FR-09，V77 建表）。
 *
 * <p>append-only：只 INSERT/SELECT（V78 REVOKE UPDATE/DELETE 后 DB 层强制）。
 * 有意<b>不</b>继承 BaseEntity——无软删/乐观锁/updated_*（只增不改，这些列无意义）；
 * created_at 由 DB DEFAULT NOW() 兜底，插入可不传。
 *
 * <p>detailJson 红线：只存字段名+脱敏值，严禁密码/token/用户输入原文（安全检查清单）。
 */
@Data
@TableName(value = "audit_logs", autoResultMap = true)
public class AuditLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发生时间；null 则 DB DEFAULT NOW()。 */
    private OffsetDateTime createdAt;

    /** 与 app.log 的 traceId 同值，日志↔审计互查。 */
    private String traceId;

    /** 操作人（未登录/系统任务为 null）。 */
    private Long userId;

    /** 操作人登录名（冗余，防改用户名后失联）。 */
    private String username;

    /** 业务模块：auth/user/role/agent/kb/system/billing... */
    private String module;

    /** 动作：login/login_failed/role_update/kb_delete... */
    private String action;

    /** 对象类型：role/agent/kb/document... */
    private String targetType;

    /** 对象 id（字符串兼容非数字主键）。 */
    private String targetId;

    /** 操作摘要 JSON（字段名+脱敏值），严禁 PII 原文。String↔jsonb 走 JsonbStringTypeHandler（同 Canvas.snapshot）。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String detailJson;

    /** X-Forwarded-For 首段或 remoteAddr。 */
    private String clientIp;

    /** 截断存储（≤256）。 */
    private String userAgent;

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAIL = "FAIL";

    /** SUCCESS / FAIL（登录失败等也留痕）。 */
    private String result = RESULT_SUCCESS;

    /** 安全体系 S2 D1（SEC-FR-040）：前一行 record_hash；首条链上行=GENESIS；null=存量链外行（V81 前）。 */
    private String prevHash;

    /** 安全体系 S2 D1（SEC-FR-040）：HMAC-SHA256(canonical(row)+prev_hash, AUDIT_HMAC_KEY) 小写 hex。 */
    private String recordHash;
}
