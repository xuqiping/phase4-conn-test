package com.superprogrammer.billing.service;

import com.superprogrammer.billing.entity.PaymentOrderEntity;
import com.superprogrammer.billing.entity.PointsLedgerEntity;
import com.superprogrammer.billing.entity.UserPointsBalanceEntity;
import com.superprogrammer.billing.mapper.PaymentOrderMapper;
import com.superprogrammer.billing.mapper.PointsLedgerMapper;
import com.superprogrammer.billing.mapper.UserPointsBalanceMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 积分钱包服务（计费核心）：预检 / 扣 / 退 / 充。
 * <p>并发安全（spec §4 / plan §坑点 ②）：扣/退/充走 {@link UserPointsBalanceMapper#adjustBalanceReturn}
 * 的 <code>UPDATE ... RETURNING</code> 单语句——调余额同时返回新余额，行锁串行化同用户的并发扣减，
 * 杜绝「先查后扣」的超支窗口。整方法 {@code @Transactional}：余额行 + 流水同生共死。
 * <p>两写路径分离（spec §5 决策 1）：本服务=同步扣减（不可丢，调用线程）；采集=异步（UsageCollector，另池）。
 * <p>开关：{@code billing.enabled=false} 时 charge/refund/requireAffordable 全短路（出问题关掉即停）；
 * grant（admin 显式充值）不看 enabled——运维核心能力恒开。
 * <p>userId=null（系统调用）→ 跳预检/扣/退（仅采不扣，spec §3 B6 边界）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsWalletService {

    private final UserPointsBalanceMapper balanceMapper;
    private final PointsLedgerMapper ledgerMapper;
    private final PaymentOrderMapper paymentOrderMapper;

    /** 计费总闸。关则跳预检+扣减+退款，仅留采集。 */
    @Value("${billing.enabled:true}")
    private boolean enabled;

    /** 失败是否退款，默认 true。 */
    @Value("${billing.refund-on-fail:true}")
    private boolean refundOnFail;

    /**
     * 预检：余额&gt;0 放行，≤0（含负数欠款）抛 {@link ErrorCode#INSUFFICIENT_POINTS}。
     * <p>enabled=false 或 userId=null（系统调用）跳过。
     */
    public void requireAffordable(Long userId) {
        if (!enabled || userId == null) {
            return;
        }
        UserPointsBalanceEntity b = balanceMapper.selectByUserId(userId);
        if (b == null || b.getBalancePoints() == null || b.getBalancePoints().signum() <= 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_POINTS);
        }
    }

    /**
     * 同步扣减 + 流水。enabled=false / userId=null / points≤0 → 短路返 null（不扣）。
     * <p>单事务：{@code insertIfAbsent}（幂等建行）→ {@code adjustBalanceReturn}（UPDATE...RETURNING 行锁扣）
     * → INSERT 流水(CONSUME, balance_after=返回值)。返回扣后余额。
     *
     * @param points 正数（扣减量）
     * @return 扣后余额；短路时 null
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal charge(Long userId, BigDecimal points, String refType, Long refId, String remark) {
        if (!enabled || userId == null || points == null || points.signum() <= 0) {
            return null;
        }
        return adjust(userId, points.negate(), PointsLedgerEntity.TYPE_CONSUME, null, refType, refId, remark, "积分扣减");
    }

    /**
     * 退款（+逆向回涨）+ 流水(REFUND)。
     * <p>调用失败/异常后调；本就没扣到（usage 空）的边界由调用方判，不重复退。
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal refund(Long userId, BigDecimal points, String refType, Long refId, String remark) {
        if (!enabled || userId == null || points == null || points.signum() <= 0) {
            return null;
        }
        return adjust(userId, points, PointsLedgerEntity.TYPE_REFUND, null, refType, refId, remark, "积分退款");
    }

    /**
     * 充值（admin grant MVP / Phase2 支付回调 PAID）。
     * <p>建 payment_order(PAID) + 余额涨 + 流水。不看 billing.enabled（运维核心能力恒开）。
     *
     * @param points       到账积分（阶梯折算后）
     * @param moneyYuan    充值金额（¥，可空=纯发放）
     * @param channel      ADMIN/ALIPAY/WECHAT；null 默认 ADMIN
     * @param channelOrderId 支付渠道订单号（Phase2 幂等）；admin grant 可空
     * @return 充值后余额
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal grant(Long userId, BigDecimal points, BigDecimal moneyYuan,
                            String channel, String channelOrderId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "充值目标用户不能为空");
        }
        if (points == null || points.signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "充值积分必须大于0");
        }
        balanceMapper.insertIfAbsent(userId);

        PaymentOrderEntity order = new PaymentOrderEntity();
        order.setUserId(userId);
        // amount_yuan 列 NOT NULL（V65）：admin 纯发放（moneyYuan=null）合法记 ¥0，避免违反非空约束
        order.setAmountYuan(moneyYuan != null ? moneyYuan : BigDecimal.ZERO);
        order.setPointsGranted(points);
        order.setStatus(PaymentOrderEntity.STATUS_PAID);
        order.setChannel(channel != null ? channel : PaymentOrderEntity.CHANNEL_ADMIN);
        order.setChannelOrderId(channelOrderId);
        order.setPaidAt(OffsetDateTime.now());
        paymentOrderMapper.insert(order);

        String type = PaymentOrderEntity.CHANNEL_ADMIN.equals(order.getChannel())
                ? PointsLedgerEntity.TYPE_ADMIN_GRANT
                : PointsLedgerEntity.TYPE_RECHARGE;
        return adjust(userId, points, type, moneyYuan, PointsLedgerEntity.REF_PAYMENT,
                order.getId(), "充值", "积分充值");
    }

    /** 查余额（用户钱包页）。无行返 0。 */
    public BigDecimal getBalance(Long userId) {
        if (userId == null) {
            return BigDecimal.ZERO;
        }
        UserPointsBalanceEntity b = balanceMapper.selectByUserId(userId);
        return b != null && b.getBalancePoints() != null ? b.getBalancePoints() : BigDecimal.ZERO;
    }

    public boolean isRefundOnFail() {
        return refundOnFail;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 余额调整内部统一路径：幂等建行 → UPDATE...RETURNING 行锁调 → 流水落 balance_after。
     * <p>CONSUME delta 为负、REFUND/GRANT delta 为正，由调用方传 signedDelta。
     */
    private BigDecimal adjust(Long userId, BigDecimal signedDelta, String type, BigDecimal moneyYuan,
                              String refType, Long refId, String remark, String logLabel) {
        balanceMapper.insertIfAbsent(userId);
        BigDecimal after = balanceMapper.adjustBalanceReturn(userId, signedDelta);
        if (after == null) {
            if (signedDelta.signum() < 0) {
                // SEC-FR-120：SQL 守卫拦下透支（并发超扣/余额不足）→ 该笔拒扣。
                // 计费铁律（LlmBillingService/MediaBillingService 吞异常）保证不炸用户请求。
                throw new BusinessException(ErrorCode.INSUFFICIENT_POINTS);
            }
            // 正向调整返 null = 行不存在——理论 insertIfAbsent 已建；防御性抛错（不静默吞，防对账黑洞）
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "钱包余额行缺失 userId=" + userId);
        }
        PointsLedgerEntity ledger = new PointsLedgerEntity();
        ledger.setUserId(userId);
        ledger.setType(type);
        ledger.setDeltaPoints(signedDelta);
        ledger.setMoneyYuan(moneyYuan);
        ledger.setRefType(refType);
        ledger.setRefId(refId);
        ledger.setBalanceAfter(after);
        ledger.setRemark(remark);
        ledgerMapper.insert(ledger);
        log.info("{} userId={} delta={} balanceAfter={} ref={}:{}", logLabel, userId, signedDelta, after, refType, refId);
        return after;
    }
}
