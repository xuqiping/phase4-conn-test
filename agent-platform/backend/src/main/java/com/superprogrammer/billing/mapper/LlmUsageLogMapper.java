package com.superprogrammer.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.billing.dto.DailyTrendVO;
import com.superprogrammer.billing.dto.UsageDimensionVO;
import com.superprogrammer.billing.dto.UsageOverviewVO;
import com.superprogrammer.billing.dto.UserUsageVO;
import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * LLM 调用审计日志 Mapper。
 * <p>异步攒批写走 {@link #batchInsert}（XML foreach）；
 * 聚合查询（Chunk I）走 GROUP BY 一次查，禁 per-user 循环（spec §坑点 N+1）。
 * <p>所有聚合按 {@code created_at BETWEEN #{from} AND #{to}} 过滤；service 层兜底默认窗 + 防超大区间。
 */
@Mapper
public interface LlmUsageLogMapper extends BaseMapper<LlmUsageLogEntity> {

    /**
     * 批量插入 usage 日志（UsageWriter 攒批 flush 调）。
     * <p>XML 实现见 resources/com/superprogrammer/billing/mapper/xml/LlmUsageLogMapper.xml。
     */
    void batchInsert(@Param("rows") List<LlmUsageLogEntity> rows);

    // ---------- Chunk I 聚合查询（admin / user） ----------

    /** 期内总量（token/¥/积分/次数）。COALESCE 保证无数据返 0 非 null。 */
    UsageOverviewVO sumTotals(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** 按用户排行（dimension_key=user_id text）。 */
    List<UsageDimensionVO> groupByUser(@Param("from") OffsetDateTime from,
                                       @Param("to") OffsetDateTime to,
                                       @Param("limit") int limit);

    /** 按模型排行（dimension_key=model）。 */
    List<UsageDimensionVO> groupByModel(@Param("from") OffsetDateTime from,
                                        @Param("to") OffsetDateTime to,
                                        @Param("limit") int limit);

    /** 按 kind 排行（dimension_key=CHAT/EMBED/IMAGE/VIDEO）。 */
    List<UsageDimensionVO> groupByKind(@Param("from") OffsetDateTime from,
                                       @Param("to") OffsetDateTime to);

    /** 按日趋势（趋势折线用）。 */
    List<DailyTrendVO> dailyTrend(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /**
     * 用户积分明细（{@code /me/usage}）。<b>不含 token/cost_yuan</b>（SELECT 列刻意省略，spec §3 用户侧不暴露）。
     * ownership 由 service 强制传 current userId，SQL 不接受外部 userId 旁路。
     */
    List<UserUsageVO> listForUser(@Param("userId") Long userId,
                                  @Param("from") OffsetDateTime from,
                                  @Param("to") OffsetDateTime to,
                                  @Param("limit") int limit);
}
