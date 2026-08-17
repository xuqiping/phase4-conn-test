package com.superprogrammer.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** /api/knowledge/ask 入参（阶段5 RAG 流式问答；S3 C4 收紧入参上限）。 */
@Data
public class AskRequest {

    @NotBlank
    @Size(max = 2000, message = "查询长度不能超过2000字符")
    private String query;

    /** 检索范围（P4 求交：用户权限 ∩ 此列表）；空 → 无可检索范围 → abstain。 */
    @Size(max = 20)
    private List<Long> kbIds;

    /**
     * 计划5 Step4：组池计费归属（null=个人钱包）。控制器入口种入 BillingContext，
     * ask 链路内部 query embed / rerank / answer 生成经网关回退自动归组计费。
     */
    private Long projectGroupId;
}
