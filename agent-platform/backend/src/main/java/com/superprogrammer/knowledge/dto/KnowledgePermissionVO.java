package com.superprogrammer.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class KnowledgePermissionVO {

    private Long id;
    private String targetType;
    private Long targetId;
    private String subjectType;
    private Long subjectId;
    /** 解析后的主体名（用户名/角色名/部门名） */
    private String subjectName;
    private Boolean canRead;
    private Boolean canWrite;
    private Boolean canManage;
    private Long grantedBy;
    private OffsetDateTime createdAt;
}
