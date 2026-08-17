package com.superprogrammer.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class KnowledgeBaseVO {

    private Long id;
    private String name;
    private String description;
    private String visibility;
    private String embeddingModel;
    private String rerankModel;
    private String summaryStrategy;
    private String status;
    private Long createdBy;
    private OffsetDateTime createdAt;

    /** 调用者对该 KB 的访问（供前端显隐按钮） */
    private boolean canManage;
    /** 14x#2：per-KB 写权限（上传/直传显隐；canRead 单独授权不再隐含写） */
    private boolean canWrite;
    private boolean canRead;
}
