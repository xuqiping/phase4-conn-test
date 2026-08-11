package com.superprogrammer.common.audit;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 审计日志查询返回 VO（日志系统 LOG-FR-12）。detailJson 原样透传（入库前已脱敏截断）。
 *
 * <p>{@code moduleLabel/actionLabel} 为显示层中文（问题修复 #2），由 {@link AuditLabelDictionary}
 * 翻译，DB 码值不变。{@link #from(AuditLogEntity)} 只拷贝实体字段，标签由 Controller 调
 * {@link #withLabels()} 填充——保持 VO 转换无外部依赖、可静态调用。
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
    /** 模块中文（显示层，不入库）。 */
    private String moduleLabel;
    /** 动作中文（显示层，不入库）。 */
    private String actionLabel;

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

    /** 填充中文标签（module/action 经 {@link AuditLabelDictionary} 翻译）。 */
    public AuditLogVO withLabels() {
        this.moduleLabel = AuditLabelDictionary.moduleLabel(module);
        this.actionLabel = AuditLabelDictionary.actionLabel(module, action);
        return this;
    }
}
