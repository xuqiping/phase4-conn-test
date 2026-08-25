package com.superprogrammer.billing.controller;

import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.auth.security.RequirePermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.billing.dto.RechargeRequest;
import com.superprogrammer.billing.dto.RechargeUserOptionVO;
import com.superprogrammer.billing.entity.PaymentOrderEntity;
import com.superprogrammer.billing.service.PointsWalletService;
import com.superprogrammer.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
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
    private final UserMapper userMapper;

    /**
     * 充值/发放积分。
     *
     * @return data 含 userId + balanceAfter（充值后余额）
     */
    @PostMapping("/recharge")
    @RequirePermission("points:recharge")
    @AuditLog(module = "billing", action = "admin_recharge", targetType = "wallet")
    public ResponseEntity<R<Map<String, Object>>> recharge(@Valid @RequestBody RechargeRequest req) {
        BigDecimal after = walletService.grantIdempotent(
                req.getUserId(),
                req.getPoints(),
                null, // MVP 纯发放，不挂金额
                PaymentOrderEntity.CHANNEL_ADMIN,
                null,
                req.getIdempotencyKey(), // SEC-FR-121：可空，空则普通充值
                req.getRemark()); // 备注落 ledger.remark（可空走默认文案）
        log.info("admin 充值 userId={} points={} balanceAfter={}", req.getUserId(), req.getPoints(), after);
        return ResponseEntity.ok(R.ok("充值成功",
                Map.of("userId", req.getUserId(), "balanceAfter", after)));
    }

    /**
     * 7x：充值页用户下拉选项（账号 + 昵称/姓名）。
     * <p>只列 ACTIVE 用户；keyword 模糊匹配 username/name；上限 20 条（下拉远端搜索口径）。
     * 不复用 {@code GET /api/users}（要 user:manage）——持 points:recharge 的财务角色未必有用户管理权。
     */
    @GetMapping("/admin/user-options")
    @RequirePermission("points:recharge")
    public ResponseEntity<R<List<RechargeUserOptionVO>>> userOptions(
            @RequestParam(required = false) String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        LambdaQueryWrapper<User> qw = new LambdaQueryWrapper<User>()
                .eq(User::getStatus, "ACTIVE")
                .select(User::getId, User::getUsername, User::getName)
                .orderByAsc(User::getId)
                .last("LIMIT 20");
        if (!kw.isEmpty()) {
            qw.and(w -> w.like(User::getUsername, kw).or().like(User::getName, kw));
        }
        List<RechargeUserOptionVO> options = userMapper.selectList(qw).stream()
                .map(u -> new RechargeUserOptionVO(u.getId(), u.getUsername(), u.getName()))
                .toList();
        return ResponseEntity.ok(R.ok(options));
    }
}
