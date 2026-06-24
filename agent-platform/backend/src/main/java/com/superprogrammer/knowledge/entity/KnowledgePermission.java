package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 知识库授权。表无 deleted/version/updated 审计列（仅 granted_by + created_at），
 * 故不继承 BaseEntity。撤销 = 硬删。
 */
@Data
@TableName("knowledge_permissions")
public class KnowledgePermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    /** KB / DIRECTORY / DOCUMENT */
    private String targetType;

    private Long targetId;

    /** USER / ROLE / DEPARTMENT / SERVICE_ACCOUNT */
    private String subjectType;

    private Long subjectId;

    private Boolean canRead;

    private Boolean canWrite;

    private Boolean canManage;

    private Long grantedBy;

    private OffsetDateTime createdAt;
}
