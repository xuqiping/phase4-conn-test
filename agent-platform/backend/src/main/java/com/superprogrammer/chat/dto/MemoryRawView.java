package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 计划12 · C · raw 流水账视图（透明化 / 遗忘权）。
 * <p>
 * gen 关态写的 raw 原文用户本人可见可删（总体设计 §3.1）。
 * <b>无导出/下载</b>——仅在线查看。
 * 二期 P1（V67）：turns 纯个人域——bornPersonal/projectIds 随一期项目挂载/出身标记下线。
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
    private OffsetDateTime createdAt;
}
