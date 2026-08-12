package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 项目↔个人授权（V79 记忆二期 P1）。表走 BaseEntity 软删 + 乐观锁。
 * <p>
 * 把「项目条目的召回读权」授权给某个个人。双向发起：
 * <ul>
 *   <li>{@code initiated_by=PROJECT}：项目 owner/admin 主动授权个人 → 立即 ACTIVE（发起方即审批方）。</li>
 *   <li>{@code initiated_by=USER}：个人申请召回某项目 → PENDING，待项目 owner/admin 审批。</li>
 * </ul>
 * 状态机：PENDING → ACTIVE / REJECTED（条件 UPDATE 防并发）；ACTIVE → REVOKED（双方可撤，行留痕不删）；
 * REJECTED 30 天后 / REVOKED 后再发起 = 同行复活 PENDING（防刷键 uk 部分唯一，按 created_at 判）。
 * 只读召回：ACTIVE 让 {@code user_id} 可勾选 {@code project_id} 到召回范围并召回其条目摘要；不写回。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("memory_project_user_grants")
public class MemoryProjectUserGrant extends BaseEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_REVOKED = "REVOKED";

    public static final String INITIATED_BY_PROJECT = "PROJECT";
    public static final String INITIATED_BY_USER = "USER";

    private Long projectId;          // 被授权项目（条目来源）
    private Long userId;             // 被授权个人（召回受益人）
    private String initiatedBy;      // PROJECT=项目主动授权 / USER=个人申请
    private Long grantedBy;          // 发起人（PROJECT 侧=项目 owner/admin；USER 侧=申请人自己）
    private Long approvedBy;         // 审批人（USER 发起时=项目 owner/admin）
    private String status;           // PENDING / ACTIVE / REJECTED / REVOKED
    private OffsetDateTime approvedAt;
}
