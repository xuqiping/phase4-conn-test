package com.superprogrammer.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class RagRetrieveRequest {

    @NotNull
    private Long kbId;

    @NotBlank
    private String query;

    /** metadata 硬 pre-filter：policy/manual/faq/...；空 = 不过滤 */
    private List<String> docTypes;

    /** 覆盖 dense top-N；空 = 用 RagConfig.maxL0Candidates */
    private Integer maxL0;

    /** ECONOMY / BALANCED / PRECISION（Phase1 仅 BALANCED）*/
    private String mode;

    /** controller 注入（非请求字段）：当前用户是否 admin */
    private boolean adminHint;
}
