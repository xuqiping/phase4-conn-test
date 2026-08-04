package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 计划12 · C · raw 流水账视图（透明化 / 遗忘权）。
 * <p>
 * gen 关态写的 raw 原文用户本人可见可删（总体设计 §3.1）。
 * <b>无导出/下载</b>——仅在线查看。
 */
@Data
@Builder
public class MemoryRawView {
    private Long id;
    private Long sessionId;
    /** INPUT / OUTPUT。 */
    private String direction;
    /** 原文（gen 关态未提炼）。 */
    private String rawContent;
    /** 出身标记。 */
    private Boolean bornPersonal;
    /** 挂载项目集（用户本人的，可露）。 */
    private List<Long> projectIds;
    private OffsetDateTime createdAt;
}
