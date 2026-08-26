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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        verify(groupWalletService).backstop(eq(5L), eq(200L), eq(100L), eq(false), eq(new BigDecimal("50")),
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
                .backstop(anyLong(), anyLong(), any(), anyBoolean(), any(), anyString(), anyString());

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

    // ==================== 7x（V155）预扣 + 多退少补 ====================

    @Test
    void holdMediaEstimated_personal_chargesHoldLeg() {
        when(walletService.isEnabled()).thenReturn(true);

        boolean held = service.holdMediaEstimated(100L, new BigDecimal("50"),
                LlmUsageLogEntity.KIND_VIDEO, 9L, null);

        assertTrue(held);
        verify(walletService).chargeIdempotent(eq(100L), eq(new BigDecimal("50")),
                eq(LlmUsageLogEntity.KIND_VIDEO + "-HOLD"), eq(9L), anyString(), eq("media-hold-9"));
    }

    @Test
    void holdMediaEstimated_group_chargesGroupPool() {
        when(walletService.isEnabled()).thenReturn(true);

        boolean held = service.holdMediaEstimated(100L, new BigDecimal("30"),
                LlmUsageLogEntity.KIND_IMAGE, 9L, 5L);

        assertTrue(held);
        verify(groupWalletService).chargeGroup(eq(5L), eq(100L), eq(new BigDecimal("30")),
                eq(LlmUsageLogEntity.KIND_IMAGE + "-HOLD"), eq("9"), eq("media-hold-9"));
        verify(walletService, never()).chargeIdempotent(anyLong(), any(), anyString(), anyLong(),
                anyString(), anyString());
    }

    @Test
    void holdMediaEstimated_disabledOrZero_returnsFalse() {
        when(walletService.isEnabled()).thenReturn(false);
        assertFalse(service.holdMediaEstimated(100L, new BigDecimal("50"),
                LlmUsageLogEntity.KIND_VIDEO, 9L, null));
        // est=0/null 不扣（无价表口径，与预检一致）
        when(walletService.isEnabled()).thenReturn(true);
        assertFalse(service.holdMediaEstimated(100L, BigDecimal.ZERO,
                LlmUsageLogEntity.KIND_VIDEO, 9L, null));
        assertFalse(service.holdMediaEstimated(100L, null,
                LlmUsageLogEntity.KIND_VIDEO, 9L, null));
        verify(walletService, never()).chargeIdempotent(anyLong(), any(), anyString(), anyLong(),
                anyString(), anyString());
    }

    @Test
    void holdMediaEstimated_insufficient_throws() {
        // 预检后被并发耗尽 → 预扣失败直抛（提交侧删任务行拒绝，不吞）
        when(walletService.isEnabled()).thenReturn(true);
        doThrow(new BusinessException(ErrorCode.INSUFFICIENT_POINTS, "积分不足"))
                .when(walletService).chargeIdempotent(anyLong(), any(), anyString(), anyLong(),
                        anyString(), anyString());

        assertThrows(BusinessException.class, () -> service.holdMediaEstimated(100L,
                new BigDecimal("50"), LlmUsageLogEntity.KIND_VIDEO, 9L, null));
    }

    @Test
    void settleMediaSuccess_actualGreater_supplementsDiff() {
        // 实耗 80 > 预扣 50 → 补扣 30（kind 腿，幂等键 media-settle-{refId}），返实耗
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(eq(LlmUsageLogEntity.KIND_VIDEO), eq(7L), eq("seedance"),
                eq(200000), eq(null), eq(5), eq(0), anyBoolean(), any())).thenReturn(new BigDecimal("0.800000"));
        when(ratioService.toPoints(new BigDecimal("0.800000"))).thenReturn(new BigDecimal("80"));

        BigDecimal actual = service.settleMediaSuccess(100L, 7L, "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, 5, 0, LlmUsageLogEntity.STATUS_SUCCESS, 9L, false, null, "720p",
                new BigDecimal("50"));

        assertEquals(new BigDecimal("80"), actual);
        verify(walletService).chargeIdempotent(eq(100L), eq(new BigDecimal("30")),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(9L), anyString(), eq("media-settle-9"));
        verify(usageCollector).record(eq(100L), eq(7L), eq(LlmUsageLogEntity.SCOPE_GLOBAL), eq("seedance"),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(200000), eq(null),
                eq(new BigDecimal("0.800000")), eq(new BigDecimal("80")),
                eq(LlmUsageLogEntity.STATUS_SUCCESS), eq(null), eq(9L), isNull(), isNull());
    }

    @Test
    void settleMediaSuccess_actualLess_refundsDiff() {
        // 实耗 30 < 预扣 50 → 退差 20（kind REFUND 腿），返实耗
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(anyString(), anyLong(), anyString(),
                anyInt(), eq(null), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(new BigDecimal("0.300000"));
        when(ratioService.toPoints(new BigDecimal("0.300000"))).thenReturn(new BigDecimal("30"));

        BigDecimal actual = service.settleMediaSuccess(100L, 7L, "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, 5, 0, LlmUsageLogEntity.STATUS_SUCCESS, 9L, false, null, "720p",
                new BigDecimal("50"));

        assertEquals(new BigDecimal("30"), actual);
        verify(walletService).refundIdempotent(eq(100L), eq(new BigDecimal("20")),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(9L), anyString(), eq("media-settle-9"));
    }

    @Test
    void settleMediaSuccess_equal_noop() {
        // 实耗 == 预扣 → 不动钱包，仍记 usage
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(anyString(), anyLong(), anyString(),
                anyInt(), eq(null), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(new BigDecimal("0.500000"));
        when(ratioService.toPoints(new BigDecimal("0.500000"))).thenReturn(new BigDecimal("50"));

        BigDecimal actual = service.settleMediaSuccess(100L, 7L, "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, 5, 0, LlmUsageLogEntity.STATUS_SUCCESS, 9L, false, null, "720p",
                new BigDecimal("50"));

        assertEquals(new BigDecimal("50"), actual);
        verify(walletService, never()).chargeIdempotent(anyLong(), any(), anyString(), anyLong(),
                anyString(), anyString());
        verify(walletService, never()).refundIdempotent(anyLong(), any(), anyString(), anyLong(),
                anyString(), anyString());
    }

    @Test
    void settleMediaSuccess_supplementFails_recordsFailedReturnsHeld() {
        // B5（Q10=A）后语义：补扣余额耗尽 → chargeToDebt 挂账接管（不再落 FAILED），
        // 返实耗 80（worker unwind 按实耗退）。挂账腿本身在 PointsWalletServiceTest 覆盖，此处验编排。
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(anyString(), anyLong(), anyString(),
                anyInt(), eq(null), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(new BigDecimal("0.800000"));
        when(ratioService.toPoints(new BigDecimal("0.800000"))).thenReturn(new BigDecimal("80"));
        doThrow(new BusinessException(ErrorCode.INSUFFICIENT_POINTS, "积分不足"))
                .when(walletService).chargeIdempotent(anyLong(), any(), anyString(), anyLong(),
                        anyString(), anyString());

        BigDecimal actual = service.settleMediaSuccess(100L, 7L, "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, 5, 0, LlmUsageLogEntity.STATUS_SUCCESS, 9L, false, null, "720p",
                new BigDecimal("50"));

        assertEquals(new BigDecimal("80"), actual);
        verify(walletService).chargeToDebt(eq(100L), eq(new BigDecimal("30")), eq(LlmUsageLogEntity.KIND_VIDEO),
                eq(9L), anyString());
        verify(usageCollector).record(eq(100L), eq(7L), eq(LlmUsageLogEntity.SCOPE_GLOBAL), eq("seedance"),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(200000), eq(null),
                eq(new BigDecimal("0.800000")), eq(new BigDecimal("80")),
                eq(LlmUsageLogEntity.STATUS_SUCCESS), eq(null), eq(9L),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void settleMediaSuccess_groupSupplementFails_backstopToOwner() {
        // 组任务补扣：组池尽 → BACKSTOP 差额扣组长个人（同 chargeMedia 口径）
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(anyString(), anyLong(), anyString(),
                anyInt(), eq(null), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(new BigDecimal("0.800000"));
        when(ratioService.toPoints(new BigDecimal("0.800000"))).thenReturn(new BigDecimal("80"));
        doThrow(new BusinessException(ErrorCode.INSUFFICIENT_POINTS, "项目组积分不足"))
                .when(groupWalletService).chargeGroup(anyLong(), anyLong(), any(), anyString(),
                        anyString(), anyString());
        var group = new com.superprogrammer.projectgroup.entity.ProjectGroupEntity();
        group.setOwnerUserId(200L);
        when(groupMapper.selectById(5L)).thenReturn(group);

        BigDecimal actual = service.settleMediaSuccess(100L, 7L, "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, 5, 0, LlmUsageLogEntity.STATUS_SUCCESS, 9L, false, 5L, "720p",
                new BigDecimal("50"));

        assertEquals(new BigDecimal("80"), actual);
        verify(groupWalletService).backstop(eq(5L), eq(200L), eq(100L), eq(false), eq(new BigDecimal("30")),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq("9"));
    }

    @Test
    void settleMediaSuccess_noHold_delegatesFullCharge() {
        // 存量在途任务（hold_applied=false → held=null）：走原 chargeMedia 全量扣
        when(walletService.isEnabled()).thenReturn(true);
        when(pricingService.computeCost(anyString(), anyLong(), anyString(),
                anyInt(), eq(null), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(new BigDecimal("0.500000"));
        when(ratioService.toPoints(new BigDecimal("0.500000"))).thenReturn(new BigDecimal("50"));

        BigDecimal actual = service.settleMediaSuccess(100L, 7L, "seedance", LlmUsageLogEntity.KIND_VIDEO,
                200000, 5, 0, LlmUsageLogEntity.STATUS_SUCCESS, 9L, false, null, "720p", null);

        assertEquals(new BigDecimal("50"), actual);
        verify(walletService).charge(eq(100L), eq(new BigDecimal("50")),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(9L), anyString());
    }

    @Test
    void refundMediaCharged_twoLegs_refundsHoldAndSupplement() {
        // 落库失败撤销（实耗 80，预扣 50）：预扣腿 50 退 VIDEO-HOLD + 补扣腿 30 退 VIDEO，幂等键独立
        service.refundMediaCharged(100L, new BigDecimal("80"), new BigDecimal("50"),
                LlmUsageLogEntity.KIND_VIDEO, 9L, null);

        verify(walletService).refundIdempotent(eq(100L), eq(new BigDecimal("50")),
                eq(LlmUsageLogEntity.KIND_VIDEO + "-HOLD"), eq(9L), anyString(), eq("media-hold-refund-9"));
        verify(walletService).refundIdempotent(eq(100L), eq(new BigDecimal("30")),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(9L), anyString(), eq("media-settle-refund-9"));
    }

    @Test
    void refundMediaCharged_chargedBelowHold_refundsSingleHoldLeg() {
        // 补扣失败场景（实耗=预扣 50）：supLeg=0 只退预扣腿
        service.refundMediaCharged(100L, new BigDecimal("50"), new BigDecimal("50"),
                LlmUsageLogEntity.KIND_VIDEO, 9L, null);

        verify(walletService).refundIdempotent(eq(100L), eq(new BigDecimal("50")),
                eq(LlmUsageLogEntity.KIND_VIDEO + "-HOLD"), eq(9L), anyString(), eq("media-hold-refund-9"));
        verify(walletService, never()).refundIdempotent(eq(100L), any(),
                eq(LlmUsageLogEntity.KIND_VIDEO), anyLong(), anyString(), anyString());
    }

    @Test
    void refundMediaCharged_noHold_delegatesLegacyRefund() {
        // 存量任务（held=null）：原 refundMedia 单腿（个人 refund 直退）
        service.refundMediaCharged(100L, new BigDecimal("50"), null,
                LlmUsageLogEntity.KIND_VIDEO, 9L, null);

        verify(walletService).refund(eq(100L), eq(new BigDecimal("50")),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(9L), anyString());
    }

    @Test
    void refundMediaHold_group_refundsHoldLeg() {
        service.refundMediaHold(100L, new BigDecimal("50"),
                LlmUsageLogEntity.KIND_VIDEO, 9L, 5L);

        verify(groupWalletService).refundGroup(eq(5L), eq(100L), eq(new BigDecimal("50")),
                eq(LlmUsageLogEntity.KIND_VIDEO + "-HOLD"), eq("9"), eq("media-hold-refund-9"));
    }

    @Test
    void refundMediaHold_swallowsExceptions() {
        // 吞异常不阻塞 worker 终态
        doThrow(new RuntimeException("DB 抖动")).when(walletService)
                .refundIdempotent(anyLong(), any(), anyString(), anyLong(), anyString(), anyString());

        service.refundMediaHold(100L, new BigDecimal("50"),
                LlmUsageLogEntity.KIND_VIDEO, 9L, null); // 不抛即过
    }
}
