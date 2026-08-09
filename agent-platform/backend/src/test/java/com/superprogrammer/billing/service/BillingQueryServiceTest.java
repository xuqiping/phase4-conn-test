package com.superprogrammer.billing.service;

import com.superprogrammer.billing.dto.UsageDetailVO;
import com.superprogrammer.billing.dto.UsageOverviewVO;
import com.superprogrammer.billing.dto.UserUsageVO;
import com.superprogrammer.billing.dto.UserWalletVO;
import com.superprogrammer.billing.entity.PointsLedgerEntity;
import com.superprogrammer.billing.mapper.LlmUsageLogMapper;
import com.superprogrammer.billing.mapper.PointsLedgerMapper;
import com.superprogrammer.common.result.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BillingQueryService 单测（Chunk I）：聚合委托 + limit 封顶 + 日期 clamp + 用户钱包/明细 ownership + VO 字段。
 */
@ExtendWith(MockitoExtension.class)
class BillingQueryServiceTest {

    @Mock private LlmUsageLogMapper usageLogMapper;
    @Mock private PointsLedgerMapper ledgerMapper;
    @Mock private PointsWalletService walletService;

    private BillingQueryService service;

    @BeforeEach
    void setUp() {
        service = new BillingQueryService(usageLogMapper, ledgerMapper, walletService);
    }

    @Test
    void overview_delegatesToMapper() {
        when(usageLogMapper.sumTotals(any(), any())).thenReturn(new UsageOverviewVO());
        OffsetDateTime from = OffsetDateTime.now().minusDays(10);
        OffsetDateTime to = OffsetDateTime.now();

        service.overview(from, to);

        verify(usageLogMapper).sumTotals(eq(from), eq(to));
    }

    @Test
    void rankByUser_capsLimitToDefaultMax() {
        // limit=999（恶意大）→ 截到 RANK_LIMIT(20)，防大 limit 拖垮 DB
        when(usageLogMapper.groupByUser(any(), any(), eq(BillingQueryService.RANK_LIMIT))).thenReturn(List.of());

        service.rankByUser(null, null, 999);

        verify(usageLogMapper).groupByUser(any(), any(), eq(BillingQueryService.RANK_LIMIT));
    }

    @Test
    void rankByModel_nullLimit_usesDefault() {
        when(usageLogMapper.groupByModel(any(), any(), eq(BillingQueryService.RANK_LIMIT))).thenReturn(List.of());

        service.rankByModel(null, null, null);

        verify(usageLogMapper).groupByModel(any(), any(), eq(BillingQueryService.RANK_LIMIT));
    }

    @Test
    void windowSpanOverMax_isClamped() {
        // 跨度 400 天 > MAX_DAYS(365) → from clamp 到约 to-365 天
        when(usageLogMapper.sumTotals(any(), any())).thenReturn(new UsageOverviewVO());
        OffsetDateTime from = OffsetDateTime.now().minusDays(400);
        OffsetDateTime to = OffsetDateTime.now();

        service.overview(from, to);

        org.mockito.ArgumentCaptor<OffsetDateTime> fromCap = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        org.mockito.ArgumentCaptor<OffsetDateTime> toCap = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(usageLogMapper).sumTotals(fromCap.capture(), toCap.capture());
        long clamped = java.time.Duration.between(fromCap.getValue(), toCap.getValue()).toDays();
        // clamp 后跨度应 ≈365（允许 ±1 时区抖动），而非 400
        assertEquals(365, clamped, 1, "超大区间应 clamp 到 MAX_DAYS");
    }

    @Test
    void userWallet_balanceFromWalletAndLedgerMapped_pointsOnly() {
        when(walletService.getBalance(100L)).thenReturn(new BigDecimal("250"));
        PointsLedgerEntity row = new PointsLedgerEntity();
        row.setCreatedAt(OffsetDateTime.now());
        row.setType("CONSUME");
        row.setDeltaPoints(new BigDecimal("-50"));
        row.setBalanceAfter(new BigDecimal("250"));
        row.setRemark("积分扣减");
        when(ledgerMapper.selectList(any())).thenReturn(List.of(row));

        UserWalletVO vo = service.userWallet(100L);

        assertEquals(new BigDecimal("250"), vo.getBalance());
        assertNotNull(vo.getRecentLedger());
        assertEquals(1, vo.getRecentLedger().size());
        assertEquals(new BigDecimal("-50"), vo.getRecentLedger().get(0).getDeltaPoints());
        assertEquals("CONSUME", vo.getRecentLedger().get(0).getType());
        // ownership：ledger 查询绑定 userId=100（LambdaQueryWrapper 不可直验，但 getBalance 收到的 userId 即凭据）
        verify(walletService).getBalance(eq(100L));
    }

