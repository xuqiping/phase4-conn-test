package com.superprogrammer.projectgroup.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 组产出明细行 VO（计划5 Step7，GET /project-groups/{id}/outputs）。
 * 源=llm_usage_logs（组维度消耗），usage 行不带 prompt → 媒体行附 media_gen_tasks 概要
 * （prompt 摘要+任务状态）供前端渲染；CHAT 行 media 字段为 null。
 * 产物文件预览不在此放开（文件端点归属门控），前端仅本人行可跳任务详情看预览。
 */
public record ProjectGroupOutputVO(
        Long id,
        OffsetDateTime createdAt,
        Long userId,
        String username,
        /** CHAT/EMBED/RERANK/IMAGE/VIDEO。 */
        String kind,
        String model,
        BigDecimal pointsConsumed,
        /** SUCCESS/FAILED/ESTIMATED（usage 侧）。 */
        String status,
        /** 媒体任务 id（CHAT/EMBED 等无任务行 null）。 */
        Long taskId,
        /** 媒体任务状态（PENDING/RUNNING/SUCCEEDED/...；无任务行 null）。 */
        String mediaStatus,
        /** 媒体任务提示词摘要（无任务行 null）。 */
        String mediaPrompt) {
}
