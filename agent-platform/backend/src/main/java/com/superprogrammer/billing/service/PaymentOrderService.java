package com.superprogrammer.billing.service;

import com.superprogrammer.billing.dto.PaymentOrderVO;
import com.superprogrammer.billing.entity.PaymentOrderEntity;
import com.superprogrammer.billing.mapper.PaymentOrderMapper;
import com.superprogrammer.billing.service.channel.PaymentChannelRouter;
import com.superprogrammer.billing.service.channel.PaymentChannelService;
import com.superprogrammer.billing.service.channel.PaymentNotifyData;
import com.superprogrammer.billing.service.channel.PaymentPrecreateResult;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.security.SecurityEventPublisher;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 自助充值支付编排（7x#3，V140）：下单 → 渠道回调验签入账 → 取消/过期关单。
 *
 * <p><b>状态机</b>：PENDING →PAID（回调抢态）/ FAILED（失败回调/金额不符）/ CLOSED（取消/过期）。
 * 全部条件 UPDATE 抢态，0 行=已被处理——回调重推、过期与支付竞态天然互斥。
 *
 * <p><b>入账三道幂等</b>（坑表①）：uk_payment_channel_order 撞索引 → markPaidIfPending 抢态 →
 * uq_ledger_ref(PAYMENT, orderId, RECHARGE) 兜底。入账三写（订单 PAID + 余额 + 流水）同事务。
 *
 * <p><b>积分快照</b>：下单时按当时阶梯比例折算 points_granted 落单，回调只吃快照——
 * 档位中途变更不影响在途订单（坑表④）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentOrderService {

    private final PaymentOrderMapper orderMapper;
    private final PaymentChannelRouter channelRouter;
    private final PointsRatioService ratioService;
    private final PointsWalletService walletService;
    private final AuditLogService auditLogService;

    /** 可选横切（同 PointsWalletService 惯例：@InjectMocks 单测无此 Bean 时 null 跳过）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SecurityEventPublisher securityEventPublisher;

    @Value("${billing.payment.order-expire-minutes:30}")
    private int orderExpireMinutes;

    @Value("${billing.payment.notify-url:}")
    private String notifyUrl;

    /** 充值金额合法区间（¥）。 */
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999.99");

    // ==================== 下单 ====================

    /**
     * 创建充值订单：金额校验 → idemKey 查重（同键同金额返原单/不同金额 409+审计）→
     * 比例快照 → PENDING 落库（expire_at=+N min）→ 渠道 precreate。
     */
    @Transactional(rollbackFor = Exception.class)
    public PaymentOrderVO createOrder(Long userId, BigDecimal amountYuan, String channel, String idemKey) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        if (amountYuan == null || amountYuan.compareTo(MIN_AMOUNT) < 0 || amountYuan.compareTo(MAX_AMOUNT) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "充值金额须在 ¥" + MIN_AMOUNT + " ~ ¥" + MAX_AMOUNT + " 之间");
        }
        PaymentChannelService channelService = channelRouter.routeForCreate(channel);

        if (idemKey != null && !idemKey.isBlank()) {
            PaymentOrderEntity existing = orderMapper.selectByIdemKey(userId, idemKey);
            if (existing != null) {
                if (existing.getAmountYuan().compareTo(amountYuan) != 0) {
                    audit(userId, "payment_idem_conflict", String.valueOf(existing.getId()),
                            "{\"idemAmount\":" + existing.getAmountYuan() + ",\"reqAmount\":" + amountYuan + "}", "FAIL");
                    throw new BusinessException(ErrorCode.CONFLICT, "重复提交金额不一致，请刷新后重试");
                }
                log.info("下单幂等撞键返原单: userId={} idemKey={} orderId={}", userId, idemKey, existing.getId());
                return toVo(existing, existing.getStatus().equals(PaymentOrderEntity.STATUS_PENDING)
                        ? precreateQuietly(channelService, existing) : null);
            }
        }

        PaymentOrderEntity order = new PaymentOrderEntity();
        order.setUserId(userId);
        order.setAmountYuan(amountYuan);
        // 快照：回调入账只吃这个值，档位变更不回溯
        order.setPointsGranted(ratioService.toPoints(amountYuan));
        order.setStatus(PaymentOrderEntity.STATUS_PENDING);
        order.setChannel(channelService.channel());
        order.setExpireAt(OffsetDateTime.now().plusMinutes(orderExpireMinutes));
        order.setIdemKey(idemKey != null && !idemKey.isBlank() ? idemKey : null);
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 并发同 idemKey：索引兜底，返原单（竞态输家）
            PaymentOrderEntity existing = idemKey != null ? orderMapper.selectByIdemKey(userId, idemKey) : null;
            if (existing != null && existing.getAmountYuan().compareTo(amountYuan) == 0) {
                return toVo(existing, precreateQuietly(channelService, existing));
            }
            throw new BusinessException(ErrorCode.CONFLICT, "重复提交，请刷新后重试");
        }
        PaymentPrecreateResult precreate = channelService.precreate(order, notifyUrl);
        audit(userId, "payment_order_create", String.valueOf(order.getId()),
                "{\"amount\":" + amountYuan + ",\"points\":" + order.getPointsGranted()
                        + ",\"channel\":\"" + order.getChannel() + "\"}", "SUCCESS");
        log.info("充值下单: orderId={} userId={} amount={} points={} channel={}",
                order.getId(), userId, amountYuan, order.getPointsGranted(), order.getChannel());
        return toVo(order, precreate);
    }

    /** 查单（本人；他人单 404 不泄露存在性）。PENDING 单补发 payToken 供断线续付。 */
    public PaymentOrderVO getOrder(Long userId, Long orderId) {
        PaymentOrderEntity order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        PaymentPrecreateResult precreate = null;
        if (PaymentOrderEntity.STATUS_PENDING.equals(order.getStatus())) {
            precreate = precreateQuietly(channelRouter.route(order.getChannel()), order);
        }
        return toVo(order, precreate);
    }

    /** 用户取消：PENDING→CLOSED 抢态；已 PAID 409；非本人/不存在 404。 */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, Long orderId) {
        if (orderMapper.cancelIfPending(orderId, userId) == 0) {
            PaymentOrderEntity order = orderMapper.selectById(orderId);
            if (order == null || !order.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
            }
            throw new BusinessException(ErrorCode.CONFLICT,
                    PaymentOrderEntity.STATUS_PAID.equals(order.getStatus()) ? "订单已支付，不可取消" : "订单已关闭");
        }
        audit(userId, "payment_order_cancel", String.valueOf(orderId), null, "SUCCESS");
        log.info("充值订单取消: orderId={} userId={}", orderId, userId);
    }

    // ==================== 回调入账 ====================

    /**
     * 渠道回调统一入口（notify 端点/mock trigger 共用同一链路——mock 测试的就是真链路）。
     * <p>验签（渠道实现）→ 查单 → 金额复核 → 抢态 PAID → 同事务入账。
     * 返回 true=本次或此前已入账（ack 成功止重推）；false=业务拒收（验签/金额/未知单，已记安全事件）。
     * CLOSED/FAILED 单遇真实付款：不入账，ERROR+安全事件，返 true ack——对账异常列表捞它人工补单。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean handleNotify(String channel, Map<String, String> params) {
        PaymentNotifyData data;
        try {
            data = channelRouter.route(channel).verifyAndParse(params);
        } catch (BusinessException e) {
            securityEvent(channel, null, "验签/报文失败: " + e.getMessage());
            log.warn("支付回调验签失败: channel={} msg={}", channel, e.getMessage());
            return false;
        }
        PaymentOrderEntity order = orderMapper.selectByChannelOrder(channel, data.channelOrderId());
        if (order == null) {
            securityEvent(channel, null, "未知渠道订单: " + mask(data.channelOrderId()));
            log.error("支付回调未知订单: channel={} channelOrderId={}", channel, mask(data.channelOrderId()));
            return false;
        }
        // 金额复核（验签后仍比对——防渠道侧/中间篡改）
        if (data.amountYuan() == null || data.amountYuan().compareTo(order.getAmountYuan()) != 0) {
            orderMapper.markFailedIfPending(order.getId());
            securityEvent(channel, order.getUserId(),
                    "金额不符 order=" + order.getId() + " expect=" + order.getAmountYuan() + " got=" + data.amountYuan());
            log.error("支付回调金额不符: orderId={} expect={} got={}", order.getId(), order.getAmountYuan(), data.amountYuan());
            audit(order.getUserId(), "payment_notify_amount_mismatch", String.valueOf(order.getId()),
                    "{\"expect\":" + order.getAmountYuan() + ",\"got\":" + data.amountYuan() + "}", "FAIL");
            return false;
        }
        if (!data.success()) {
            if (orderMapper.markFailedIfPending(order.getId()) > 0) {
                log.info("支付失败回调关单: orderId={}", order.getId());
                audit(order.getUserId(), "payment_order_failed", String.valueOf(order.getId()), null, "SUCCESS");
            }
            return true;
        }
        if (orderMapper.markPaidIfPending(order.getId(), data.payerAccount()) == 0) {
            // 抢态失败=已是终态
            if (PaymentOrderEntity.STATUS_PAID.equals(order.getStatus())) {
                log.info("支付回调重推幂等: orderId={}", order.getId());
                return true;
            }
            // CLOSED/FAILED 遇真实付款：不自动入账，安全事件+对账异常人工补单
            securityEvent(channel, order.getUserId(),
                    "已付但订单已" + order.getStatus() + " order=" + order.getId());
            log.error("支付回调遇终态单（人工补单线索）: orderId={} status={} channelOrderId={}",
                    order.getId(), order.getStatus(), mask(data.channelOrderId()));
            audit(order.getUserId(), "payment_notify_terminal_order", String.valueOf(order.getId()),
                    "{\"status\":\"" + order.getStatus() + "\"}", "FAIL");
            return true;
        }
        // 同事务入账（uq_ledger_ref 兜底：极小竞态下重复入账撞唯一索引→按已入账处理）
        try {
            walletService.creditRechargeForOrder(order.getUserId(), order.getPointsGranted(),
                    order.getAmountYuan(), order.getId(), "自助充值（" + channel + "）");
        } catch (DuplicateKeyException e) {
            log.warn("支付入账撞 uq_ledger_ref 按已入账处理: orderId={}", order.getId());
        }
        audit(order.getUserId(), "payment_order_paid", String.valueOf(order.getId()),
                "{\"points\":" + order.getPointsGranted() + "}", "SUCCESS");
        log.info("充值入账: orderId={} userId={} points={}", order.getId(), order.getUserId(), order.getPointsGranted());
        return true;
    }

    // ==================== 过期关单 ====================

    /** 过期 job 单批关单（逐行条件 UPDATE，与回调抢态互斥；返回关闭数）。 */
    @Transactional(rollbackFor = Exception.class)
    public int expireBatch(int limit) {
        int closed = 0;
        for (Long id : orderMapper.selectExpiredPendingIds(limit)) {
            if (orderMapper.closeIfPending(id) > 0) {
                closed++;
            }
        }
        if (closed > 0) {
            log.info("过期充值订单批量关闭: {} 单", closed);
        }
        return closed;
    }

    // ==================== 内部 ====================

    private PaymentPrecreateResult precreateQuietly(PaymentChannelService channelService, PaymentOrderEntity order) {
        try {
            return channelService.precreate(order, notifyUrl);
        } catch (Exception e) {
            log.warn("precreate 重发失败（查单/撞键补发场景，容忍）: orderId={} msg={}", order.getId(), e.getMessage());
            return null;
        }
    }

    private PaymentOrderVO toVo(PaymentOrderEntity o, PaymentPrecreateResult precreate) {
        return new PaymentOrderVO(o.getId(), o.getCreatedAt(), o.getAmountYuan(), o.getPointsGranted(),
                o.getStatus(), o.getChannel(), o.getPayerAccount(), o.getExpireAt(), o.getPaidAt(),
                precreate != null ? precreate.payToken() : null);
    }

    private void securityEvent(String channel, Long userId, String reason) {
        if (securityEventPublisher != null) {
            securityEventPublisher.publish(ApplicationSecurityEvent.KIND_PAYMENT_NOTIFY_REJECT,
                    userId, Map.of("channel", channel, "reason", reason));
        }
    }

    /** 渠道单号日志掩码（保留头尾各 4 位）。 */
    static String mask(String channelOrderId) {
        if (channelOrderId == null || channelOrderId.length() <= 8) {
            return "***";
        }
        return channelOrderId.substring(0, 4) + "***" + channelOrderId.substring(channelOrderId.length() - 4);
    }

    private void audit(Long userId, String action, String targetId, String detail, String result) {
        try {
            var row = auditLogService.fromMdc("billing", action, "payment_order", targetId, detail, result);
            row.setUserId(userId);
            auditLogService.record(row);
        } catch (Exception e) {
            log.warn("支付审计落库失败(已吞): {}", e.toString());
        }
    }
}
