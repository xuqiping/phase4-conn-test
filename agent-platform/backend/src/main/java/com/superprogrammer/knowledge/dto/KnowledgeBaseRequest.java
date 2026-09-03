package com.superprogrammer.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KnowledgeBaseRequest {

    @NotBlank
    private String name;

    private String description;

    /** PRIVATE / TEAM / PUBLIC，默认 PRIVATE */
    private String visibility;

    /** Phase1 默认 doubao */
    private String embeddingModel;

    private String rerankModel;

    /** 14x#1：per-KB RAG 问答模型，null/空=跟随全局默认；服务端校验须在启用 CHAT 列表内且 ≤128 */
    private String answerModel;

    /** 14x#3：库级保密开关（owner/admin 可切换；PUBLIC 库禁开——互斥校验在 Service） */
    private Boolean confidential;

    /** L0 摘要模式：PER_SECTION / BATCH / HYBRID，留空走默认 */
    private String summaryStrategy;

    /** ColPali 实验通道 KB 级开关（WP5 Step4；update 时 null=不动既有开关） */
    private Boolean colpaliEnabled;
}
