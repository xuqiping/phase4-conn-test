package com.superprogrammer.billing.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import com.superprogrammer.billing.dto.PaymentChannelConfigVO;
import com.superprogrammer.billing.service.PaymentChannelConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 支付渠道网页配置（admin，7x 追加）。
 *
 * <pre>
 * GET /api/billing/admin/payment-channels           两渠道脱敏状态（tails，永不出明文）
 * PUT /api/billing/admin/payment-channels/{channel} 保存（merge：留空=保持原值；整体 AES 落库）
 * </pre>
 *
 * 权限 {@code payment:config}（V144 seed，admin 默认持有）；写操作 @AuditLog。
 */
@RestController
@RequestMapping("/api/billing/admin/payment-channels")
@RequiredArgsConstructor
public class PaymentChannelAdminController {

    private final PaymentChannelConfigService configService;

    @GetMapping
    @RequirePermission("payment:config")
    public ResponseEntity<R<List<PaymentChannelConfigVO>>> list() {
        return ResponseEntity.ok(R.ok(configService.listMasked()));
    }

    /**
     * 保存渠道配置。body 例：{@code {"appId":"2021...","privateKey":"...","alipayPublicKey":""}}
     * ——空串字段保持原值（merge）。审计只记动作，不记值。
     */
    @PutMapping("/{channel}")
    @RequirePermission("payment:config")
    @AuditLog(module = "billing", action = "payment_channel_config_save", targetType = "payment_channel_config")
    public ResponseEntity<R<Void>> save(@PathVariable("channel") String channel,
                                        @RequestBody Map<String, String> config) {
        configService.save(channel.toUpperCase(), config);
        return ResponseEntity.ok(R.ok("已保存（加密存储）", null));
    }
}
