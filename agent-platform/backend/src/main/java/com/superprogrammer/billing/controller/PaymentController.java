package com.superprogrammer.billing.controller;

import com.superprogrammer.billing.dto.PaymentOrderVO;
import com.superprogrammer.billing.dto.RechargePageVO;
import com.superprogrammer.billing.entity.PaymentOrderEntity;
import com.superprogrammer.billing.service.PaymentOrderService;
import com.superprogrammer.billing.service.channel.MockPaymentChannel;
import com.superprogrammer.billing.service.channel.PaymentChannelRouter;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.ratelimit.RateLimit;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 自助充值支付·用户端点（7x#3，V140）。
 *
 * <pre>
 * POST /api/billing/payment/orders            下单（限流 10/60s/用户；idemKey 防双击）
 * GET  /api/billing/payment/orders/{id}       查单（本人；PENDING 补发 payToken 续付）
 * POST /api/billing/payment/orders/{id}/cancel 取消（PENDING→CLOSED；已付 409）
 * GET  /api/billing/payment/channels          当前可用渠道（空=前端隐藏充值按钮）
 * GET  /api/billing/payment/me/recharges      我的充值记录（六字段分页+累计条）
 * POST /api/billing/payment/mock/trigger      mock 收银台模拟支付（仅 mock-enabled；走同一 notify 链路）
 * </pre>
 */
@RestController
@RequestMapping("/api/billing/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentOrderService paymentOrderService;
    private final PaymentChannelRouter channelRouter;
    private final MockPaymentChannel mockPaymentChannel;

    @org.springframework.beans.factory.annotation.Value("${billing.payment.mock-enabled:false}")
    private boolean mockEnabled;

    @PostMapping("/orders")
    @RateLimit(action = "payment_order", max = 10, windowSeconds = 60)
    public ResponseEntity<R<PaymentOrderVO>> create(@Valid @RequestBody CreateOrderRequest req) {
        return ResponseEntity.ok(R.ok("订单已创建", paymentOrderService.createOrder(
                currentUserId(), req.getAmountYuan(), req.getChannel(), req.getIdemKey())));
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<R<PaymentOrderVO>> get(@PathVariable("id") Long id) {
        return ResponseEntity.ok(R.ok(paymentOrderService.getOrder(currentUserId(), id)));
    }

    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<R<Void>> cancel(@PathVariable("id") Long id) {
        paymentOrderService.cancel(currentUserId(), id);
        return ResponseEntity.ok(R.ok("订单已取消", null));
    }

    @GetMapping("/channels")
    public ResponseEntity<R<List<String>>> channels() {
        return ResponseEntity.ok(R.ok(channelRouter.availableChannels()));
    }

    @GetMapping("/me/recharges")
    public ResponseEntity<R<RechargePageVO>> myRecharges(@RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(R.ok(paymentOrderService.myRecharges(currentUserId(), page, size)));
    }

    /**
     * mock 收银台触发（仅 mock-enabled；否则 404 防生产暴露面）。
     * 服务端用 mock 密钥构造已签名回调参数，走与真实渠道完全相同的 handleNotify 链路——
     * 测的就是真链路；密钥永不出服务端。仅限本人 PENDING 的 MOCK 单。
     */
    @PostMapping("/mock/trigger")
    @AuditLog(module = "billing", action = "mock_trigger", targetType = "payment_order")
    @RateLimit(action = "payment_mock_trigger", max = 20, windowSeconds = 60)
    public ResponseEntity<R<Map<String, Object>>> mockTrigger(@Valid @RequestBody MockTriggerRequest req) {
        if (!mockEnabled) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "接口不存在");
        }
        PaymentOrderVO order = paymentOrderService.getOrder(currentUserId(), req.getOrderId());
        if (!PaymentOrderEntity.CHANNEL_MOCK.equals(order.channel())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅 MOCK 渠道订单可模拟支付");
        }
        if (!PaymentOrderEntity.STATUS_PENDING.equals(order.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "订单已是终态，不可模拟支付");
        }
        // 复用查单 VO 重建最小实体供造签（字段足够：id/amount/expire）
        PaymentOrderEntity probe = new PaymentOrderEntity();
        probe.setId(order.id());
        probe.setUserId(currentUserId());
        probe.setAmountYuan(order.amountYuan());
        probe.setExpireAt(order.expireAt());
        boolean ok = paymentOrderService.handleNotify(PaymentOrderEntity.CHANNEL_MOCK,
                mockPaymentChannel.buildSignedNotify(probe, Boolean.TRUE.equals(req.getSuccess()),
                        req.getPayerAccount()));
        return ResponseEntity.ok(R.ok(ok ? "模拟支付成功" : "模拟支付被拒",
                Map.of("orderId", order.id(), "accepted", ok)));
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getPrincipal() == null ? null : (Long) auth.getPrincipal();
    }

    @Data
    public static class CreateOrderRequest {
        @NotNull(message = "金额必填")
        @DecimalMin(value = "0.01", message = "单笔最低 ¥0.01")
        @DecimalMax(value = "99999.99", message = "单笔最高 ¥99999.99")
        private BigDecimal amountYuan;
        @NotBlank(message = "支付渠道必填")
        private String channel;
        /** 前端幂等键（UUID/表单会话）；同键同金额返原单，不同金额 409。 */
        private String idemKey;
    }

    @Data
    public static class MockTriggerRequest {
        @NotNull(message = "orderId 必填")
        private Long orderId;
        /** true=模拟支付成功；false=模拟失败（PENDING→FAILED）。 */
        @NotNull(message = "success 必填")
        private Boolean success;
        /** 可选模拟付款账号（默认 mock-user@wallet）。 */
        private String payerAccount;
    }
}
