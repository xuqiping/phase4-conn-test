package com.superprogrammer.projectgroup.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.projectgroup.entity.ProjectGroupWalletEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 组池钱包 Mapper（单行/组；扣减走条件 UPDATE 防并发透支，镜像个人钱包模式）。
 */
@Mapper
public interface ProjectGroupWalletMapper extends BaseMapper<ProjectGroupWalletEntity> {

    /**
     * 条件扣减：余额够才扣（WHERE balance>=cost），返回影响行数——0=余额不足。
     * 数据库行锁保证原子性，天然防两成员并发扣最后一百分双成功。
     */
    @Update("UPDATE project_group_wallets SET balance_points = balance_points - #{cost}, updated_at = NOW() "
            + "WHERE group_id = #{groupId} AND balance_points >= #{cost}")
    int deduct(@Param("groupId") Long groupId, @Param("cost") BigDecimal cost);

    /** 入账（划拨/退款）：无条件加（CHECK>=0 兜底，加钱不会越界）。 */
    @Update("UPDATE project_group_wallets SET balance_points = balance_points + #{amount}, updated_at = NOW() "
            + "WHERE group_id = #{groupId}")
    int credit(@Param("groupId") Long groupId, @Param("amount") BigDecimal amount);
}
