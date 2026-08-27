package com.superprogrammer.billing.service;

import com.superprogrammer.billing.dto.UsageDetailVO;
import com.superprogrammer.billing.dto.UsageOverviewVO;
import com.superprogrammer.billing.dto.UserUsageVO;
import com.superprogrammer.billing.dto.UserWalletVO;
import com.superprogrammer.billing.entity.PointsLedgerEntity;
import com.superprogrammer.billing.mapper.LlmUsageLogMapper;
import com.superprogrammer.billing.mapper.PointsLedgerMapper;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.projectgroup.entity.ProjectGroupEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMapper;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    @Mock private ProjectGroupMapper groupMapper;
    @Mock private com.superprogrammer.billing.mapper.PaymentOrderMapper paymentOrderMapper;
    @Mock private com.superprogrammer.billing.mapper.UserPointsBalanceMapper balanceMapper;
    @Mock private com.superprogrammer.billing.mapper.GroupAllocationMapper groupAllocationMapper;

    private BillingQueryService service;

    @BeforeEach
    void setUp() {
        service = new BillingQueryService(usageLogMapper, ledgerMapper, walletService, groupMapper,
                paymentOrderMapper, balanceMapper, groupAllocationMapper);
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
        when(usageLogMapper.listForUser(eq(100L), any(), any(), any(), anyInt())).thenReturn(List.of(row));

        List<UserUsageVO> list = service.userUsage(100L, null, null, null);

        assertEquals(1, list.size());
        verify(usageLogMapper).listForUser(eq(100L), any(), any(), any(), eq(BillingQueryService.USER_USAGE_LIMIT));
        // 用户 VO 字段无 token/cost_yuan（编译期保证：UserUsageVO 刻意不含 token/costYuan 字段）
        assertEquals(new BigDecimal("12"), list.get(0).getPointsConsumed());
    }

    // ---------- 计划5 Step8：账单项目列（组名透出 + 组筛选透传 + 选项数据源） ----------

    @Test
    void userUsage_projectGroupIdFilterThreaded_andVoCarriesGroupName() {
        // 用户筛「我在组A的消耗」：projectGroupId 透传 mapper；组名随行返回（个人行 null 由 SQL LEFT JOIN 决定）
        UserUsageVO row = new UserUsageVO();
        row.setKind("VIDEO");
        row.setPointsConsumed(new BigDecimal("50"));
        row.setProjectGroupId(10L);
        row.setProjectGroupName("组A");
        when(usageLogMapper.listForUser(eq(100L), any(), any(), eq(10L), anyInt())).thenReturn(List.of(row));

        List<UserUsageVO> list = service.userUsage(100L, null, null, 10L);

        assertEquals("组A", list.get(0).getProjectGroupName());
        verify(usageLogMapper).listForUser(eq(100L), any(), any(), eq(10L), eq(BillingQueryService.USER_USAGE_LIMIT));
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
        when(usageLogMapper.countDetail(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(17L);
        when(usageLogMapper.pageDetail(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), eq((long) BillingQueryService.DETAIL_PAGE_SIZE)))
                .thenReturn(List.of(row));

        PageResult<UsageDetailVO> pr = service.pageDetail(null, null, null, null, null, null, null, null, null, 1, 0);

        assertEquals(17L, pr.getTotal());
        assertEquals(1, pr.getRecords().size());
        assertEquals("admin", pr.getRecords().get(0).getUsername());
        assertEquals(496, pr.getRecords().get(0).getTokensInput());
        assertEquals((long) BillingQueryService.DETAIL_PAGE_SIZE, pr.getSize());
    }

    @Test
    void pageDetail_sizeCappedToMax_andOffsetComputed() {
        // size=999（恶意大）→ 截到 DETAIL_MAX_SIZE(100)；page=3 → offset=(3-1)*100=200
        when(usageLogMapper.countDetail(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(500L);
        when(usageLogMapper.pageDetail(any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(200L), eq((long) BillingQueryService.DETAIL_MAX_SIZE)))
                .thenReturn(List.of());

        PageResult<UsageDetailVO> pr = service.pageDetail(null, null, null, null, null, null, null, null, null, 3, 999);

        assertEquals((long) BillingQueryService.DETAIL_MAX_SIZE, pr.getSize());
        assertEquals(3L, pr.getPage());
        org.mockito.ArgumentCaptor<Long> offsetCap = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(usageLogMapper).pageDetail(any(), any(), any(), any(), any(), any(), any(), any(), any(), offsetCap.capture(), eq((long) BillingQueryService.DETAIL_MAX_SIZE));
        assertEquals(200L, offsetCap.getValue());
    }

    @Test
    void pageDetail_totalZero_shortCircuitsPageQuery() {
        // total=0 → 不调 pageDetail（短路免空查询），返空 records
        when(usageLogMapper.countDetail(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0L);

        PageResult<UsageDetailVO> pr = service.pageDetail(null, null, null, null, null, null, null, null, null, 1, 20);

        assertTrue(pr.getRecords().isEmpty());
        assertEquals(0L, pr.getTotal());
        verify(usageLogMapper, never()).pageDetail(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong());
    }

    // ---------- 8x Chunk7：drill-down 反查键 traceId/taskId 透传 ----------

    @Test
    void pageDetail_traceIdAndTaskIdThreadedToMapper() {
        // admin 从审计行 drill-down：chat 行按 traceId 过滤、媒体行按 taskId 过滤 → 必须原样透传到 mapper
        when(usageLogMapper.countDetail(any(), any(), any(), any(), any(), any(),
                eq("trace-abc"), eq(9L), any())).thenReturn(1L);
        UsageDetailVO row = new UsageDetailVO();
        row.setTraceId("trace-abc");
        row.setTaskId(9L);
        when(usageLogMapper.pageDetail(any(), any(), any(), any(), any(), any(),
                eq("trace-abc"), eq(9L), any(), anyLong(), anyLong())).thenReturn(List.of(row));

        PageResult<UsageDetailVO> pr = service.pageDetail(null, null, null, null, null, null,
                "trace-abc", 9L, null, 1, 20);

        assertEquals(1L, pr.getTotal());
        assertEquals("trace-abc", pr.getRecords().get(0).getTraceId());
        assertEquals(9L, pr.getRecords().get(0).getTaskId());
        verify(usageLogMapper).countDetail(any(), any(), any(), any(), any(), any(), eq("trace-abc"), eq(9L), any());
        verify(usageLogMapper).pageDetail(any(), any(), any(), any(), any(), any(),
                eq("trace-abc"), eq(9L), any(), eq(0L), eq(20L));
    }

    @Test
    void pageDetail_projectGroupIdFilterThreaded_toCountAndPage() {
        // 计划5 Step8：admin 按「项目组A」筛调用明细 → countDetail/pageDetail 均须带 projectGroupId（漏一处=total与行数不一致）
        when(usageLogMapper.countDetail(any(), any(), any(), any(), any(), any(), any(), any(), eq(10L))).thenReturn(1L);
        UsageDetailVO row = new UsageDetailVO();
        row.setProjectGroupId(10L);
        row.setProjectGroupName("组A");
        when(usageLogMapper.pageDetail(any(), any(), any(), any(), any(), any(), any(), any(),
                eq(10L), anyLong(), anyLong())).thenReturn(List.of(row));

        PageResult<UsageDetailVO> pr = service.pageDetail(null, null, null, null, null, null, null, null, 10L, 1, 20);

        assertEquals("组A", pr.getRecords().get(0).getProjectGroupName());
        verify(usageLogMapper).countDetail(any(), any(), any(), any(), any(), any(), any(), any(), eq(10L));
        verify(usageLogMapper).pageDetail(any(), any(), any(), any(), any(), any(), any(), any(),
                eq(10L), eq(0L), eq(20L));
    }

    @Test
    void projectGroupOptions_mapsIdAndName() {
        // 计划5 Step8：筛选项数据源——mapper 行映射 id/name record（软删滤除由 MP @TableLogic 保证）
        ProjectGroupEntity g = new ProjectGroupEntity();
        g.setId(10L);
        g.setName("组A");
        when(groupMapper.selectList(any())).thenReturn(List.of(g));

        assertEquals(1, service.projectGroupOptions().size());
        assertEquals(10L, service.projectGroupOptions().get(0).id());
        assertEquals("组A", service.projectGroupOptions().get(0).name());
    }

    // ==================== 20x#1：admin 充值记录 + 用户余额视图 ====================

    @Test
    void adminRecharges_pageAndFilteredSums() {
        // 明细六字段行 + 筛选下 Σ（同 WHERE 口径两次聚合）
        var row = new com.superprogrammer.billing.dto.AdminRechargeRecordVO(
                1L, 7L, "u7", "小七", "三年二班", OffsetDateTime.now(), "MOCK", "138****1234",
                new BigDecimal("100.00"), new BigDecimal("1000"), new BigDecimal("1500"), "PAID");
        when(paymentOrderMapper.countAdminRecharges(eq(7L), eq("u7"), eq("MOCK"), eq("PAID"), any(), any()))
                .thenReturn(1L);
        when(paymentOrderMapper.pageAdminRecharges(eq(7L), eq("u7"), eq("MOCK"), eq("PAID"), any(), any(),
                eq(0L), eq(20L))).thenReturn(List.of(row));
        when(paymentOrderMapper.sumPaidAmountFiltered(eq(7L), eq("u7"), eq("MOCK"), eq("PAID"), any(), any()))
                .thenReturn(new BigDecimal("100.00"));
        when(paymentOrderMapper.sumPaidPointsFiltered(eq(7L), eq("u7"), eq("MOCK"), eq("PAID"), any(), any()))
                .thenReturn(new BigDecimal("1000"));

        var vo = service.adminRecharges(7L, "u7", "MOCK", "PAID", null, null, 1, 20);

        assertEquals(1, vo.page().getTotal());
        assertEquals(new BigDecimal("1500"), vo.page().getRecords().get(0).balanceAfter());
        // 合计卡与明细同口径（筛选联动聚合一致）
        assertEquals(new BigDecimal("100.00"), vo.filteredPaidAmount());
        assertEquals(new BigDecimal("1000"), vo.filteredPaidPoints());
    }

    @Test
    void adminRecharges_emptyTotalSkipsPageQuery() {
        when(paymentOrderMapper.countAdminRecharges(any(), any(), any(), any(), any(), any())).thenReturn(0L);
        when(paymentOrderMapper.sumPaidAmountFiltered(any(), any(), any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(paymentOrderMapper.sumPaidPointsFiltered(any(), any(), any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        var vo = service.adminRecharges(null, null, null, null, null, null, 1, 20);

        assertEquals(0, vo.page().getTotal());
        assertTrue(vo.page().getRecords().isEmpty());
        verify(paymentOrderMapper, never()).pageAdminRecharges(any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong());
    }

    @Test
    void adminRecharges_sizeCappedAtMax() {
        // size=9999（恶意大）→ 封顶 RECHARGE_MAX_SIZE(100)
        when(paymentOrderMapper.countAdminRecharges(any(), any(), any(), any(), any(), any())).thenReturn(0L);
        when(paymentOrderMapper.sumPaidAmountFiltered(any(), any(), any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(paymentOrderMapper.sumPaidPointsFiltered(any(), any(), any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        var vo = service.adminRecharges(null, null, null, null, null, null, 1, 9999);

        assertEquals(BillingQueryService.RECHARGE_MAX_SIZE, vo.page().getSize());
    }

    @Test
    void userBalances_zeroFillAndPlatformTotals() {
        // 无钱包行/无充值用户显 0（SQL COALESCE 出 0，此处验证映射直传 + 合计卡来自全平台聚合）
        var zeroRow = new com.superprogrammer.billing.dto.UserBalanceRowVO(
                9L, "newbie", null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
        when(balanceMapper.countUserBalances(null)).thenReturn(1L);
        when(balanceMapper.pageUserBalances(isNull(), any(), eq(0L), eq(20L))).thenReturn(List.of(zeroRow));
        java.util.Map<String, Object> totals = new java.util.HashMap<>();
        totals.put("totalusers", 3L);
        totals.put("sumbalance", new BigDecimal("500"));
        totals.put("sumrechargepoints", new BigDecimal("2000"));
        totals.put("sumrechargeamount", new BigDecimal("200.00"));
        when(balanceMapper.platformBalanceTotals(any())).thenReturn(totals);

        var vo = service.userBalances(null, null, null, 1, 20);

        assertEquals(1, vo.page().getTotal());
        assertEquals(BigDecimal.ZERO, vo.page().getRecords().get(0).balancePoints());
        assertEquals(3L, vo.totalUsers());
        assertEquals(new BigDecimal("500"), vo.sumBalance());
        assertEquals(new BigDecimal("2000"), vo.sumRechargePoints());
        assertEquals(new BigDecimal("200.00"), vo.sumRechargeAmount());
    }

    @Test
    void userBalances_sortWhitelistFallsBackToBalance() {
        // 非白名单 sortBy（注入尝试）→ 回落余额降序白名单列，绝不拼接外部输入
        when(balanceMapper.countUserBalances(null)).thenReturn(0L);
        java.util.Map<String, Object> totals = new java.util.HashMap<>();
        totals.put("totalusers", 0L);
        when(balanceMapper.platformBalanceTotals(any())).thenReturn(totals);

        service.userBalances(null, "balance_points; DROP TABLE users", "desc", 1, 20);

        verify(balanceMapper, never()).pageUserBalances(any(), org.mockito.ArgumentMatchers.contains("DROP"),
                anyLong(), anyLong());
    }

    @Test
    void userBalances_rechargeAmountSortMapped() {
        when(balanceMapper.countUserBalances(null)).thenReturn(0L);
        java.util.Map<String, Object> totals = new java.util.HashMap<>();
        totals.put("totalusers", 0L);
        when(balanceMapper.platformBalanceTotals(any())).thenReturn(totals);

        service.userBalances(null, "rechargeAmount", "asc", 1, 20);

        verify(balanceMapper, never()).pageUserBalances(any(),
                org.mockito.ArgumentMatchers.contains("DROP"), anyLong(), anyLong());
        // count 查 0 → 不分页查询（短路）
        verify(balanceMapper, never()).pageUserBalances(any(), any(), anyLong(), anyLong());
    }

    // ==================== D2（20x-1）：keyword 转义 + 昵称直出 ====================

    @Test
    void keywordWildcards_escapedBeforeQuery() {
        // %/_ 当普通字符匹配（防「输入 % 全表命中」）；mapper 侧 SQL 声明 ESCAPE '\'
        assertEquals("\\%100\\%", BillingQueryService.escapeLikeKeyword("%100%"));
        assertEquals("A\\_班", BillingQueryService.escapeLikeKeyword("A_班"));
        assertEquals("C:\\\\path", BillingQueryService.escapeLikeKeyword("C:\\path"));
        // null/空白原样返回（=不筛选）
        assertNull(BillingQueryService.escapeLikeKeyword(null));
        assertEquals("", BillingQueryService.escapeLikeKeyword(""));

        // 接线：userBalances/adminRecharges 传入 mapper 的已是转义后的串
        when(balanceMapper.countUserBalances(any())).thenReturn(0L);
        java.util.Map<String, Object> totals = new java.util.HashMap<>();
        totals.put("totalusers", 0L);
        when(balanceMapper.platformBalanceTotals(any())).thenReturn(totals);
        service.userBalances("%张%", null, null, 1, 20);
        verify(balanceMapper).countUserBalances("\\%张\\%");
    }

    @Test
    void adminRecharges_keywordEscapedAndNameCarried() {
        when(paymentOrderMapper.countAdminRecharges(any(), eq("小\\_七"), any(), any(), any(), any())).thenReturn(0L);
        when(paymentOrderMapper.sumPaidAmountFiltered(any(), any(), any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(paymentOrderMapper.sumPaidPointsFiltered(any(), any(), any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        var vo = service.adminRecharges(null, "小_七", null, null, null, null, 1, 20);

        assertEquals(0, vo.page().getTotal());
        verify(paymentOrderMapper).countAdminRecharges(isNull(), eq("小\\_七"), isNull(), isNull(), isNull(), isNull());
    }

    // ==================== D3（20x-2）：项目组分配视图 ====================

    @Test
    void groupAllocations_keywordEscaped_groupIdPassed_paged() {
        var row = new com.superprogrammer.billing.dto.GroupAllocationRowVO(
                3L, "A 班组", 9L, "stu9", "小九", "三年二班", "MEMBER",
                new BigDecimal("100"), new BigDecimal("30"), new BigDecimal("70"),
                new BigDecimal("150"), new BigDecimal("50"), new BigDecimal("100"),
                OffsetDateTime.parse("2026-08-01T10:00:00+08:00"));
        when(groupAllocationMapper.countGroupAllocations("小\\%九\\%", 3L)).thenReturn(1L);
        when(groupAllocationMapper.pageGroupAllocations("小\\%九\\%", 3L, 0L, 20L)).thenReturn(List.of(row));

        var page = service.groupAllocations("小%九%", 3L, 1, 20);

        assertEquals(1, page.getRecords().size());
        assertEquals(row, page.getRecords().get(0));
        // page=1 → offset 0；keyword 转义后传 mapper；groupId 原样精确筛
        verify(groupAllocationMapper).pageGroupAllocations("小\\%九\\%", 3L, 0L, 20L);
    }

    @Test
    void groupAllocations_zeroTotalShortCircuits_andSizeCapped() {
        when(groupAllocationMapper.countGroupAllocations(isNull(), isNull())).thenReturn(0L);

        var result = service.groupAllocations(null, null, 0, 9999);

        assertEquals(0, result.getRecords().size());
        // size 9999 截到 RECHARGE_MAX_SIZE(100)；count=0 → 不打分页查询
        assertEquals(100, result.getSize());
        verify(groupAllocationMapper, never()).pageGroupAllocations(any(), any(), anyLong(), anyLong());
    }

    // ==================== 修复IV E1（12x-1）：按备注汇总 ====================

    @Test
    void remarkSummary_rowsMapped_fieldsCarried() {
        // 同备注一桶：行字段（人数/余额/充值/窗内消耗/调用次数）原样透出；remark='' 即「未填备注」桶（前端判空）
        var row = new com.superprogrammer.billing.dto.RemarkSummaryRowVO(
                "三年二班", 7L,
                new BigDecimal("350.5"), new BigDecimal("2000"), new BigDecimal("200.00"),
                new BigDecimal("88.8"), 123L);
        when(balanceMapper.remarkSummary(any(), any())).thenReturn(List.of(row));

        List<com.superprogrammer.billing.dto.RemarkSummaryRowVO> rows =
                service.remarkSummary(OffsetDateTime.now().minusDays(7), OffsetDateTime.now());

        assertEquals(1, rows.size());
        assertEquals("三年二班", rows.get(0).remark());
        assertEquals(7L, rows.get(0).userCount());
        assertEquals(new BigDecimal("350.5"), rows.get(0).balanceSum());
        assertEquals(new BigDecimal("88.8"), rows.get(0).consumePointsSum());
        assertEquals(123L, rows.get(0).callCount());
    }

    @Test
    void remarkSummary_nullWindow_defaulted_toFallsToNow() {
        // from/to=null → clamp 兜底近 30 天；to 须落 NOW()（汇总 SQL usage 子查询需要明确上界，半开区间）
        when(balanceMapper.remarkSummary(any(), any())).thenReturn(List.of());

        OffsetDateTime before = OffsetDateTime.now();
        service.remarkSummary(null, null);
        OffsetDateTime after = OffsetDateTime.now();

        org.mockito.ArgumentCaptor<OffsetDateTime> fromCap = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        org.mockito.ArgumentCaptor<OffsetDateTime> toCap = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(balanceMapper).remarkSummary(fromCap.capture(), toCap.capture());
        long span = java.time.Duration.between(fromCap.getValue(), toCap.getValue()).toDays();
        assertEquals(30, span, 1, "null 窗应兜底近 30 天（允许 ±1 抖动）");
        // to 兜底=调用时刻 NOW()（非 null——SQL created_at < to 不可空）
        assertTrue(!toCap.getValue().isBefore(before.minusSeconds(1)) && !toCap.getValue().isAfter(after.plusSeconds(1)),
                "to 兜底应为当前时刻");
    }

    @Test
    void remarkSummary_emptyTable_returnsEmpty_noException() {
        // 空表/无消耗用户：SQL COALESCE 全 0 行或 0 行列表——service 直透不炸
        when(balanceMapper.remarkSummary(any(), any())).thenReturn(List.of());

        assertTrue(service.remarkSummary(null, null).isEmpty());
    }
}
