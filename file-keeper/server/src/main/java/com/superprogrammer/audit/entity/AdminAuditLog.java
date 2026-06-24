package com.superprogrammer.audit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("admin_audit_logs")
public class AdminAuditLog extends BaseEntity {

    private Long adminUserId;
    private String action;
    private String targetType;
    private String targetId;
    private String detail;
    private String ipAddress;
}
