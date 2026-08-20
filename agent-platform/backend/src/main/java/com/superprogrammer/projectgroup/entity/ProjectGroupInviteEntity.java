package com.superprogrammer.projectgroup.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 组邀请（project_group_invites，V138，17x#3）。
 * <p>加成员从「组长直接写入」改「邀请 → 被邀请人同意」：
 * PENDING →（被邀请人）ACCEPTED（落成员行）/ DECLINED；（组长）CANCELED。
 * 同组同人仅一条 PENDING（部分唯一索引）；DECLINED 再邀请走同行复活 + 条件 UPDATE（grant 先例）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_group_invites")
public class ProjectGroupInviteEntity extends BaseEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_DECLINED = "DECLINED";
    public static final String STATUS_CANCELED = "CANCELED";

    private Long groupId;

    /** 发起人（组长/admin 代管）。 */
    private Long inviterUserId;

    /** 被邀请人（决策方）。 */
    private Long inviteeUserId;

    /** 接受后落成员行的限额快照（NULL=不限）。 */
    private BigDecimal quotaLimitPoints;

    /** PENDING/ACCEPTED/DECLINED/CANCELED。 */
    private String status;

    private OffsetDateTime decidedAt;
}