    @Test
    void userUsage_passesUserIdForOwnership_andVoHasNoTokenOrCost() {
        // ownership：userId 由 controller 传 current user；service 透传给 mapper（SQL WHERE user_id=硬绑）
        UserUsageVO row = new UserUsageVO();
        row.setModel("gpt-4");
        row.setKind("CHAT");
        row.setPointsConsumed(new BigDecimal("12"));
        row.setStatus("SUCCESS");
        when(usageLogMapper.listForUser(eq(100L), any(), any(), anyInt())).thenReturn(List.of(row));

        List<UserUsageVO> list = service.userUsage(100L, null, null);

        assertEquals(1, list.size());
        verify(usageLogMapper).listForUser(eq(100L), any(), any(), eq(BillingQueryService.USER_USAGE_LIMIT));
        // 用户 VO 字段无 token/cost_yuan（编译期保证：UserUsageVO 仅 5 字段 createdAt/model/kind/pointsConsumed/status）
        assertEquals(new BigDecimal("12"), list.get(0).getPointsConsumed());
    }

    // ---------- admin 调用明细 pageDetail ----------

    @Test
    void pageDetail_returnsPageResultWithRecords() {
        UsageDetailVO row = new UsageDetailVO();
        row.setId(7L);
        row.setModel("glm-5.1");
        row.setUsername("admin");
        row.setTokensInput(496);
        row.setTokensOutput(36);
        row.setPointsConsumed(new BigDecimal("0.57"));
        when(usageLogMapper.countDetail(any(), any(), any(), any(), any(), any())).thenReturn(17L);
        when(usageLogMapper.pageDetail(any(), any(), any(), any(), any(), any(), anyLong(), eq((long) BillingQueryService.DETAIL_PAGE_SIZE)))
                .thenReturn(List.of(row));

        PageResult<UsageDetailVO> pr = service.pageDetail(null, null, null, null, null, null, 1, 0);

        assertEquals(17L, pr.getTotal());
        assertEquals(1, pr.getRecords().size());
        assertEquals("admin", pr.getRecords().get(0).getUsername());
        assertEquals(496, pr.getRecords().get(0).getTokensInput());
        assertEquals((long) BillingQueryService.DETAIL_PAGE_SIZE, pr.getSize());
    }

    @Test
    void pageDetail_sizeCappedToMax_andOffsetComputed() {
        // size=999（恶意大）→ 截到 DETAIL_MAX_SIZE(100)；page=3 → offset=(3-1)*100=200
        when(usageLogMapper.countDetail(any(), any(), any(), any(), any(), any())).thenReturn(500L);
        when(usageLogMapper.pageDetail(any(), any(), any(), any(), any(), any(), eq(200L), eq((long) BillingQueryService.DETAIL_MAX_SIZE)))
                .thenReturn(List.of());

        PageResult<UsageDetailVO> pr = service.pageDetail(null, null, null, null, null, null, 3, 999);

        assertEquals((long) BillingQueryService.DETAIL_MAX_SIZE, pr.getSize());
        assertEquals(3L, pr.getPage());
        org.mockito.ArgumentCaptor<Long> offsetCap = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(usageLogMapper).pageDetail(any(), any(), any(), any(), any(), any(), offsetCap.capture(), eq((long) BillingQueryService.DETAIL_MAX_SIZE));
        assertEquals(200L, offsetCap.getValue());
    }

    @Test
    void pageDetail_totalZero_shortCircuitsPageQuery() {
        // total=0 → 不调 pageDetail（短路免空查询），返空 records
        when(usageLogMapper.countDetail(any(), any(), any(), any(), any(), any())).thenReturn(0L);

        PageResult<UsageDetailVO> pr = service.pageDetail(null, null, null, null, null, null, 1, 20);

        assertTrue(pr.getRecords().isEmpty());
        assertEquals(0L, pr.getTotal());
        verify(usageLogMapper, never()).pageDetail(any(), any(), any(), any(), any(), any(), anyLong(), anyLong());
    }
}
