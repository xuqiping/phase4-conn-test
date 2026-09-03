package com.superprogrammer.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** C1 建边请求。关联仅限同库：docId/relatedDocId 必须同属 kbId 的库。 */
@Data
public class KnowledgeRelationRequest {

    @NotNull(message = "kbId 不能为空")
    private Long kbId;

    @NotNull(message = "docId 不能为空")
    private Long docId;

    @NotNull(message = "relatedDocId 不能为空")
    private Long relatedDocId;

    /** MUST_CITE / MAY_CITE / MUST_BE_CITED / MAY_BE_CITED */
    @NotBlank(message = "relationType 不能为空")
    private String relationType;

    @Size(max = 500, message = "备注最长 500 字")
    private String note;
}
