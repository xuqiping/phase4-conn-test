package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 计划12 B：标签对外 VO（向量 4）。
 * <p>
 * 只露 label + subject + topic + usage_count（+ id 供 owner 编辑寻址）。
 * aliases / anchor_embedding / anchor_tokens 一律不外露——同义归一是内部机制，
 * 暴露别名集等于把「哪些词被并到了一起」泄露给前端，既无业务必要也增加误用面。
 */
@Data
@Builder
public class MemoryTagVO {
    private Long id;
    private String subject;
    private String topic;
    private String label;
    private Integer usageCount;
}
