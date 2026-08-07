package com.superprogrammer.billing.service;

import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MediaBillingService 单测：算价→折算→扣→采 全链 + 三条降级（禁用/价表缺/退款）。
 * 对齐 plan §Step13 验证 + spec §联动（消耗扣、失败不阻塞出口、退款逆向）。
 */
@ExtendWith(MockitoExtension.class)
class MediaBillingServiceTest {

    @Mock private PricingService pricingService;
    @Mock private PointsRatioService ratioService;
    @Mock private PointsWalletService walletService;
    @Mock private UsageCollector usageCollector;

    private MediaBillingService service;

    @BeforeEach
    void setUp() {
        service = new MediaBillingService(pricingService, ratioService, walletService, usageCollector);
    }

    @Test
    void chargeMedia_happy_chargesAndRecords() {
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(eq(LlmUsageLogEntity.KIND_VIDEO), eq(7L), eq("seedance"),
                eq(200000), eq(null), eq(5), eq(0))).thenReturn(new BigDecimal("0.500000"));
        when(ratioService.toPoints(new BigDecimal("0.500000"))).thenReturn(new BigDecimal("50"));

        BigDecimal charged = service.chargeMedia(100L, 7L, "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, 5, 0, LlmUsageLogEntity.STATUS_SUCCESS, 9L);

        assertEquals(new BigDecimal("50"), charged);
        verify(walletService).charge(eq(100L), eq(new BigDecimal("50")),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(9L), eq("seedance"));
        verify(usageCollector).record(eq(100L), eq(7L), eq(LlmUsageLogEntity.SCOPE_GLOBAL), eq("seedance"),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(200000), eq(null),
                eq(new BigDecimal("0.500000")), eq(new BigDecimal("50")),
                eq(LlmUsageLogEntity.STATUS_SUCCESS), eq(null));
    }

    @Test
    void chargeMedia_billingDisabled_returnsNullNoOp() {
        when(walletService.isEnabled()).thenReturn(false);

        BigDecimal charged = service.chargeMedia(100L, 7L, "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, 5, 0, LlmUsageLogEntity.STATUS_SUCCESS, 9L);

        assertNull(charged);
        verify(pricingService, never()).computeCost(anyString(), anyLong(), anyString(),
                anyInt(), any(), anyInt(), anyInt());
        verify(walletService, never()).charge(anyLong(), any(), anyString(), anyLong(), anyString());
        verify(usageCollector, never()).record(anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyInt(), any(), any(), any(), anyString(), any());
    }

    @Test
    void chargeMedia_pricingMissing_recordsFailedNoCharge() {
        // 价表缺（PRICING_NOT_FOUND）：视频已生成不可逆→记 FAILED usage 供 admin 排障，不抛、不扣
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(anyString(), anyLong(), anyString(),
                anyInt(), any(), anyInt(), anyInt()))
                .thenThrow(new BusinessException(ErrorCode.PRICING_NOT_FOUND));

        BigDecimal charged = service.chargeMedia(100L, 7L, "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, 5, 0, LlmUsageLogEntity.STATUS_SUCCESS, 9L);

        assertNull(charged);
        verify(walletService, never()).charge(anyLong(), any(), anyString(), anyLong(), anyString());
        verify(usageCollector).record(eq(100L), eq(7L), eq(LlmUsageLogEntity.SCOPE_GLOBAL), eq("seedance"),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(200000), eq(null),
                eq(null), eq(null), eq(LlmUsageLogEntity.STATUS_FAILED), anyString());
    }

    @Test
    void chargeMedia_image_chargesByCountDimension() {
        // Chunk G：IMAGE 走 count 维度（price_per_image×count），kind=IMAGE，videoSeconds/imageCount 占位互换
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(eq(LlmUsageLogEntity.KIND_IMAGE), eq(7L), eq("doubao-3-0"),
                eq(null), eq(null), eq(null), eq(4))).thenReturn(new BigDecimal("0.800000"));
        when(ratioService.toPoints(new BigDecimal("0.800000"))).thenReturn(new BigDecimal("80"));

        BigDecimal charged = service.chargeMedia(100L, 7L, "doubao-3-0", LlmUsageLogEntity.KIND_IMAGE,
                null, null, 4, LlmUsageLogEntity.STATUS_SUCCESS, 9L);

        assertEquals(new BigDecimal("80"), charged);
        verify(walletService).charge(eq(100L), eq(new BigDecimal("80")),
                eq(LlmUsageLogEntity.KIND_IMAGE), eq(9L), eq("doubao-3-0"));
        verify(usageCollector).record(eq(100L), eq(7L), eq(LlmUsageLogEntity.SCOPE_GLOBAL), eq("doubao-3-0"),
                eq(LlmUsageLogEntity.KIND_IMAGE), eq(null), eq(null),
                eq(new BigDecimal("0.800000")), eq(new BigDecimal("80")),
                eq(LlmUsageLogEntity.STATUS_SUCCESS), eq(null));
    }

    @Test
    void refundMedia_nullOrZero_isNoOp() {
        service.refundMedia(100L, null, LlmUsageLogEntity.KIND_VIDEO, 9L);
        service.refundMedia(100L, BigDecimal.ZERO, LlmUsageLogEntity.KIND_VIDEO, 9L);

        verify(walletService, never()).refund(anyLong(), any(), anyString(), anyLong(), anyString());
    }

    @Test
    void refundMedia_positive_refunds() {
        service.refundMedia(100L, new BigDecimal("50"), LlmUsageLogEntity.KIND_VIDEO, 9L);

        verify(walletService).refund(eq(100L), eq(new BigDecimal("50")),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(9L), anyString());
    }
}
