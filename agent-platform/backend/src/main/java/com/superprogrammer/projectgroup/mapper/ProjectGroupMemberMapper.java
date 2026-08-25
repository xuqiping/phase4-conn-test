package com.superprogrammer.projectgroup.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 组成员 Mapper（限额/used 走条件 UPDATE 行锁，chargeGroup 消耗路径）。
 */
@Mapper
public interface ProjectGroupMemberMapper extends BaseMapper<ProjectGroupMemberEntity> {

    @Select("SELECT * FROM project_group_members WHERE group_id = #{groupId} AND user_id = #{userId} AND deleted = 0")
    ProjectGroupMemberEntity selectByGroupUser(@Param("groupId") Long groupId, @Param("userId") Long userId);

    /**
     * 行锁读（V156 层级额度）：管理预算串行化点——凡动「管理可分配」的操作
     * （管理给成员配额度/邀请接受落行/管理本人消耗）都先锁管理行再算可分配，
     * 防并发双分配/边花边分超发。
     */
    @Select("SELECT * FROM project_group_members WHERE group_id = #{groupId} AND user_id = #{userId} AND deleted = 0 FOR UPDATE")
    ProjectGroupMemberEntity selectByGroupUserForUpdate(@Param("groupId") Long groupId, @Param("userId") Long userId);

    /** Σ 下级已用（V156）：管理子树消耗和（含下级消耗冒泡到管理总额度）。 */
    @Select("SELECT COALESCE(SUM(used_points), 0) FROM project_group_members "
            + "WHERE group_id = #{groupId} AND allocated_by_user_id = #{parentUserId} AND deleted = 0")
    BigDecimal sumChildUsed(@Param("groupId") Long groupId, @Param("parentUserId") Long parentUserId);

    /**
     * Σ 下级预留（V156）：GREATEST(quota−used,0) 只计限额非空行；
     * 限额为空的下级见 {@link #countChildUnbounded}（被限额管理不允许存在，防御口径）。
     */
    @Select("SELECT COALESCE(SUM(GREATEST(quota_limit_points - used_points, 0)), 0) FROM project_group_members "
            + "WHERE group_id = #{groupId} AND allocated_by_user_id = #{parentUserId} AND deleted = 0 "
            + "AND quota_limit_points IS NOT NULL "
            + "AND (#{excludeUserId}::bigint IS NULL OR user_id <> #{excludeUserId})")
    BigDecimal sumChildReserved(@Param("groupId") Long groupId, @Param("parentUserId") Long parentUserId,
                                @Param("excludeUserId") Long excludeUserId);

    /** 限额为空的下级数（V156 防御：被限额管理下不应存在；exclude=正被收编的目标行）。 */
    @Select("SELECT COUNT(*) FROM project_group_members "
            + "WHERE group_id = #{groupId} AND allocated_by_user_id = #{parentUserId} AND deleted = 0 "
            + "AND quota_limit_points IS NULL "
            + "AND (#{excludeUserId}::bigint IS NULL OR user_id <> #{excludeUserId})")
    long countChildUnbounded(@Param("groupId") Long groupId, @Param("parentUserId") Long parentUserId,
                             @Param("excludeUserId") Long excludeUserId);

    /** 下级改挂（V156）：管理降回成员时其下级统一改挂组长，预算不悬空。 */
    @Update("UPDATE project_group_members SET allocated_by_user_id = #{toUserId}, updated_at = NOW(), version = version + 1 "
            + "WHERE group_id = #{groupId} AND allocated_by_user_id = #{fromUserId} AND deleted = 0")
    int reparentChildren(@Param("groupId") Long groupId, @Param("fromUserId") Long fromUserId,
                         @Param("toUserId") Long toUserId);

    /** 直接下级快照（17x-1 降职缩额）：在持有目标管理行 FOR UPDATE 事务内读，算 Σ下级 quota。 */
    @Select("SELECT * FROM project_group_members WHERE group_id = #{groupId} "
            + "AND allocated_by_user_id = #{parentUserId} AND deleted = 0")
    java.util.List<ProjectGroupMemberEntity> selectChildren(@Param("groupId") Long groupId,
                                                            @Param("parentUserId") Long parentUserId);

    /**
     * 条件累加 used（消耗）：quota NULL 不限；否则 used+cost≤quota 才动。
     * 返 0 行=超限额（或非成员/已软删），外层事务回滚组池扣减。
     */
    @Update("UPDATE project_group_members SET used_points = used_points + #{cost}, updated_at = NOW(), version = version + 1 "
            + "WHERE group_id = #{groupId} AND user_id = #{userId} AND deleted = 0 "
            + "AND (quota_limit_points IS NULL OR used_points + #{cost} <= quota_limit_points)")
    int addUsed(@Param("groupId") Long groupId, @Param("userId") Long userId, @Param("cost") BigDecimal cost);

    /**
     * 退款回减 used：GREATEST 落 0（防负，CHECK ck_pgm_used_nonneg 兜底）。
     * 注：若组长已重置 used（迟到退款），Σ(CONSUME-REFUND) 会小于 used——对账模板该行会黄，属罕见运维人工核对项。
     */
    @Update("UPDATE project_group_members SET used_points = GREATEST(used_points - #{cost}, 0), updated_at = NOW(), version = version + 1 "
            + "WHERE group_id = #{groupId} AND user_id = #{userId} AND deleted = 0")
    int subtractUsed(@Param("groupId") Long groupId, @Param("userId") Long userId, @Param("cost") BigDecimal cost);

    /**
     * 复活软删成员行（17x#1 修 uk_pgm_group_user 409）：回归即重置——
     * quota=新邀请值、used=0（限额周期重新起算，历史消耗在组流水/usage_log 仍可查）、
     * role=MEMBER、allowed_kinds/member_visibility_overrides 清空（不继承移除前状态）、
     * allocated_by=本次分配人（V156 层级额度）。
     * 条件 UPDATE 天然互斥：并发双接受恰一线程命中。
     *
     * @return 1=复活成功；0=无软删残留行（调用方走探针/新插）
     */
    @Update("UPDATE project_group_members SET deleted = 0, quota_limit_points = #{quota}, used_points = 0, "
            + "role = 'MEMBER', allowed_kinds = NULL, member_visibility_overrides = NULL, "
            + "allocated_by_user_id = #{allocatedBy}, "
            + "updated_at = NOW(), version = version + 1 "
            + "WHERE group_id = #{groupId} AND user_id = #{userId} AND deleted = 1")
    int reviveRow(@Param("groupId") Long groupId, @Param("userId") Long userId,
                  @Param("quota") BigDecimal quota, @Param("allocatedBy") Long allocatedBy);
}
