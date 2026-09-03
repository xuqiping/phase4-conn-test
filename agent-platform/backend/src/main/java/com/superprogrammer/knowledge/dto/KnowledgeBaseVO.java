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
    /** 14x#1：per-KB RAG 问答模型，null=跟随全局默认 */
    private String answerModel;
    /** 14x#3：库级保密开关（前端徽标/入口隐藏依据） */
    private boolean confidential;
    private String summaryStrategy;
    /** ColPali 实验通道 KB 级开关（WP5 Step4；实验通道默认全关，前端仅实验入口展示） */
    private boolean colpaliEnabled;
    private String status;
    private Long createdBy;
    private OffsetDateTime createdAt;

    /** 14x#1（L4）：换 embedding 且库内已有文档时的重建索引强提示，正常为 null */
    private String warning;

    /** 调用者对该 KB 的访问（供前端显隐按钮） */
    private boolean canManage;
    /** 14x#2：per-KB 写权限（上传/直传显隐；canRead 单独授权不再隐含写） */
    private boolean canWrite;
    private boolean canRead;
}
