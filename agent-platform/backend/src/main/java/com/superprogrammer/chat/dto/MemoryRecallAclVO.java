package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 计划12 · I2 · ACL 授权矩阵行（总体设计 §3.6 + §6 向量 14）。
 * <p>
 * {@code GET /api/chat/memory/projects/{pid}/recall-acl} 返当前授权矩阵（扁平行 reader→target），
 * 前端按 readerUserId 聚合成「谁可读谁」。仅 owner / {@code recall_admin=true} admin 可看（I2 controller 判）。
 *
 * @see com.superprogrammer.chat.mapper.MemoryRecallAclMapper#findGrantedDetails
 */
@Data
@Builder
public class MemoryRecallAclVO {
    private Long readerUserId;
    private String readerUsername;
    private String readerName;
    private Long targetUserId;
    private String targetUsername;
    private String targetName;
    /** 授权操作人（审计，向量 15） */
    private Long createdBy;
    private OffsetDateTime createdAt;
}
