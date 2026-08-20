package com.superprogrammer.projectgroup.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 公共池入组申请（project_group_join_requests，V138，17x#4）。
 * <p>镜像资产公众池申请（AssetPublicAccessRequest）状态机：
 * 本人发起 PENDING →（组长）APPROVED（落成员行 quota NULL）/ REJECTED（30 天防刷窗口在服务层）；
 * 组长撤池级联 PENDING→REVOKED；申请人可取消 PENDING（软删）。REJECTED 超期/REVOKED 再申 = 同行复活。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_group_join_requests")
public class ProjectGroupJoinRequestEntity extends BaseEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_REVOKED = "REVOKED";

    private Long groupId;

    /** 申请人（本人发起）。 */
    private Long userId;

    /** 申请留言（≤200，可选）。 */
    private String message;

    /** PENDING/APPROVED/REJECTED/REVOKED。 */
    private String status;

    private Long decidedBy;
    private OffsetDateTime decidedAt;
}
