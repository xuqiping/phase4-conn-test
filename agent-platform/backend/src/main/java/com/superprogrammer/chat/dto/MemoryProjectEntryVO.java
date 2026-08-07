package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 项目记忆条目视图（记忆二期 P1 · FR-005）。
 * <p>
 * 「为何被收录」= ruleText（命中规则文案）+ confidence；均不含原文（raw_content 不出个人域）。
 * l2Detail 仅 owner/admin 与作者本人可见？——v1 从简：条目本就是脱敏蒸馏产物，成员即可读（设计 §2）。
 */
@Data
@Builder
public class MemoryProjectEntryVO {

    private Long id;
    private Long projectId;
    private Long authorUserId;
    private String authorName;       // 展示「张三·3 天前」（username 兜底 name）
    private String l1Summary;
    private String l2Detail;
    private Double confidence;
    private String status;           // ACTIVE / PENDING_REVIEW
    private String contentType;      // TEXT / FILE
    private String ruleText;         // 命中规则文案（「为何被收录」）
    private OffsetDateTime createdAt;
}
