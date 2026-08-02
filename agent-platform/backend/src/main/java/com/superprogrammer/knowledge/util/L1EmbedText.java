package com.superprogrammer.knowledge.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.service.internal.L1Metadata;

/**
 * L1 文档元数据 → embed 文本（Phase3）。
 * 拼接 summary + outline + importantRules（"；"分隔），复用 RagRetrievalService.loadL1 同款 join 语义。
 *
 * <p>纯函数，供三处共用（避免拼接逻辑漂移）：
 * <ul>
 *   <li>{@code KnowledgeNodeWriter} — 建 UPSERT_L1 job 时算 L1 文本 hash（idempotency_key + content_hash）</li>
 *   <li>{@code IndexJobWorker} — UPSERT_L1 job 消费时取 embed 文本</li>
 *   <li>{@code IndexJobTxService} — completeUpsertL1 tx 内复算 hash 防中途变更</li>
 * </ul>
 */
public final class L1EmbedText {

    private L1EmbedText() {
    }

    /** summary；outline 各项；importantRules 各项，"；"拼接，全空返 ""。 */
    public static String build(L1Metadata l1) {
        if (l1 == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendNonBlank(sb, l1.getSummary());
        if (l1.getOutline() != null) {
            for (String o : l1.getOutline()) {
                appendNonBlank(sb, o);
            }
        }
        if (l1.getImportantRules() != null) {
            for (String r : l1.getImportantRules()) {
                appendNonBlank(sb, r);
            }
        }
        return sb.toString();
    }

    private static void appendNonBlank(StringBuilder sb, String s) {
        if (s == null || s.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("；");
        }
        sb.append(s.strip());
    }

    /**
     * L1 文本 sha256（writer 建 job / worker embed / tx 复校 三处共用，防拼接+hash 漂移导致复校误判）。
     * l1Json 空→sha256("")；解析失败→回退 raw json hash（保幂等键稳定，不再 log：调用方上下文不同）。
     */
    public static String hashOfJson(String l1Json, ObjectMapper om) {
        if (l1Json == null || l1Json.isBlank()) {
            return HashUtil.sha256("");
        }
        try {
            return HashUtil.sha256(build(om.readValue(l1Json, L1Metadata.class)));
        } catch (Exception e) {
            return HashUtil.sha256(l1Json);
        }
    }
}
