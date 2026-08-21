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
 * admin 充值记录 + 用户余额视图 IT（真 PG，20x#1）：
 * 无钱包行用户显 0 / 无充值用户累计 0 / 合计卡与明细 Σ 一致 / 筛选联动聚合同口径 / 排序白名单。
 */
@SpringBootTest(properties = {
        "billing.payment.mock-enabled=true",
        "billing.payment.mock-secret=it-mock-secret"
})
@Tag("integration")
@ActiveProfiles("it")
class PaymentAdminQueryIT {

    private static final long UID_A = 991500001L;
    private static final long UID_B = 991500002L;

    @Autowired
    private PaymentOrderService paymentOrderService;
    @Autowired
    private MockPaymentChannel mockChannel;
    @Autowired
    private BillingQueryService queryService;
    @Autowired
    private com.superprogrammer.billing.mapper.PaymentOrderMapper orderMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("INSERT INTO users (id, username, password, name, status) OVERRIDING SYSTEM VALUE "
                + "VALUES (?, 'it_adm_alpha', 'x', 'IT甲', 'ACTIVE')", UID_A);
        jdbc.update("INSERT INTO users (id, username, password, name, status) OVERRIDING SYSTEM VALUE "
                + "VALUES (?, 'it_adm_beta', 'x', 'IT乙', 'ACTIVE')", UID_B);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        for (long uid : new long[]{UID_A, UID_B}) {
            jdbc.update("DELETE FROM audit_logs WHERE target_type='payment_order' AND target_id IN "
                    + "(SELECT CAST(id AS VARCHAR) FROM payment_order WHERE user_id=?)", uid);
            jdbc.update("DELETE FROM points_ledger WHERE user_id=?", uid);
            jdbc.update("DELETE FROM payment_order WHERE user_id=?", uid);
            jdbc.update("DELETE FROM user_points_balance WHERE user_id=?", uid);
            jdbc.update("DELETE FROM users WHERE id=?", uid);
        }
    }

    /** A 付一单（真链路：下单→mock 造签→handleNotify 入账）；返回订单 VO。 */
    private PaymentOrderVO payA(BigDecimal amount) {
        PaymentOrderVO order = paymentOrderService.createOrder(UID_A, amount, "MOCK", null);
        Map<String, String> params = mockChannel.buildSignedNotify(
                orderMapper.selectById(order.id()), true, "it-payer@mock");
        assertThat(paymentOrderService.handleNotify("MOCK", params)).isTrue();
        return order;
    }

    @Test
    void admin充值记录_筛选联动聚合同口径() {
        PaymentOrderVO paid = payA(new BigDecimal("10.00"));
        paymentOrderService.createOrder(UID_B, new BigDecimal("20.00"), "MOCK", null); // PENDING 不付

        // 无筛选：两行，Σ 只算 PAID（A 的 10 元）
        var all = queryService.adminRecharges(null, "it_adm_", null, null, null, null, 1, 20);
        assertThat(all.page().getTotal()).isEqualTo(2);
        assertThat(all.filteredPaidAmount()).isEqualByComparingTo("10.00");
        assertThat(all.filteredPaidPoints()).isEqualByComparingTo(paid.pointsGranted());
        // PAID 行六字段齐（含 username + balanceAfter）
        var paidRow = all.page().getRecords().stream()
                .filter(r -> r.id().equals(paid.id())).findFirst().orElseThrow();
        assertThat(paidRow.username()).isEqualTo("it_adm_alpha");
        assertThat(paidRow.balanceAfter()).isNotNull();
        assertThat(paidRow.payerAccount()).isEqualTo("it-payer@mock");

        // 筛 status=PENDING：只 B 的行，Σ 归零（口径随筛选联动）
        var pending = queryService.adminRecharges(null, "it_adm_", null, "PENDING", null, null, 1, 20);
        assertThat(pending.page().getTotal()).isEqualTo(1);
        assertThat(pending.page().getRecords().get(0).balanceAfter()).isNull();
        assertThat(pending.filteredPaidAmount()).isEqualByComparingTo("0");

        // keyword 精确到 A：只 A 的行
        var onlyA = queryService.adminRecharges(null, "it_adm_alpha", null, null, null, null, 1, 20);
        assertThat(onlyA.page().getTotal()).isEqualTo(1);
        assertThat(onlyA.filteredPaidAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void 用户余额视图_零值填充与合计一致() {
        PaymentOrderVO paid = payA(new BigDecimal("10.00"));
        // B：有钱包行无充值；再插一个无钱包行用户 C
        long uidC = 991500003L;
        jdbc.update("INSERT INTO users (id, username, password, name, status) OVERRIDING SYSTEM VALUE "
                + "VALUES (?, 'it_adm_gamma', 'x', 'IT丙', 'ACTIVE')", uidC);
        try {
            jdbc.update("INSERT INTO user_points_balance (user_id, balance_points, updated_at) "
                    + "VALUES (?, 5, NOW()) ON CONFLICT (user_id) DO NOTHING", UID_B);

            var vo = queryService.userBalances("it_adm_", "rechargeAmount", "desc", 1, 20);
            var rows = vo.page().getRecords();
            assertThat(vo.page().getTotal()).isEqualTo(3);
            // 排序：A（有充值）在首
            assertThat(rows.get(0).userId()).isEqualTo(UID_A);
            assertThat(rows.get(0).totalRechargeAmount()).isEqualByComparingTo("10.00");
            assertThat(rows.get(0).totalRechargePoints()).isEqualByComparingTo(paid.pointsGranted());
            assertThat(rows.get(0).lastRechargeAt()).isNotNull();
            // B：余额 5、累计 0、最近充值 null
            var bRow = rows.stream().filter(r -> r.userId().equals(UID_B)).findFirst().orElseThrow();
            assertThat(bRow.balancePoints()).isEqualByComparingTo("5");
            assertThat(bRow.totalRechargePoints()).isEqualByComparingTo("0");
            assertThat(bRow.lastRechargeAt()).isNull();
            // C：无钱包行 → 全 0
            var cRow = rows.stream().filter(r -> r.userId().equals(uidC)).findFirst().orElseThrow();
            assertThat(cRow.balancePoints()).isEqualByComparingTo("0");
            assertThat(cRow.totalRechargeAmount()).isEqualByComparingTo("0");

            // 合计卡（全平台口径，含非 it_adm_ 用户）≥ 本三行 Σ，且口径一致可核对
            BigDecimal sumBal = rows.stream().map(r -> r.balancePoints()).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(vo.sumBalance()).isGreaterThanOrEqualTo(sumBal);
            assertThat(vo.sumRechargeAmount()).isGreaterThanOrEqualTo(new BigDecimal("10.00"));
            assertThat(vo.totalUsers()).isGreaterThanOrEqualTo(3);
        } finally {
            jdbc.update("DELETE FROM user_points_balance WHERE user_id=?", uidC);
            jdbc.update("DELETE FROM users WHERE id=?", uidC);
        }
    }
}
