package com.superprogrammer.projectgroup.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 组产出明细行 VO（计划5 Step7，GET /project-groups/{id}/outputs；V138 增产物文件字段）。
 * 源=llm_usage_logs（组维度消耗），usage 行不带 prompt → 媒体行附 media_gen_tasks 概要
 * （prompt 摘要+任务状态）供前端渲染；CHAT 行 media 字段为 null。
 * <p>V138（17x#1）：媒体行附产物文件引用（视频 resultFileId / 图片 imageFileIds），
 * 仅当请求者按组可见性设置可见该行产物时非 null（ProjectGroupVisibilityService 判定；
 * 文件读取由 ProjectGroupFileAccessGrantor 同口径放行）。CHAT 行恒 null。
 */
public record ProjectGroupOutputVO(
        Long id,
        OffsetDateTime createdAt,
        Long userId,
        String username,
        /** 17x#2：昵称/姓名（users.name，空回落 username；用户已删 null）。 */
        String displayName,
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
        String mediaPrompt,
        /** 视频产物 fileId（→ stored_files.file_id；无产物/不可见 null）。 */
        String resultFileId,
        /** 图片产物 fileId 列表（无产物/不可见 null）。 */
        List<String> imageFileIds,
        /** 17x-2026-08-25：CHAT 行会话最新 assistant 回复（预览列展示生成结果；非 CHAT null）。 */
        String chatResult) {
}
