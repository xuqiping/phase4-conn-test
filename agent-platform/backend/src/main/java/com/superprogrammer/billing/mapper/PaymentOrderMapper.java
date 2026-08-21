package com.superprogrammer.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.billing.entity.PaymentOrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * 充值订单 Mapper（MVP admin grant 直写 PAID；V140 自助支付状态机）。
 *
 * <p>状态迁移全部走条件 UPDATE（`WHERE status='PENDING'`）——0 行=抢态失败（已处理/已关单），
 * 与回调重推/过期 job 的竞态天然互斥，杜绝双重入账。
 */
@Mapper
public interface PaymentOrderMapper extends BaseMapper<PaymentOrderEntity> {

    @Select("SELECT * FROM payment_order WHERE channel = #{channel} AND channel_order_id = #{channelOrderId}")
    PaymentOrderEntity selectByChannelOrder(@Param("channel") String channel,
                                            @Param("channelOrderId") String channelOrderId);

    /** 创建幂等查重：同用户同 idemKey 的既有单（uk_payment_idem 兜底前的友好路径）。 */
    @Select("SELECT * FROM payment_order WHERE user_id = #{userId} AND idem_key = #{idemKey}")
    PaymentOrderEntity selectByIdemKey(@Param("userId") Long userId, @Param("idemKey") String idemKey);

    /** 回调抢态入账：PENDING→PAID + 落渠道单号/付款账号/支付时间。返回 1=本次入账；0=已被处理。 */
    @Update("UPDATE payment_order SET status = 'PAID', paid_at = NOW(), payer_account = #{payerAccount} "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markPaidIfPending(@Param("id") Long id, @Param("payerAccount") String payerAccount);

    /** 失败回调抢态：PENDING→FAILED（金额不符/渠道明确失败）。返回 0=已是终态。 */
    @Update("UPDATE payment_order SET status = 'FAILED' WHERE id = #{id} AND status = 'PENDING'")
    int markFailedIfPending(@Param("id") Long id);

    /** 用户取消：本人 PENDING→CLOSED。返回 0=非本人/已终态（调用方按现状回 404/409）。 */
    @Update("UPDATE payment_order SET status = 'CLOSED' WHERE id = #{id} AND user_id = #{userId} AND status = 'PENDING'")
    int cancelIfPending(@Param("id") Long id, @Param("userId") Long userId);

    /** 过期关单（job 逐行条件 UPDATE，与回调抢态互斥）。 */
    @Update("UPDATE payment_order SET status = 'CLOSED' WHERE id = #{id} AND status = 'PENDING'")
    int closeIfPending(@Param("id") Long id);

    /** 过期 PENDING 批扫（走 idx_payment_pending_expire 部分索引；单批限量防长事务）。 */
    @Select("SELECT id FROM payment_order WHERE status = 'PENDING' AND expire_at < NOW() ORDER BY id LIMIT #{limit}")
    List<Long> selectExpiredPendingIds(@Param("limit") int limit);

    /** 对账异常①：PENDING 超过 thresholdMinutes 仍未关（job 停滞/时钟异常线索）。 */
    @Select("SELECT COUNT(*) FROM payment_order WHERE status = 'PENDING' "
            + "AND created_at < NOW() - (#{thresholdMinutes} || ' minutes')::INTERVAL")
    long countStalePending(@Param("thresholdMinutes") int thresholdMinutes);

    /** 7x#1 累计条：用户 Σ已付金额。 */
    @Select("SELECT COALESCE(SUM(amount_yuan), 0) FROM payment_order WHERE user_id = #{userId} AND status = 'PAID'")
    BigDecimal sumPaidAmountByUser(@Param("userId") Long userId);

    /** 7x#1 累计条：用户 Σ已付积分。 */
    @Select("SELECT COALESCE(SUM(points_granted), 0) FROM payment_order WHERE user_id = #{userId} AND status = 'PAID'")
    BigDecimal sumPaidPointsByUser(@Param("userId") Long userId);

    // ==================== 对账「渠道异常」三节（7x#3 运维入口，只读） ====================

    /** 异常①：PENDING 超阈值未关（过期 job 停滞线索；限 100 行）。 */
    @Select("SELECT id AS orderId, user_id AS userId, amount_yuan AS amountYuan, points_granted AS pointsGranted, "
            + "status, channel, created_at AS createdAt, paid_at AS paidAt FROM payment_order "
            + "WHERE status = 'PENDING' AND created_at < NOW() - (#{thresholdMinutes} || ' minutes')::INTERVAL "
            + "ORDER BY id LIMIT 100")
    List<com.superprogrammer.billing.dto.PaymentAnomalyRowVO> selectStalePending(@Param("thresholdMinutes") int thresholdMinutes);

    /** 异常②：PAID 但无 RECHARGE/ADMIN_GRANT 流水（入账半截=脏数据；限 100 行）。 */
    @Select("SELECT o.id AS orderId, o.user_id AS userId, o.amount_yuan AS amountYuan, "
            + "o.points_granted AS pointsGranted, o.status, o.channel, o.created_at AS createdAt, o.paid_at AS paidAt "
            + "FROM payment_order o WHERE o.status = 'PAID' AND NOT EXISTS ("
            + "  SELECT 1 FROM points_ledger l WHERE l.ref_type = 'PAYMENT' AND l.ref_id = o.id "
            + "  AND l.type IN ('RECHARGE','ADMIN_GRANT')) ORDER BY o.id LIMIT 100")
    List<com.superprogrammer.billing.dto.PaymentAnomalyRowVO> selectPaidNoLedger();

    /** 异常③：终态单（CLOSED/FAILED）后渠道仍推真实付款（审计留痕反查；近 7 天，限 100 行）。 */
    @Select("SELECT o.id AS orderId, o.user_id AS userId, o.amount_yuan AS amountYuan, "
            + "o.points_granted AS pointsGranted, o.status, o.channel, o.created_at AS createdAt, o.paid_at AS paidAt "
            + "FROM payment_order o WHERE o.id IN ("
            + "  SELECT DISTINCT CAST(a.target_id AS BIGINT) FROM audit_logs a "
            + "  WHERE a.action = 'payment_notify_terminal_order' AND a.target_type = 'payment_order' "
            + "  AND a.created_at > NOW() - INTERVAL '7 days') ORDER BY o.id DESC LIMIT 100")
    List<com.superprogrammer.billing.dto.PaymentAnomalyRowVO> selectClosedButPaid();
}
