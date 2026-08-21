package com.superprogrammer.billing.service;

import com.superprogrammer.billing.dto.PaymentOrderVO;
import com.superprogrammer.billing.service.channel.MockPaymentChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自助支付全链 IT（真 PG，7x#3，V140）：下单→mock 回调入账→重推幂等→过期关单→
 * 关单后回调（已付已关单异常留痕）。mock 密钥服务端持有，走真实 handleNotify 链路。
 */
@SpringBootTest(properties = {
        "billing.payment.mock-enabled=true",
        "billing.payment.mock-secret=it-mock-secret"
})
@Tag("integration")
@ActiveProfiles("it")
class PaymentFlowIT {

    private static final long UID = 991400001L;

    @Autowired
    private PaymentOrderService paymentOrderService;
    @Autowired
    private MockPaymentChannel mockChannel;
    @Autowired
    private PointsWalletService walletService;
    @Autowired
    private BillingReconcileService reconcileService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("INSERT INTO users (id, username, password, name, status) OVERRIDING SYSTEM VALUE "
                + "VALUES (?, 'it_pay_user', 'x', 'IT支付', 'ACTIVE')", UID);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM audit_logs WHERE target_type='payment_order' AND target_id IN "
                + "(SELECT CAST(id AS VARCHAR) FROM payment_order WHERE user_id=?)", UID);
        jdbc.update("DELETE FROM points_ledger WHERE user_id=?", UID);
        jdbc.update("DELETE FROM payment_order WHERE user_id=?", UID);
        jdbc.update("DELETE FROM user_points_balance WHERE user_id=?", UID);
        jdbc.update("DELETE FROM users WHERE id=?", UID);
    }

    @Test
    void 全链_下单到入账到记录六字段() {
        PaymentOrderVO order = paymentOrderService.createOrder(UID, new BigDecimal("10.00"), "MOCK", "it-idem-1");
        assertThat(order.status()).isEqualTo("PENDING");
        assertThat(order.pointsGranted()).isNotNull();
        assertThat(order.payToken()).isNotBlank();

        // mock 回调（服务端造签走真链路）
        var params = notifyParams(order.id(), true);
        assertThat(paymentOrderService.handleNotify("MOCK", params)).isTrue();

        BigDecimal balance = walletService.getBalance(UID);
        assertThat(balance).isEqualByComparingTo(order.pointsGranted());
        // 流水 RECHARGE ref=PAYMENT/orderId 恰一行
        Integer ledgerCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM points_ledger WHERE user_id=? AND type='RECHARGE' AND ref_type='PAYMENT' AND ref_id=?",
                Integer.class, UID, order.id());
        assertThat(ledgerCnt).isEqualTo(1);
        // 充值记录六字段：balanceAfter JOIN 到位
        var page = paymentOrderService.myRecharges(UID, 1, 10);
        assertThat(page.page().getRecords()).hasSize(1);
        var row = page.page().getRecords().get(0);
        assertThat(row.balanceAfter()).isEqualByComparingTo(order.pointsGranted());
        assertThat(row.payerAccount()).isEqualTo("it-payer@mock");
        assertThat(row.status()).isEqualTo("PAID");
        assertThat(page.totalPaidPoints()).isEqualByComparingTo(order.pointsGranted());
    }

    @Test
    void 回调重推三次_只入一次() {
        PaymentOrderVO order = paymentOrderService.createOrder(UID, new BigDecimal("5.00"), "MOCK", null);
        var params = notifyParams(order.id(), true);
        assertThat(paymentOrderService.handleNotify("MOCK", params)).isTrue();
        assertThat(paymentOrderService.handleNotify("MOCK", params)).isTrue();
        assertThat(paymentOrderService.handleNotify("MOCK", params)).isTrue();

        Integer ledgerCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM points_ledger WHERE user_id=? AND type='RECHARGE' AND ref_type='PAYMENT' AND ref_id=?",
                Integer.class, UID, order.id());
        assertThat(ledgerCnt).isEqualTo(1);
        assertThat(walletService.getBalance(UID)).isEqualByComparingTo(order.pointsGranted());
    }

    @Test
    void 同idemKey重下单_返原单() {
        PaymentOrderVO a = paymentOrderService.createOrder(UID, new BigDecimal("10.00"), "MOCK", "it-idem-2");
        PaymentOrderVO b = paymentOrderService.createOrder(UID, new BigDecimal("10.00"), "MOCK", "it-idem-2");
        assertThat(b.id()).isEqualTo(a.id());
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_order WHERE user_id=? AND idem_key='it-idem-2'", Integer.class, UID);
        assertThat(cnt).isEqualTo(1);
    }

    @Test
    void 过期关单_与关单后回调不入账且留痕() throws Exception {
        PaymentOrderVO order = paymentOrderService.createOrder(UID, new BigDecimal("10.00"), "MOCK", null);
        // 手工把过期时间拨到过去，跑过期批
        jdbc.update("UPDATE payment_order SET expire_at = NOW() - INTERVAL '1 minute' WHERE id=?", order.id());
        int closed = paymentOrderService.expireBatch(50);
        assertThat(closed).isEqualTo(1);
        String status = jdbc.queryForObject("SELECT status FROM payment_order WHERE id=?", String.class, order.id());
        assertThat(status).isEqualTo("CLOSED");

        // 用户最后一刻已付款：渠道回调才到 → 不入账 + 终态留痕
        assertThat(paymentOrderService.handleNotify("MOCK", notifyParams(order.id(), true))).isTrue();
        assertThat(walletService.getBalance(UID)).isEqualByComparingTo("0");
        status = jdbc.queryForObject("SELECT status FROM payment_order WHERE id=?", String.class, order.id());
        assertThat(status).isEqualTo("CLOSED"); // 不被回调翻活

        // 审计异步入库：轮询等对账异常节出现该单（已付已关单）
        boolean seen = false;
        for (int i = 0; i < 20 && !seen; i++) {
            Thread.sleep(500);
            seen = reconcileService.paymentAnomalies().closedButPaid().stream()
                    .anyMatch(r -> r.orderId().equals(order.id()));
        }
        assertThat(seen).as("closedButPaid 对账异常应出现该单").isTrue();
    }

    @Test
    void 取消后_mock再付_走异常不入账() {
        PaymentOrderVO order = paymentOrderService.createOrder(UID, new BigDecimal("10.00"), "MOCK", null);
        paymentOrderService.cancel(UID, order.id());
        assertThat(paymentOrderService.handleNotify("MOCK", notifyParams(order.id(), true))).isTrue();
        assertThat(walletService.getBalance(UID)).isEqualByComparingTo("0");
    }

    private Map<String, String> notifyParams(long orderId, boolean success) {
        var probe = new com.superprogrammer.billing.entity.PaymentOrderEntity();
        probe.setId(orderId);
        probe.setUserId(UID);
        var amt = jdbc.queryForObject("SELECT amount_yuan FROM payment_order WHERE id=?", BigDecimal.class, orderId);
        var exp = jdbc.queryForObject("SELECT expire_at FROM payment_order WHERE id=?",
                java.time.OffsetDateTime.class, orderId);
        probe.setAmountYuan(amt);
        probe.setExpireAt(exp);
        return mockChannel.buildSignedNotify(probe, success, "it-payer@mock");
    }
}
