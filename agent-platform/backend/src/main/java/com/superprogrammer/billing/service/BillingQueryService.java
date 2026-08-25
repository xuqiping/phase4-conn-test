package com.superprogrammer.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.billing.dto.DailyTrendVO;
import com.superprogrammer.billing.dto.LedgerItemVO;
import com.superprogrammer.billing.dto.ProjectGroupOptionVO;
import com.superprogrammer.billing.dto.UsageDetailVO;
import com.superprogrammer.billing.dto.UsageDimensionVO;
import com.superprogrammer.billing.dto.UsageOverviewVO;
import com.superprogrammer.billing.dto.UserUsageVO;
import com.superprogrammer.billing.dto.UserWalletVO;
import com.superprogrammer.billing.entity.PointsLedgerEntity;
import com.superprogrammer.billing.mapper.LlmUsageLogMapper;
import com.superprogrammer.billing.mapper.PointsLedgerMapper;
import com.superprogrammer.projectgroup.entity.ProjectGroupEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMapper;
import com.superprogrammer.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 账单/钱包查询服务（Chunk I，spec §6 查询 API）。
 *
 * <p>聚合全走 {@link LlmUsageLogMapper} 的 GROUP BY 一次查（禁 per-user 循环，spec §坑点 N+1）。
 *
 * <p>分权（spec §安全 鉴权 + 数据最小化）：
 * <ul>
 *   <li>admin 端：见真 token/¥/积分（{@link UsageOverviewVO}/{@link UsageDimensionVO} 含 token+cost）。</li>
 *   <li>user 端：ownership 强制按 current userId 过滤（{@link #userWallet}/{@link #userUsage} 由 controller 传
 *       SecurityContext 取出的 userId，SQL 不接受外部 userId 旁路）；VO 刻意不含 token/¥（spec §3）。</li>
 * </ul>
 *
 * <p>日期区间：null 兜底默认窗（近 30 天）；超 {@link #MAX_DAYS} 自动 clamp（防超大区间拖垮聚合查询，
 * spec §安全 输入校验）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingQueryService {

    /** 默认查询窗（from/to 为 null 时）。 */
    static final int DEFAULT_DAYS = 30;
    /** 单次查询最大跨度，超此自动 clamp（防超大区间拖垮聚合）。 */
    static final int MAX_DAYS = 365;
    /** 排行榜默认/上限条数。 */
    static final int RANK_LIMIT = 20;
    /** 用户钱包最近流水条数。 */
    static final int WALLET_LEDGER_LIMIT = 50;
    /** 用户积分明细上限。 */
    static final int USER_USAGE_LIMIT = 200;
    /** 调用明细默认每页条数。 */
    static final int DETAIL_PAGE_SIZE = 20;
    /** 调用明细每页上限（防恶意大 size 拖垮 DB）。 */
    static final int DETAIL_MAX_SIZE = 100;

    private final LlmUsageLogMapper usageLogMapper;
    private final PointsLedgerMapper ledgerMapper;
    private final PointsWalletService walletService;
    /** 计划5 Step8：账单页项目组筛选项数据源（跨模块只读）。 */
    private final ProjectGroupMapper groupMapper;
    /** 20x#1：admin 充值记录。 */
    private final com.superprogrammer.billing.mapper.PaymentOrderMapper paymentOrderMapper;
    /** 20x#1：admin 用户余额视图。 */
    private final com.superprogrammer.billing.mapper.UserPointsBalanceMapper balanceMapper;
    /** D3（20x-2）：admin 项目组分配视图。 */
    private final com.superprogrammer.billing.mapper.GroupAllocationMapper groupAllocationMapper;

    // ---------- admin ----------

    public UsageOverviewVO overview(OffsetDateTime from, OffsetDateTime to) {
        Window w = clamp(from, to);
        return usageLogMapper.sumTotals(w.from, w.to);
    }

    public List<UsageDimensionVO> rankByUser(OffsetDateTime from, OffsetDateTime to, Integer limit) {
        Window w = clamp(from, to);
        return usageLogMapper.groupByUser(w.from, w.to, cap(limit));
    }

    public List<UsageDimensionVO> rankByModel(OffsetDateTime from, OffsetDateTime to, Integer limit) {
        Window w = clamp(from, to);
        return usageLogMapper.groupByModel(w.from, w.to, cap(limit));
    }

    public List<UsageDimensionVO> rankByKind(OffsetDateTime from, OffsetDateTime to) {
        Window w = clamp(from, to);
        return usageLogMapper.groupByKind(w.from, w.to);
    }

    public List<DailyTrendVO> dailyTrend(OffsetDateTime from, OffsetDateTime to) {
        Window w = clamp(from, to);
        return usageLogMapper.dailyTrend(w.from, w.to);
    }

    /**
     * admin 调用明细分页（逐条 llm_usage_logs，含 token/¥/积分 + username via JOIN）。
     * <p>复用 {@link #clamp} 兜底窗；size 缺省 {@link #DETAIL_PAGE_SIZE}、封顶 {@link #DETAIL_MAX_SIZE}；
     * total==0 短路免一次空分页查询。
     * <p>计划5 Step8：{@code projectGroupId} 非空=只看该组池消耗行；行带组名（个人行 null）。
     */
    public PageResult<UsageDetailVO> pageDetail(OffsetDateTime from, OffsetDateTime to,
                                                Long userId, String model, String kind, String status,
                                                String traceId, Long taskId, Long projectGroupId,
                                                long page, long size) {
        Window w = clamp(from, to);
        long sz = size <= 0 ? DETAIL_PAGE_SIZE : Math.min(size, DETAIL_MAX_SIZE);
        long pg = Math.max(page, 1);
        long total = usageLogMapper.countDetail(w.from(), w.to(), userId, model, kind, status, traceId, taskId,
                projectGroupId);
        List<UsageDetailVO> records = total == 0
                ? List.of()
                : usageLogMapper.pageDetail(w.from(), w.to(), userId, model, kind, status, traceId, taskId,
                        projectGroupId, (pg - 1) * sz, sz);
        return PageResult.of(records, total, pg, sz);
    }

    /**
     * 计划5 Step8：admin 账单页「项目组」筛选下拉数据源（id+name）。
     * MP @TableLogic 自动滤软删组——软删组的账单行仍带组名显示，只是不可再从下拉筛（边缘，可接受）。
     * 仅供 usage:view 持有者（账单页侧），与项目组管理端点分离。
     * <p>用 QueryWrapper 列名字符串（非 Lambda select）——单测无 MP TableInfo 缓存也能跑。
     */
    public List<ProjectGroupOptionVO> projectGroupOptions() {
        return groupMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ProjectGroupEntity>()
                        .select("id", "name")
                        .orderByAsc("id"))
                .stream()
                .map(g -> new ProjectGroupOptionVO(g.getId(), g.getName()))
                .toList();
    }

    // ---------- admin 充值/余额（20x#1） ----------

    /** admin 充值记录默认每页。 */
    static final int RECHARGE_PAGE_SIZE = 20;
    /** admin 充值记录每页上限。 */
    static final int RECHARGE_MAX_SIZE = 100;

    /**
     * admin 充值记录分页 + 当前筛选下 Σ（仅 PAID 计入，与明细同 WHERE 口径）。
     * 行=六字段（时间/渠道/付款账号/金额/积分/充值后余额）+ userId/username/name（D2）；
     * 未入账状态（PENDING/FAILED/CLOSED）balanceAfter=null。
     */
    public com.superprogrammer.billing.dto.AdminRechargePageVO adminRecharges(
            Long userId, String keyword, String channel, String status,
            OffsetDateTime from, OffsetDateTime to, long page, long size) {
        long sz = size <= 0 ? RECHARGE_PAGE_SIZE : Math.min(size, RECHARGE_MAX_SIZE);
        long pg = Math.max(page, 1);
        String kw = escapeLikeKeyword(keyword);
        long total = paymentOrderMapper.countAdminRecharges(userId, kw, channel, status, from, to);
        List<com.superprogrammer.billing.dto.AdminRechargeRecordVO> records = total == 0
                ? List.of()
                : paymentOrderMapper.pageAdminRecharges(userId, kw, channel, status, from, to,
                        (pg - 1) * sz, sz);
        return new com.superprogrammer.billing.dto.AdminRechargePageVO(
                PageResult.of(records, total, pg, sz),
                paymentOrderMapper.sumPaidAmountFiltered(userId, kw, channel, status, from, to),
                paymentOrderMapper.sumPaidPointsFiltered(userId, kw, channel, status, from, to));
    }

    /**
     * admin 用户余额视图分页 + 合计卡（7x 反馈：合计卡跟随 keyword 筛选——筛选谁合计谁，未筛选=全平台）。
     * D2（20x-1）：keyword 匹配 username/name 任一；行带 name（昵称/姓名）。
     * 排序白名单（防注入）：balance（余额）/rechargePoints（累计充值积分）/rechargeAmount（累计充值金额），
     * 缺省按余额降序；方向仅 asc/desc。
     */
    public com.superprogrammer.billing.dto.UserBalancePageVO userBalances(
            String keyword, String sortBy, String order, long page, long size) {
        long sz = size <= 0 ? RECHARGE_PAGE_SIZE : Math.min(size, RECHARGE_MAX_SIZE);
        long pg = Math.max(page, 1);
        String orderClause = balanceOrderClause(sortBy, order);
        String kw = escapeLikeKeyword(keyword);
        long total = balanceMapper.countUserBalances(kw);
        List<com.superprogrammer.billing.dto.UserBalanceRowVO> records = total == 0
                ? List.of()
                : balanceMapper.pageUserBalances(kw, orderClause, (pg - 1) * sz, sz);
        java.util.Map<String, Object> t = balanceMapper.platformBalanceTotals(kw);
        return new com.superprogrammer.billing.dto.UserBalancePageVO(
                PageResult.of(records, total, pg, sz),
                toLong(t.get("totalusers")),
                toBd(t.get("sumbalance")),
                toBd(t.get("sumrechargepoints")),
                toBd(t.get("sumrechargeamount")));
    }

    /**
     * D3（20x-2）：admin 项目组分配视图分页。
     * 行=成员活行（quota/used/剩余快照）+ ledger MEMBER_* 聚合（累计被分配/收回/净额/最近分配时间）。
     * keyword 匹配 username/name（D2 同款转义）；groupId 精确筛选；排序固定 组→用户。
     */
    public com.superprogrammer.common.result.PageResult<com.superprogrammer.billing.dto.GroupAllocationRowVO> groupAllocations(
            String keyword, Long groupId, long page, long size) {
        long sz = size <= 0 ? RECHARGE_PAGE_SIZE : Math.min(size, RECHARGE_MAX_SIZE);
        long pg = Math.max(page, 1);
        String kw = escapeLikeKeyword(keyword);
        long total = groupAllocationMapper.countGroupAllocations(kw, groupId);
        List<com.superprogrammer.billing.dto.GroupAllocationRowVO> records = total == 0
                ? List.of()
                : groupAllocationMapper.pageGroupAllocations(kw, groupId, (pg - 1) * sz, sz);
        return PageResult.of(records, total, pg, sz);
    }

    /** 余额视图排序白名单映射（仅允许三列 + asc/desc，其余回落默认）。 */
    private static String balanceOrderClause(String sortBy, String order) {
        String col = switch (sortBy == null ? "" : sortBy) {
            case "rechargePoints" -> "COALESCE(r.totalPoints, 0)";
            case "rechargeAmount" -> "COALESCE(r.totalAmount, 0)";
            default -> "COALESCE(b.balance_points, 0)";
        };
        String dir = "asc".equalsIgnoreCase(order) ? "ASC" : "DESC";
        return "ORDER BY " + col + " " + dir + ", u.id ASC";
    }

    /**
     * LIKE keyword 前置转义（D 坑点表）：`\` `%` `_` → `\x`，mapper 侧统一声明 {@code ESCAPE '\'}。
     * 防用户输入 %/_ 当通配符全表命中；null/空白原样返回（=不筛选）。
     */
    static String escapeLikeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return keyword;
        }
        return keyword.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static java.math.BigDecimal toBd(Object o) {
        return o instanceof java.math.BigDecimal b ? b : java.math.BigDecimal.ZERO;
    }

    // ---------- user（ownership 由 controller 传 current userId，无外部旁路） ----------

    /** 用户钱包：余额 + 最近流水（仅积分维度，不返 ¥/token）。 */
    public UserWalletVO userWallet(Long userId) {
        UserWalletVO vo = new UserWalletVO();
        vo.setBalance(walletService.getBalance(userId));
        vo.setDebtPoints(walletService.getDebt(userId));
        LambdaQueryWrapper<PointsLedgerEntity> w = new LambdaQueryWrapper<PointsLedgerEntity>()
                .eq(PointsLedgerEntity::getUserId, userId)
                .orderByDesc(PointsLedgerEntity::getCreatedAt)
                .last("LIMIT " + WALLET_LEDGER_LIMIT);
        List<PointsLedgerEntity> rows = ledgerMapper.selectList(w);
        vo.setRecentLedger(rows.stream().map(BillingQueryService::toLedgerItem).toList());
        return vo;
    }

    /** 用户积分明细（不含 token/¥，按 createdAt 倒序）。计划5 Step8：+组名列；组筛选可空=全部（个人+组池行）。 */
    public List<UserUsageVO> userUsage(Long userId, OffsetDateTime from, OffsetDateTime to, Long projectGroupId) {
        Window w = clamp(from, to);
        return usageLogMapper.listForUser(userId, w.from, w.to, projectGroupId, USER_USAGE_LIMIT);
    }

    // ---------- helpers ----------

    private static LedgerItemVO toLedgerItem(PointsLedgerEntity e) {
        LedgerItemVO item = new LedgerItemVO();
        item.setCreatedAt(e.getCreatedAt());
        item.setType(e.getType());
        item.setDeltaPoints(e.getDeltaPoints());
        item.setBalanceAfter(e.getBalanceAfter());
        item.setRemark(e.getRemark());
        return item;
    }

    /** 排行条数封顶（防恶意大 limit 拖垮 DB）。null/非正用默认，超 RANK_LIMIT 截到 RANK_LIMIT。 */
    private static int cap(Integer limit) {
        if (limit == null || limit <= 0) return RANK_LIMIT;
        return Math.min(limit, RANK_LIMIT);
    }

    /**
     * 规范化查询窗：null 兜底默认窗；跨度超 {@link #MAX_DAYS} clamp 到最近 MAX_DAYS 天。
     * 返回的 from/to 直接喂聚合 SQL（均可空→SQL 内 &lt;where&gt; 处理）。
     */
    private Window clamp(OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime end = to != null ? to : OffsetDateTime.now();
        OffsetDateTime start = from;
        if (start == null) {
            start = end.minusDays(DEFAULT_DAYS);
        }
        long spanDays = java.time.Duration.between(start, end).toDays();
        if (spanDays > MAX_DAYS) {
            log.info("账单查询区间超 {} 天，自动 clamp（原跨度 {} 天）", MAX_DAYS, spanDays);
            start = end.minusDays(MAX_DAYS);
        }
        return new Window(start, to == null ? null : end);
    }

    /** 仅 to=null 时不限上界（查到最新），有 to 时带上界（半开区间 created_at &lt; to）。 */
    private record Window(OffsetDateTime from, OffsetDateTime to) {}
}
