package com.superprogrammer.projectgroup.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 组成员记账行（project_group_members，V133）。
 * <p>quota_limit_points=组长配置的累计消耗上限（NULL 不限，组长默认 NULL）；
 * used_points=同事务维护的冗余快照（随消耗增/退款减，免 SUM 扫描 usage_log）。
 * <p>V139 加成员权限三维：role（OWNER/MANAGER/MEMBER）+ allowed_kinds（可用模块白名单，NULL 不限）+
 * member_visibility_overrides（成员级产出可见性稀疏覆盖，优先于组级设置）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("project_group_members")
public class ProjectGroupMemberEntity extends BaseEntity {

    /** 组内角色：组长。每组仅一个活行（uk_pgm_owner）。 */
    public static final String ROLE_OWNER = "OWNER";
    /** 组内角色：管理（管人不管钱）。 */
    public static final String ROLE_MANAGER = "MANAGER";
    /** 组内角色：普通成员（默认）。 */
    public static final String ROLE_MEMBER = "MEMBER";

    /** 所属组 → project_groups.id。 */
    private Long groupId;

    /** 成员用户 id。 */
    private Long userId;

    /** 累计消耗上限；NULL=不限。调低不追偿仅限后续。 */
    private BigDecimal quotaLimitPoints;

    /** 已耗积分快照（>=0，CHECK 兜底）。 */
    private BigDecimal usedPoints;

    /** 组内角色（V139）：OWNER/MANAGER/MEMBER，默认 MEMBER。 */
    private String role;

    /** 可用模块白名单 JSONB 数组字符串（V139）：NULL=不限；[]=全禁；元素∈CHAT/EMBED/RERANK/IMAGE/VIDEO。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String allowedKinds;

    /** 成员级产出可见性稀疏覆盖 JSONB（V139）：{"VIDEO":"OWN"}；NULL=无覆盖回落组级。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String memberVisibilityOverrides;

    /**
     * 额度分配人（V156 层级额度）：NULL=组长行；组长 id=组池直管；管理 id=占该管理预算。
     * 管理可分配 = 自己额度 − (自己已用+Σ下级已用) − Σ下级 GREATEST(quota−used,0)。
     */
    private Long allocatedByUserId;

    /** 组内名下余额（V161 修复III）：成员从个人钱包划入、记在自己成员行上的钱，组长回收/调限额碰不到。 */
    private BigDecimal selfPoints;

    /** 欠款·组池垫付（V161 修复III）：超限额溢出中由组池垫付的部分，还款回组池。 */
    private BigDecimal debtPoolPoints;

    /** 欠款·组长垫付（V161 修复III）：超限额溢出中由组长个人兜底的部分，还款优先退组长。 */
    private BigDecimal debtLeaderPoints;
}
