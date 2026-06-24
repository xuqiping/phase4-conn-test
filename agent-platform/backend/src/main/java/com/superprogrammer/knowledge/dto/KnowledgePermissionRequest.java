package com.superprogrammer.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class KnowledgePermissionRequest {

    /** KB / DIRECTORY / DOCUMENT */
    @NotBlank
    private String targetType;

    @NotNull
    private Long targetId;

    /** USER / ROLE / DEPARTMENT / SERVICE_ACCOUNT */
    @NotBlank
    private String subjectType;

    @NotNull
    private Long subjectId;

    private Boolean canRead;
    private Boolean canWrite;
    private Boolean canManage;
}
