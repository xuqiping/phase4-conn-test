package com.superprogrammer.common.audit;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 审计日志查询返回 VO（日志系统 LOG-FR-12）。detailJson 原样透传（入库前已脱敏截断）。
 */
@Data
public class AuditLogVO {
    private Long id;
    private OffsetDateTime createdAt;
    private String traceId;
    private Long userId;
    private String username;
    private String module;
    private String action;
    private String targetType;
    private String targetId;
    private String detailJson;
    private String clientIp;
    private String userAgent;
    private String result;

    public static AuditLogVO from(AuditLogEntity e) {
        AuditLogVO vo = new AuditLogVO();
        vo.setId(e.getId());
        vo.setCreatedAt(e.getCreatedAt());
        vo.setTraceId(e.getTraceId());
        vo.setUserId(e.getUserId());
        vo.setUsername(e.getUsername());
        vo.setModule(e.getModule());
        vo.setAction(e.getAction());
        vo.setTargetType(e.getTargetType());
        vo.setTargetId(e.getTargetId());
        vo.setDetailJson(e.getDetailJson());
        vo.setClientIp(e.getClientIp());
        vo.setUserAgent(e.getUserAgent());
        vo.setResult(e.getResult());
        return vo;
    }
}
