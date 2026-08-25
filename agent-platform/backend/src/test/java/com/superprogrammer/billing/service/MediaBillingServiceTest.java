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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
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
    /** 计划5 Step5：组池结算分支 mock。 */
    @Mock private com.superprogrammer.projectgroup.service.ProjectGroupWalletService groupWalletService;
    @Mock private com.superprogrammer.projectgroup.mapper.ProjectGroupMapper groupMapper;
    @Mock private UsageCollector usageCollector;

    private MediaBillingService service;

    @BeforeEach
    void setUp() {
        service = new MediaBillingService(pricingService, ratioService, walletService,
                groupWalletService, groupMapper, usageCollector);
    }

    @Test
    void chargeMedia_happy_chargesAndRecords() {
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(eq(LlmUsageLogEntity.KIND_VIDEO), eq(7L), eq("seedance"),
                eq(200000), eq(null), eq(5), eq(0), anyBoolean(), any())).thenReturn(new BigDecimal("0.500000"));
        when(ratioService.toPoints(new BigDecimal("0.500000"))).thenReturn(new BigDecimal("50"));

        BigDecimal charged = service.chargeMedia(100L, 7L, "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, 5, 0, LlmUsageLogEntity.STATUS_SUCCESS, 9L);

        assertEquals(new BigDecimal("50"), charged);
        verify(walletService).charge(eq(100L), eq(new BigDecimal("50")),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(9L), eq("seedance"));
        verify(usageCollector).record(eq(100L), eq(7L), eq(LlmUsageLogEntity.SCOPE_GLOBAL), eq("seedance"),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(200000), eq(null),
                eq(new BigDecimal("0.500000")), eq(new BigDecimal("50")),
                eq(LlmUsageLogEntity.STATUS_SUCCESS), eq(null), eq(9L), isNull(), isNull());
    }

    @Test
    void chargeMedia_billingDisabled_returnsNullNoOp() {
        when(walletService.isEnabled()).thenReturn(false);

        BigDecimal charged = service.chargeMedia(100L, 7L, "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, 5, 0, LlmUsageLogEntity.STATUS_SUCCESS, 9L);

        assertNull(charged);
        verify(pricingService, never()).computeCost(anyString(), anyLong(), anyString(),
                anyInt(), any(), anyInt(), anyInt(), anyBoolean(), any());
        verify(walletService, never()).charge(anyLong(), any(), anyString(), anyLong(), anyString());
        verify(usageCollector, never()).record(anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyInt(), any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void chargeMedia_pricingMissing_recordsFailedNoCharge() {
        // 价表缺（PRICING_NOT_FOUND）：视频已生成不可逆→记 FAILED usage 供 admin 排障，不抛、不扣
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(anyString(), anyLong(), anyString(),
                anyInt(), any(), anyInt(), anyInt(), anyBoolean(), any()))
                .thenThrow(new BusinessException(ErrorCode.PRICING_NOT_FOUND));

        BigDecimal charged = service.chargeMedia(100L, 7L, "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, 5, 0, LlmUsageLogEntity.STATUS_SUCCESS, 9L);

        assertNull(charged);
        verify(walletService, never()).charge(anyLong(), any(), anyString(), anyLong(), anyString());
        verify(usageCollector).record(eq(100L), eq(7L), eq(LlmUsageLogEntity.SCOPE_GLOBAL), eq("seedance"),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(200000), eq(null),
                eq(null), eq(null), eq(LlmUsageLogEntity.STATUS_FAILED), anyString(), eq(9L));
    }

    @Test
    void chargeMedia_image_chargesByCountDimension() {
        // Chunk G：IMAGE 走 count 维度（price_per_image×count），kind=IMAGE，videoSeconds/imageCount 占位互换
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(eq(LlmUsageLogEntity.KIND_IMAGE), eq(7L), eq("doubao-3-0"),
                eq(null), eq(null), eq(null), eq(4), anyBoolean(), any())).thenReturn(new BigDecimal("0.800000"));
        when(ratioService.toPoints(new BigDecimal("0.800000"))).thenReturn(new BigDecimal("80"));

        BigDecimal charged = service.chargeMedia(100L, 7L, "doubao-3-0", LlmUsageLogEntity.KIND_IMAGE,
                null, null, 4, LlmUsageLogEntity.STATUS_SUCCESS, 9L);

        assertEquals(new BigDecimal("80"), charged);
        verify(walletService).charge(eq(100L), eq(new BigDecimal("80")),
                eq(LlmUsageLogEntity.KIND_IMAGE), eq(9L), eq("doubao-3-0"));
        verify(usageCollector).record(eq(100L), eq(7L), eq(LlmUsageLogEntity.SCOPE_GLOBAL), eq("doubao-3-0"),
                eq(LlmUsageLogEntity.KIND_IMAGE), eq(null), eq(null),
                eq(new BigDecimal("0.800000")), eq(new BigDecimal("80")),
                eq(LlmUsageLogEntity.STATUS_SUCCESS), eq(null), eq(9L), isNull(), isNull());
    }

    // ---------------- 7x-3：VIDEO has_reference 计费穿线 ----------------

    @Test
    void chargeMedia_videoWithReference_threadsHasReferenceToPricing() {
        // 7x-3：带参考视频任务计费时，hasReference=true 必须透传到 PricingService（命中 true 价表行）
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(eq(LlmUsageLogEntity.KIND_VIDEO), eq(7L), eq("seedance"),
                eq(200000), eq(null), eq(5), eq(0), eq(true), isNull())).thenReturn(new BigDecimal("0.100000"));
        when(ratioService.toPoints(new BigDecimal("0.100000"))).thenReturn(new BigDecimal("10"));

        BigDecimal charged = service.chargeMedia(100L, 7L, "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, 5, 0, LlmUsageLogEntity.STATUS_SUCCESS, 9L, true);

        assertEquals(new BigDecimal("10"), charged);
        // 关键断言：computeCost 收到的第 8 个参数是 true（hasReference 透传正确）
        verify(pricingService).computeCost(eq(LlmUsageLogEntity.KIND_VIDEO), eq(7L), eq("seedance"),
                eq(200000), eq(null), eq(5), eq(0), eq(true), isNull());
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

    // ---------------- 计划5 Step5：媒体组池结算/退款/兜底 ----------------

    @Test
    void chargeMedia_withGroup_chargesGroupPoolNotPersonal() {
        // 选组结算：chargeGroup（幂等键=media-charge-{taskId}，429 退避重投不双扣），个人钱包不动
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(eq(LlmUsageLogEntity.KIND_VIDEO), eq(7L), eq("seedance"),
                eq(200000), eq(null), eq(5), eq(0), anyBoolean(), any())).thenReturn(new BigDecimal("0.500000"));
        when(ratioService.toPoints(new BigDecimal("0.500000"))).thenReturn(new BigDecimal("50"));

        BigDecimal charged = service.chargeMedia(100L, 7L, "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, 5, 0, LlmUsageLogEntity.STATUS_SUCCESS, 9L, false, 5L);

        assertEquals(new BigDecimal("50"), charged);
        verify(groupWalletService).chargeGroup(eq(5L), eq(100L), eq(new BigDecimal("50")),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq("9"), eq("media-charge-9"));
        verify(walletService, never()).charge(anyLong(), any(), anyString(), anyLong(), anyString());
        // usage 行带 gid（账单/项目推进唯一事实源）
        verify(usageCollector).record(eq(100L), eq(7L), eq(LlmUsageLogEntity.SCOPE_GLOBAL), eq("seedance"),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(200000), eq(null),
                eq(new BigDecimal("0.500000")), eq(new BigDecimal("50")),
                eq(LlmUsageLogEntity.STATUS_SUCCESS), eq(null), eq(9L), isNull(), eq(5L));
    }

    @Test
    void chargeMedia_groupPoolExhausted_backstopsLeader() {
        // 残余竞态（提交预检已过、结算时组池尽/超限额）→ 组长个人兜底全额；视频已生成不可逆
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(anyString(), anyLong(), anyString(),
                anyInt(), any(), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(new BigDecimal("0.500000"));
        when(ratioService.toPoints(new BigDecimal("0.500000"))).thenReturn(new BigDecimal("50"));
        doThrow(new BusinessException(ErrorCode.INSUFFICIENT_POINTS)).when(groupWalletService)
                .chargeGroup(anyLong(), anyLong(), any(), anyString(), anyString(), anyString());
        com.superprogrammer.projectgroup.entity.ProjectGroupEntity group =
                new com.superprogrammer.projectgroup.entity.ProjectGroupEntity();
        group.setOwnerUserId(200L);
        when(groupMapper.selectById(5L)).thenReturn(group);

        BigDecimal charged = service.chargeMedia(100L, 7L, "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, 5, 0, LlmUsageLogEntity.STATUS_SUCCESS, 9L, false, 5L);

        assertEquals(new BigDecimal("50"), charged);
        verify(groupWalletService).backstop(eq(5L), eq(200L), eq(false), eq(new BigDecimal("50")),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq("9"));
        // 兜底成功仍返回实扣值（worker 退款链路完整），且不动个人 charge
        verify(walletService, never()).charge(anyLong(), any(), anyString(), anyLong(), anyString());
    }

    @Test
    void chargeMedia_backstopAlsoFails_recordsFailedUsage() {
        // 组长个人也不足/组已删 → 兜底抛 → 外层记 FAILED usage（平台缺口 admin 可见），返回 null 不抛
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(anyString(), anyLong(), anyString(),
                anyInt(), any(), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(new BigDecimal("0.500000"));
        when(ratioService.toPoints(new BigDecimal("0.500000"))).thenReturn(new BigDecimal("50"));
        doThrow(new BusinessException(ErrorCode.INSUFFICIENT_POINTS)).when(groupWalletService)
                .chargeGroup(anyLong(), anyLong(), any(), anyString(), anyString(), anyString());
        com.superprogrammer.projectgroup.entity.ProjectGroupEntity group =
                new com.superprogrammer.projectgroup.entity.ProjectGroupEntity();
        group.setOwnerUserId(200L);
        when(groupMapper.selectById(5L)).thenReturn(group);
        doThrow(new BusinessException(ErrorCode.INSUFFICIENT_POINTS)).when(groupWalletService)
                .backstop(anyLong(), anyLong(), anyBoolean(), any(), anyString(), anyString());

        BigDecimal charged = service.chargeMedia(100L, 7L, "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, 5, 0, LlmUsageLogEntity.STATUS_SUCCESS, 9L, false, 5L);

        assertNull(charged);
        verify(usageCollector).record(eq(100L), eq(7L), eq(LlmUsageLogEntity.SCOPE_GLOBAL), eq("seedance"),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(200000), eq(null),
                eq(null), eq(null), eq(LlmUsageLogEntity.STATUS_FAILED), anyString(), eq(9L));
    }

    @Test
    void refundMedia_withGroup_refundsGroupPool() {
        // 失败退款：refundGroup（幂等键=media-refund-{taskId}）——组池回加 + used 回减；个人钱包不动
        service.refundMedia(100L, new BigDecimal("50"), LlmUsageLogEntity.KIND_VIDEO, 9L, 5L);

        verify(groupWalletService).refundGroup(eq(5L), eq(100L), eq(new BigDecimal("50")),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq("9"), eq("media-refund-9"));
        verify(walletService, never()).refund(anyLong(), any(), anyString(), anyLong(), anyString());
    }
}
