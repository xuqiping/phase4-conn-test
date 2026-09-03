package com.superprogrammer.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 采纳关联建议的请求体。建议本身是无序对（docIdA<docIdB），方向语义由采纳时指定：
 * fromDocId 必须是建议两端之一（主动方），relationType 四选一（默认语义建议 MAY_CITE）。
 */
@Data
public class RelationSuggestionAdoptRequest {

    /** 主动方文档 id（= 建议的 docIdA 或 docIdB，决定边的方向读法） */
    private Long fromDocId;

    /** MUST_CITE / MAY_CITE / MUST_BE_CITED / MAY_BE_CITED */
    @NotBlank(message = "relationType 不能为空")
    private String relationType;

    @Size(max = 200, message = "备注不能超过 200 字")
    private String note;
}
