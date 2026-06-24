package com.superprogrammer.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/** /api/knowledge/ask 入参（阶段5 RAG 流式问答）。 */
@Data
public class AskRequest {

    @NotBlank
    private String query;

    /** 检索范围（P4 求交：用户权限 ∩ 此列表）；空 → 无可检索范围 → abstain。 */
    private List<Long> kbIds;
}
