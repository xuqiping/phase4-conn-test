package com.superprogrammer.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.Set;

/** 公众池审批型项目的独立访问申请，不写入项目成员表。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("asset_public_access_requests")
public class AssetPublicAccessRequest extends BaseEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final Set<String> STATUSES = Set.of(
            STATUS_PENDING, STATUS_APPROVED, STATUS_REJECTED, STATUS_REVOKED);

    private Long projectId;
    private Long applicantId;
    private String status;
    private Long decidedBy;
    private OffsetDateTime decidedAt;
}
