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
     * <p>安全体系 S1 · SEC-FR-120：追加 {@code AND balance_points + #{delta} >= 0} 守卫——
     * 并发透支时本语句 0 行返 null（service 层转 INSUFFICIENT_POINTS），
     * 「余额≥0」从预检软约定升级为 SQL 硬守卫；与 V80 CHECK 约束双保险。
     * V65 的「可负=欠款」模型自此作废：透支笔拒扣（计费层吞异常，用户本次免扣），不再欠债。
     */
    @Select("UPDATE user_points_balance "
            + "SET balance_points = balance_points + #{delta}, updated_at = NOW() "
            + "WHERE user_id = #{userId} AND balance_points + #{delta} >= 0 RETURNING balance_points")
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

    /**
     * B5（Q10=A）：行锁读余额+欠款（挂账/冲抵事务内先锁行再算 pay/repay，防并发窗口）。
     * 须在调用方 @Transactional 内使用；锁序恒为「钱包行最后」（与 backstop 锁序兼容）。
     */
    @Select("SELECT * FROM user_points_balance WHERE user_id = #{userId} FOR UPDATE")
    UserPointsBalanceEntity selectByUserIdForUpdate(@Param("userId") Long userId);

    /**
     * B5：欠款列原子调（正=挂账/负=冲抵）。守卫 {@code debt_points + delta >= 0}（V157 CHECK 同口径），
     * 违反返 null（service 层按业务错处理）；RETURNING 新欠款值。
     */
    @Select("UPDATE user_points_balance SET debt_points = debt_points + #{delta}, updated_at = NOW() "
            + "WHERE user_id = #{userId} AND debt_points + #{delta} >= 0 RETURNING debt_points")
    BigDecimal adjustDebtReturn(@Param("userId") Long userId, @Param("delta") BigDecimal delta);

    // ==================== admin 用户余额视图（20x#1） ====================

    /** 余额视图总数（keyword 筛选 username；users 无软删字段实体但 DB 有 deleted 列，SQL 层过滤）。 */
    @Select("<script>SELECT COUNT(*) FROM users u "
            + "<where> u.deleted = 0 "
            + "<if test='keyword != null and keyword != \"\"'> AND u.username LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\'</if>"
            + "</where></script>")
    long countUserBalances(@Param("keyword") String keyword);

    /**
     * 余额视图分页：users LEFT JOIN 钱包 LEFT JOIN PAID 聚合——无钱包行/无充值用户显 0（COALESCE）。
     * <p>排序列由 service 白名单映射后整段传入 orderClause（防注入；只允许白名单列+方向）。
     */
    @Select("<script>SELECT u.id AS userId, u.username, "
            + "COALESCE(b.balance_points, 0) AS balancePoints, "
            + "COALESCE(r.totalPoints, 0) AS totalRechargePoints, "
            + "COALESCE(r.totalAmount, 0) AS totalRechargeAmount, "
            + "r.lastAt AS lastRechargeAt "
            + "FROM users u "
            + "LEFT JOIN user_points_balance b ON b.user_id = u.id "
            + "LEFT JOIN (SELECT user_id, SUM(points_granted) AS totalPoints, SUM(amount_yuan) AS totalAmount, "
            + "MAX(paid_at) AS lastAt FROM payment_order WHERE status = 'PAID' GROUP BY user_id) r ON r.user_id = u.id "
            + "<where> u.deleted = 0 "
            + "<if test='keyword != null and keyword != \"\"'> AND u.username LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\'</if>"
            + "</where> ${orderClause} LIMIT #{size} OFFSET #{offset}</script>")
    java.util.List<com.superprogrammer.billing.dto.UserBalanceRowVO> pageUserBalances(
            @Param("keyword") String keyword, @Param("orderClause") String orderClause,
            @Param("offset") long offset, @Param("size") long size);

    /**
     * 合计卡（7x 反馈：跟随 keyword 筛选——筛选谁就合计谁；keyword 空=全平台口径；与明细行同 JOIN/WHERE 保证 Σ 一致）。
     * 返回顺序固定：totalUsers / sumBalance / sumRechargePoints / sumRechargeAmount。
     */
    @Select("<script>SELECT COUNT(*) AS totalUsers, "
            + "COALESCE(SUM(COALESCE(b.balance_points, 0)), 0) AS sumBalance, "
            + "COALESCE(SUM(COALESCE(r.totalPoints, 0)), 0) AS sumRechargePoints, "
            + "COALESCE(SUM(COALESCE(r.totalAmount, 0)), 0) AS sumRechargeAmount "
            + "FROM users u "
            + "LEFT JOIN user_points_balance b ON b.user_id = u.id "
            + "LEFT JOIN (SELECT user_id, SUM(points_granted) AS totalPoints, SUM(amount_yuan) AS totalAmount "
            + "FROM payment_order WHERE status = 'PAID' GROUP BY user_id) r ON r.user_id = u.id "
            + "<where> u.deleted = 0 "
            + "<if test='keyword != null and keyword != \"\"'> AND u.username LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\'</if>"
            + "</where></script>")
    java.util.Map<String, Object> platformBalanceTotals(@Param("keyword") String keyword);
}
