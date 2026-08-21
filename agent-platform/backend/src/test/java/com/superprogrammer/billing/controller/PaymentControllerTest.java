package com.superprogrammer.billing.controller;

import com.superprogrammer.billing.dto.PaymentOrderVO;
import com.superprogrammer.billing.entity.PaymentOrderEntity;
import com.superprogrammer.billing.service.PaymentOrderService;
import com.superprogrammer.billing.service.channel.MockPaymentChannel;
import com.superprogrammer.billing.service.channel.PaymentChannelRouter;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 支付用户端点（7x#3）：mock trigger 三道闸（开关/渠道/终态）+ 下单/查单透传本人口径。 */
@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    private static final long UID = 7L;

    @Mock private PaymentOrderService paymentOrderService;
    @Mock private PaymentChannelRouter channelRouter;
    @Mock private MockPaymentChannel mockPaymentChannel;

    @InjectMocks
    private PaymentController controller;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UID, "u", List.of()));
    }

    private PaymentOrderVO orderVo(String status, String channel) {
        return new PaymentOrderVO(42L, OffsetDateTime.now(), new BigDecimal("10.00"),
                new BigDecimal("1000"), status, channel, null,
                OffsetDateTime.now().plusMinutes(30), null, "token");
    }

    @Test
    void trigger_mock关闭_404防暴露() {
        ReflectionTestUtils.setField(controller, "mockEnabled", false);
        PaymentController.MockTriggerRequest req = new PaymentController.MockTriggerRequest();
        req.setOrderId(42L);
        req.setSuccess(true);
        assertThatThrownBy(() -> controller.mockTrigger(req))
                .isInstanceOf(BusinessException.class).hasMessageContaining("接口不存在");
        verify(paymentOrderService, never()).handleNotify(anyString(), any());
    }

    @Test
    void trigger_非MOCK单_400() {
        ReflectionTestUtils.setField(controller, "mockEnabled", true);
        when(paymentOrderService.getOrder(UID, 42L)).thenReturn(orderVo("PENDING", "ALIPAY"));
        PaymentController.MockTriggerRequest req = new PaymentController.MockTriggerRequest();
        req.setOrderId(42L);
        req.setSuccess(true);
        assertThatThrownBy(() -> controller.mockTrigger(req))
                .isInstanceOf(BusinessException.class).hasMessageContaining("MOCK");
    }

    @Test
    void trigger_终态单_409() {
        ReflectionTestUtils.setField(controller, "mockEnabled", true);
        when(paymentOrderService.getOrder(UID, 42L)).thenReturn(orderVo("PAID", "MOCK"));
        PaymentController.MockTriggerRequest req = new PaymentController.MockTriggerRequest();
        req.setOrderId(42L);
        req.setSuccess(true);
        assertThatThrownBy(() -> controller.mockTrigger(req))
                .isInstanceOf(BusinessException.class).hasMessageContaining("终态");
    }

    @Test
    void trigger_成功走同一handleNotify链路() {
        ReflectionTestUtils.setField(controller, "mockEnabled", true);
        when(paymentOrderService.getOrder(UID, 42L)).thenReturn(orderVo("PENDING", "MOCK"));
        when(mockPaymentChannel.buildSignedNotify(any(), eq(true), any()))
                .thenReturn(java.util.Map.of("k", "v"));
        when(paymentOrderService.handleNotify(eq("MOCK"), any())).thenReturn(true);

        PaymentController.MockTriggerRequest req = new PaymentController.MockTriggerRequest();
        req.setOrderId(42L);
        req.setSuccess(true);
        var resp = controller.mockTrigger(req);
        assertThat(resp.getBody().getData().get("accepted")).isEqualTo(true);
        verify(paymentOrderService).handleNotify(eq("MOCK"), eq(java.util.Map.of("k", "v")));
    }

    @Test
    void 下单透传当前用户与幂等键() {
        when(paymentOrderService.createOrder(eq(UID), any(), eq("MOCK"), eq("idem-9")))
                .thenReturn(orderVo("PENDING", "MOCK"));
        PaymentController.CreateOrderRequest req = new PaymentController.CreateOrderRequest();
        req.setAmountYuan(new BigDecimal("10.00"));
        req.setChannel("MOCK");
        req.setIdemKey("idem-9");
        var resp = controller.create(req);
        assertThat(resp.getBody().getData().status()).isEqualTo("PENDING");
        verify(paymentOrderService).createOrder(UID, new BigDecimal("10.00"), "MOCK", "idem-9");
    }

    @Test
    void 可用渠道透传() {
        when(channelRouter.availableChannels()).thenReturn(List.of("MOCK"));
        assertThat(controller.channels().getBody().getData()).containsExactly("MOCK");
    }
}
