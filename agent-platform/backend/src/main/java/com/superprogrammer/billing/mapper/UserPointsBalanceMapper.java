package com.superprogrammer.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.billing.entity.UserPointsBalanceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * 钱包余额 Mapper。
 * <p>核心 {@link #adjustBalanceReturn} 用 PostgreSQL <code>UPDATE ... RETURNING</code>：
 * 调余额同时返回新余额，单语句原子 + 行锁，防并发扣减超支（plan §坑点 ① / spec §4 并发安全）。
 */
@Mapper
public interface UserPointsBalanceMapper extends BaseMapper<UserPointsBalanceEntity> {

    /**
     * 原子调余额（扣/加通用）。delta 负=扣，正=加。
     * <p>UPDATE ... RETURNING balance_points：扣同时返回新余额，行锁串行化并发扣减。
     * 调用前须 {@link #insertIfAbsent} 保证行存在；行不存在时 RETURNING 0 行 → 返 null。
     */
    @Select("UPDATE user_points_balance "
            + "SET balance_points = balance_points + #{delta}, updated_at = NOW() "
            + "WHERE user_id = #{userId} RETURNING balance_points")
    BigDecimal adjustBalanceReturn(@Param("userId") Long userId, @Param("delta") BigDecimal delta);

    /**
     * 幂等建余额行（首次充值/消耗前）。不存在则建 0 余额行，存在 ON CONFLICT 不动。
     * <p>并发安全：两线程同时建，UNIQUE(user_id) + ON CONFLICT DO NOTHING 保证只一行。
     */
    @Insert("INSERT INTO user_points_balance (user_id, balance_points, updated_at) "
            + "VALUES (#{userId}, 0, NOW()) ON CONFLICT (user_id) DO NOTHING")
    int insertIfAbsent(@Param("userId") Long userId);

    @Select("SELECT * FROM user_points_balance WHERE user_id = #{userId}")
    UserPointsBalanceEntity selectByUserId(@Param("userId") Long userId);
}
