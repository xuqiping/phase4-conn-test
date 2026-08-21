package com.superprogrammer.billing.service;

import com.superprogrammer.billing.dto.PaymentOrderVO;
import com.superprogrammer.billing.entity.PaymentOrderEntity;
import com.superprogrammer.billing.mapper.PaymentOrderMapper;
import com.superprogrammer.billing.service.channel.MockPaymentChannel;
import com.superprogrammer.billing.service.channel.PaymentChannelRouter;
import com.superprogrammer.billing.service.channel.PaymentPrecreateResult;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付编排（7x#3）：下单幂等/快照、回调状态机、金额复核、终态单异常路径、过期批。
 */
@ExtendWith(MockitoExtension.class)
class PaymentOrderServiceTest {

    private static final long UID = 7L;

    @Mock private PaymentOrderMapper orderMapper;
    @Mock private PointsRatioService ratioService;
    @Mock private PointsWalletService walletService;
    @Mock private AuditLogService auditLogService;

    private MockPaymentChannel mockChannel;
    private PaymentChannelRouter router;

    private PaymentOrderService service;

    @BeforeEach
    void setUp() {
        mockChannel = new MockPaymentChannel();
        ReflectionTestUtils.setField(mockChannel, "secret", "test-secret");
        ReflectionTestUtils.setField(mockChannel, "mockEnabled", true);
        router = new PaymentChannelRouter(List.of(mockChannel,
                new com.superprogrammer.billing.service.channel.AlipayPaymentChannel(),
                new com.superprogrammer.billing.service.channel.WechatPaymentChannel()));
        service = new PaymentOrderService(orderMapper, router, ratioService, walletService, auditLogService);
        ReflectionTestUtils.setField(service, "orderExpireMinutes", 30);
        ReflectionTestUtils.setField(service, "notifyUrl", "https://example/notify");
        lenient().when(ratioService.toPoints(any())).thenAnswer(inv ->
                ((BigDecimal) inv.getArgument(0)).multiply(new BigDecimal("100")));
    }

    private PaymentOrderEntity pendingOrder(long id) {
        PaymentOrderEntity o = new PaymentOrderEntity();
        o.setId(id);
        o.setUserId(UID);
        o.setAmountYuan(new BigDecimal("10.00"));
        o.setPointsGranted(new BigDecimal("1000"));
        o.setStatus(PaymentOrderEntity.STATUS_PENDING);
        o.setChannel(PaymentOrderEntity.CHANNEL_MOCK);
        o.setExpireAt(OffsetDateTime.now().plusMinutes(30));
        return o;
    }

    // ==================== 下单 ====================

    @Test
    void 下单_快照积分_and_PENDING带过期() {
        when(orderMapper.insert(any())).thenAnswer(inv -> {
            PaymentOrderEntity o = inv.getArgument(0);
            o.setId(42L);
            o.setCreatedAt(OffsetDateTime.now());
            return 1;
        });

        PaymentOrderVO vo = service.createOrder(UID, new BigDecimal("10.00"), "MOCK", "idem-1");

        assertThat(vo.status()).isEqualTo("PENDING");
        assertThat(vo.pointsGranted()).isEqualByComparingTo("1000"); // 快照=下单时比例
        assertThat(vo.payToken()).isNotBlank();
        assertThat(vo.expireAt()).isAfter(OffsetDateTime.now());
    }

    @Test
    void 下单_idemKey同金额_返原单不新建() {
        PaymentOrderEntity existing = pendingOrder(42L);
        existing.setIdemKey("idem-1");
        existing.setCreatedAt(OffsetDateTime.now());
        when(orderMapper.selectByIdemKey(UID, "idem-1")).thenReturn(existing);

        PaymentOrderVO vo = service.createOrder(UID, new BigDecimal("10.00"), "MOCK", "idem-1");

        assertThat(vo.id()).isEqualTo(42L);
        assertThat(vo.payToken()).isNotBlank(); // PENDING 续付补发令牌
        verify(orderMapper, never()).insert(any());
    }

