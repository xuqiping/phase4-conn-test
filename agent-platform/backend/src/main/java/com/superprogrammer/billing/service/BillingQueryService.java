package com.superprogrammer.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.billing.dto.DailyTrendVO;
import com.superprogrammer.billing.dto.LedgerItemVO;
import com.superprogrammer.billing.dto.UsageDimensionVO;
import com.superprogrammer.billing.dto.UsageOverviewVO;
import com.superprogrammer.billing.dto.UserUsageVO;
import com.superprogrammer.billing.dto.UserWalletVO;
import com.superprogrammer.billing.entity.PointsLedgerEntity;
import com.superprogrammer.billing.mapper.LlmUsageLogMapper;
import com.superprogrammer.billing.mapper.PointsLedgerMapper;
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

    private final LlmUsageLogMapper usageLogMapper;
    private final PointsLedgerMapper ledgerMapper;
    private final PointsWalletService walletService;

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

    // ---------- user（ownership 由 controller 传 current userId，无外部旁路） ----------

    /** 用户钱包：余额 + 最近流水（仅积分维度，不返 ¥/token）。 */
    public UserWalletVO userWallet(Long userId) {
        UserWalletVO vo = new UserWalletVO();
        vo.setBalance(walletService.getBalance(userId));
        LambdaQueryWrapper<PointsLedgerEntity> w = new LambdaQueryWrapper<PointsLedgerEntity>()
                .eq(PointsLedgerEntity::getUserId, userId)
                .orderByDesc(PointsLedgerEntity::getCreatedAt)
                .last("LIMIT " + WALLET_LEDGER_LIMIT);
        List<PointsLedgerEntity> rows = ledgerMapper.selectList(w);
        vo.setRecentLedger(rows.stream().map(BillingQueryService::toLedgerItem).toList());
        return vo;
    }

    /** 用户积分明细（不含 token/¥，按 createdAt 倒序）。 */
    public List<UserUsageVO> userUsage(Long userId, OffsetDateTime from, OffsetDateTime to) {
        Window w = clamp(from, to);
        return usageLogMapper.listForUser(userId, w.from, w.to, USER_USAGE_LIMIT);
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
