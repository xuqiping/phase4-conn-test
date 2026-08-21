package com.superprogrammer.billing.controller;

import com.superprogrammer.billing.service.PaymentOrderService;
import com.superprogrammer.common.ratelimit.RateLimit;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 支付渠道回调接收端点（7x#3）：匿名放行（SecurityConfig 精确 permitAll /notify/**），
 * 安全靠渠道验签（verifyAndParse 第一道），与登录态无关。
 *
 * <p>应答约定：handleNotify true → 200「success」（渠道止重推）；false → 400（验签/金额/未知单——
 * 已记安全事件，渠道会按策略重推，幂等链路兜底）。真实渠道接入时按其应答格式适配（支付宝 "success" 纯文本等）。
 */
@RestController
@RequestMapping("/api/billing/payment/notify")
@RequiredArgsConstructor
public class PaymentNotifyController {

    private final PaymentOrderService paymentOrderService;

    @PostMapping("/{channel}")
    @RateLimit(action = "payment_notify", max = 120, windowSeconds = 60, scope = RateLimit.RateLimitScope.IP)
    public ResponseEntity<R<String>> notify(@PathVariable("channel") String channel,
                                            @RequestBody(required = false) Map<String, String> params) {
        boolean ok = paymentOrderService.handleNotify(channel.toUpperCase(),
                params != null ? params : Map.of());
        if (!ok) {
            return ResponseEntity.badRequest().body(R.fail(400, "回调处理失败"));
        }
        return ResponseEntity.ok(R.ok("success", "success"));
    }
}