    @Test
    void 下单_idemKey不同金额_409() {
        PaymentOrderEntity existing = pendingOrder(42L);
        existing.setIdemKey("idem-1");
        when(orderMapper.selectByIdemKey(UID, "idem-1")).thenReturn(existing);
        when(auditLogService.fromMdc(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(new com.superprogrammer.common.audit.AuditLogEntity());

        assertThatThrownBy(() -> service.createOrder(UID, new BigDecimal("20.00"), "MOCK", "idem-1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重复提交金额不一致");
        verify(orderMapper, never()).insert(any());
    }

    @Test
    void 下单_金额越界_400() {
        assertThatThrownBy(() -> service.createOrder(UID, new BigDecimal("0.001"), "MOCK", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("充值金额");
        assertThatThrownBy(() -> service.createOrder(UID, new BigDecimal("100000"), "MOCK", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("充值金额");
    }

    @Test
    void 下单_渠道未开通_400() {
        assertThatThrownBy(() -> service.createOrder(UID, new BigDecimal("10"), "ALIPAY", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未开通");
    }

    // ==================== 查单/取消 ====================

    @Test
    void 查单_他人单404不泄露() {
        PaymentOrderEntity o = pendingOrder(42L);
        o.setUserId(999L);
        when(orderMapper.selectById(42L)).thenReturn(o);
        assertThatThrownBy(() -> service.getOrder(UID, 42L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("订单不存在");
    }

    @Test
    void 取消_已支付409_成功取消抢态() {
        when(orderMapper.cancelIfPending(42L, UID)).thenReturn(0);
        PaymentOrderEntity paid = pendingOrder(42L);
        paid.setStatus(PaymentOrderEntity.STATUS_PAID);
        when(orderMapper.selectById(42L)).thenReturn(paid);
        assertThatThrownBy(() -> service.cancel(UID, 42L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已支付");

        PaymentOrderService service2 = service;
        when(orderMapper.cancelIfPending(43L, UID)).thenReturn(1);
        when(auditLogService.fromMdc(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(new com.superprogrammer.common.audit.AuditLogEntity());
        service2.cancel(UID, 43L); // 不抛即过
    }

    // ==================== 回调 ====================

    private Map<String, String> notifyParams(PaymentOrderEntity o, boolean success) {
        return mockChannel.buildSignedNotify(o, success, "payer@mock");
    }

    @Test
    void 回调_成功入账全链() {
        PaymentOrderEntity o = pendingOrder(42L);
        when(orderMapper.selectByChannelOrder("MOCK", "MOCK-42")).thenReturn(o);
        when(orderMapper.markPaidIfPending(eq(42L), any())).thenReturn(1);
        when(walletService.creditRechargeForOrder(eq(UID), any(), any(), eq(42L), any()))
                .thenReturn(new BigDecimal("2000"));
        when(auditLogService.fromMdc(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(new com.superprogrammer.common.audit.AuditLogEntity());

        assertThat(service.handleNotify("MOCK", notifyParams(o, true))).isTrue();
        verify(walletService).creditRechargeForOrder(eq(UID),
                eq(new BigDecimal("1000")), eq(new BigDecimal("10.00")), eq(42L), any());
    }

    @Test
    void 回调_重推幂等_只入一次() {
        PaymentOrderEntity o = pendingOrder(42L);
        when(orderMapper.selectByChannelOrder("MOCK", "MOCK-42")).thenReturn(o);
        // 第一次抢态成功，第二三次 0 行（已 PAID）
        when(orderMapper.markPaidIfPending(eq(42L), any())).thenReturn(1, 0, 0);
        o.setStatus(PaymentOrderEntity.STATUS_PAID); // 抢态后内存态（真实链路第二三次查到 PAID）
        when(walletService.creditRechargeForOrder(anyLong(), any(), any(), anyLong(), any()))
                .thenReturn(new BigDecimal("2000"));
        when(auditLogService.fromMdc(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(new com.superprogrammer.common.audit.AuditLogEntity());

        assertThat(service.handleNotify("MOCK", notifyParams(o, true))).isTrue();
        assertThat(service.handleNotify("MOCK", notifyParams(o, true))).isTrue();
        assertThat(service.handleNotify("MOCK", notifyParams(o, true))).isTrue();
        verify(walletService, org.mockito.Mockito.times(1))
                .creditRechargeForOrder(anyLong(), any(), any(), anyLong(), any());
    }

    @Test
    void 回调_金额不符_FAILED拒入账() {
        PaymentOrderEntity o = pendingOrder(42L);
        when(orderMapper.selectByChannelOrder("MOCK", "MOCK-42")).thenReturn(o);
        // 渠道侧金额被改：构造 20.00 的合法签名回调（mock 密钥在服务端=测试可造）
        PaymentOrderEntity tampered = pendingOrder(42L);
        tampered.setAmountYuan(new BigDecimal("20.00"));
        when(auditLogService.fromMdc(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(new com.superprogrammer.common.audit.AuditLogEntity());

        assertThat(service.handleNotify("MOCK", notifyParams(tampered, true))).isFalse();
        verify(orderMapper).markFailedIfPending(42L);
        verify(walletService, never()).creditRechargeForOrder(anyLong(), any(), any(), anyLong(), any());
    }

    @Test
    void 回调_验签失败_拒() {
        PaymentOrderEntity o = pendingOrder(42L);
        Map<String, String> params = notifyParams(o, true);
        params.put("sign", "deadbeef");
        assertThat(service.handleNotify("MOCK", params)).isFalse();
        verify(orderMapper, never()).markPaidIfPending(anyLong(), any());
    }

    @Test
    void 回调_未知渠道单_拒() {
        when(orderMapper.selectByChannelOrder("MOCK", "MOCK-404")).thenReturn(null);
        PaymentOrderEntity ghost = pendingOrder(404L);
        assertThat(service.handleNotify("MOCK", notifyParams(ghost, true))).isFalse();
    }

    @Test
    void 回调_CLOSED单遇付款_不入账留痕() {
        PaymentOrderEntity o = pendingOrder(42L);
        o.setStatus(PaymentOrderEntity.STATUS_CLOSED);
        when(orderMapper.selectByChannelOrder("MOCK", "MOCK-42")).thenReturn(o);
        when(orderMapper.markPaidIfPending(eq(42L), any())).thenReturn(0);
        when(auditLogService.fromMdc(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(new com.superprogrammer.common.audit.AuditLogEntity());

        assertThat(service.handleNotify("MOCK", notifyParams(o, true))).isTrue(); // ack 止重推
        verify(walletService, never()).creditRechargeForOrder(anyLong(), any(), any(), anyLong(), any());
    }

    @Test
    void 回调_失败通知_PENDING转FAILED() {
        PaymentOrderEntity o = pendingOrder(42L);
        when(orderMapper.selectByChannelOrder("MOCK", "MOCK-42")).thenReturn(o);
        when(orderMapper.markFailedIfPending(42L)).thenReturn(1);
        when(auditLogService.fromMdc(anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(new com.superprogrammer.common.audit.AuditLogEntity());

        assertThat(service.handleNotify("MOCK", notifyParams(o, false))).isTrue();
        verify(orderMapper).markFailedIfPending(42L);
        verify(walletService, never()).creditRechargeForOrder(anyLong(), any(), any(), anyLong(), any());
    }

    // ==================== 过期批 ====================

    @Test
    void 过期批_逐行抢态关闭() {
        when(orderMapper.selectExpiredPendingIds(anyInt())).thenReturn(List.of(1L, 2L, 3L));
        when(orderMapper.closeIfPending(1L)).thenReturn(1);
        when(orderMapper.closeIfPending(2L)).thenReturn(0); // 被回调抢走
        when(orderMapper.closeIfPending(3L)).thenReturn(1);
        assertThat(service.expireBatch(200)).isEqualTo(2);
    }
}
