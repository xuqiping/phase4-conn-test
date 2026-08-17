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
}
