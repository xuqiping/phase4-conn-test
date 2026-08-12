package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 项目授权链（V68 记忆二期 P2 · FR-101）。表走 BaseEntity 软删 + 乐观锁。
 * <p>
 * child 项目条目授权给 parent 项目成员召回（单级不传递）。
 * 状态机：PENDING → ACTIVE / REJECTED（条件 UPDATE 防并发）；ACTIVE → REVOKED（双方可撤，行留痕不删）；
 * REJECTED 30 天后 / REVOKED 后再发起 = 同行复活 PENDING（防刷键 uk 部分唯一，按 created_at 判）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("memory_project_links")
public class MemoryProjectLink extends BaseEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_REVOKED = "REVOKED";

    private Long parentProjectId;    // 被授权方（召回受益项目）
    private Long childProjectId;     // 授权方（条目来源项目）
    private Long grantedBy;          // 发起人（child owner）
    private Long approvedBy;         // 审批人（parent owner/admin）
    private String status;           // PENDING / ACTIVE / REJECTED / REVOKED
    private OffsetDateTime approvedAt;
    // 三期非对称撤销：child owner 主动撤销的申请人（非空=撤销申请挂起待 parent 审批；status 仍 ACTIVE）
    private Long revokeRequestedBy;
    private OffsetDateTime revokeRequestedAt;
}
