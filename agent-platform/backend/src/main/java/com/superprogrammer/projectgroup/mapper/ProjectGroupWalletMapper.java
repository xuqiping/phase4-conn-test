package com.superprogrammer.projectgroup.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.projectgroup.entity.ProjectGroupWalletEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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

    @Select("SELECT * FROM project_group_wallets WHERE group_id = #{groupId}")
    ProjectGroupWalletEntity selectByGroupId(@Param("groupId") Long groupId);

    /**
     * 行锁读（SELECT ... FOR UPDATE）：backstop 不写组池但要落 balance_after，
     * 锁行取一致余额，防并发 CONSUME 插中间导致「末行 balance_after ≠ 钱包」对账假阳。
     * 锁序：调用方须先锁个人行再进本方法（先个人后组固定锁序）。
     */
    @Select("SELECT * FROM project_group_wallets WHERE group_id = #{groupId} FOR UPDATE")
    ProjectGroupWalletEntity selectByGroupIdForUpdate(@Param("groupId") Long groupId);

    /**
     * 在途占用（reclaim 上限校验用）：Σ(estimated_cost)，status∈PENDING/RUNNING。
     * 媒体任务提交即扣组池并落 estimated_cost（Step5），回收须留够在途的钱。
     */
    @Select("SELECT COALESCE(SUM(estimated_cost), 0) FROM media_gen_tasks "
            + "WHERE project_group_id = #{groupId} AND status IN ('PENDING','RUNNING')")
    BigDecimal sumInflightEstimated(@Param("groupId") Long groupId);
}
