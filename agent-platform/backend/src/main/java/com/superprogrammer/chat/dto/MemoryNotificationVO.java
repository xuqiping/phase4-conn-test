package com.superprogrammer.chat.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 计划12 · F · 波及通知展示 VO（总体设计 §3.8）。
 * <p>
 * 前端波及 badge + 折叠板用：{@code type} 区分「他人撤回 turn 波及我的 summary」与「项目删除影响」。
 */
@Data
public class MemoryNotificationVO {

    private Long id;
    private String type;          // SUMMARY_AFFECTED_BY_RECALL / PROJECT_DELETED_AFFECTED
    private Long refId;           // 关联 summary/project id
    private String message;
    private OffsetDateTime createdAt;
}
