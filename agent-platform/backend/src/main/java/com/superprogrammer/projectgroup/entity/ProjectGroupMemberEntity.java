package com.superprogrammer.projectgroup.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 组成员记账行（project_group_members，V133）。
 * <p>quota_limit_points=组长配置的累计消耗上限（NULL 不限，组长默认 NULL）；
 * used_points=同事务维护的冗余快照（随消耗增/退款减，免 SUM 扫描 usage_log）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_group_members")
public class ProjectGroupMemberEntity extends BaseEntity {

    /** 所属组 → project_groups.id。 */
    private Long groupId;

    /** 成员用户 id。 */
    private Long userId;

    /** 累计消耗上限；NULL=不限。调低不追偿仅限后续。 */
    private BigDecimal quotaLimitPoints;

    /** 已耗积分快照（>=0，CHECK 兜底）。 */
    private BigDecimal usedPoints;
}
