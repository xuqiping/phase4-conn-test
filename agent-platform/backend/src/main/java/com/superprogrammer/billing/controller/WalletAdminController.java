package com.superprogrammer.billing.controller;

import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.billing.dto.RechargeRequest;
import com.superprogrammer.billing.entity.PaymentOrderEntity;
import com.superprogrammer.billing.service.PointsWalletService;
import com.superprogrammer.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * admin 钱包端点（Chunk H Step 15）：充值/发放积分。
 * <p>权限 {@code points:recharge}（仅 admin 默认有）。
 * <p>复用 {@link PointsWalletService#grant}：建 payment_order(PAID, channel=ADMIN) + 余额涨 + 流水(ADMIN_GRANT)，
 * 三者同事务。admin 显式充值不看 {@code billing.enabled}（运维核心能力恒开）。
 * <p>Phase2 支付回调（moneyYuan → 阶梯折算 → points）另起 PaymentCallbackController，本端点 MVP 只接「直接填积分」。
 */
@Slf4j
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class WalletAdminController {

    private final PointsWalletService walletService;

    /**
     * 充值/发放积分。
     *
     * @return data 含 userId + balanceAfter（充值后余额）
     */
    @PostMapping("/recharge")
    @RequirePermission("points:recharge")
    @AuditLog(module = "billing", action = "admin_recharge", targetType = "wallet")
    public ResponseEntity<R<Map<String, Object>>> recharge(@Valid @RequestBody RechargeRequest req) {
        BigDecimal after = walletService.grant(
                req.getUserId(),
                req.getPoints(),
                null, // MVP 纯发放，不挂金额
                PaymentOrderEntity.CHANNEL_ADMIN,
                null);
        log.info("admin 充值 userId={} points={} balanceAfter={}", req.getUserId(), req.getPoints(), after);
        return ResponseEntity.ok(R.ok("充值成功",
                Map.of("userId", req.getUserId(), "balanceAfter", after)));
    }
}
