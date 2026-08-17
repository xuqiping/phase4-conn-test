package com.superprogrammer.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.billing.dto.DailyTrendVO;
import com.superprogrammer.billing.dto.UsageDetailVO;
import com.superprogrammer.billing.dto.UsageDimensionVO;
import com.superprogrammer.billing.dto.UsageOverviewVO;
import com.superprogrammer.billing.dto.UserUsageVO;
import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
     * <p>计划5 Step8：+projectGroupName（LEFT JOIN project_groups，个人行 null）；
     * {@code projectGroupId} 可空=全部，非空=只看该组内我的消耗行。
     */
    List<UserUsageVO> listForUser(@Param("userId") Long userId,
                                  @Param("from") OffsetDateTime from,
                                  @Param("to") OffsetDateTime to,
                                  @Param("projectGroupId") Long projectGroupId,
                                  @Param("limit") int limit);

    // ---------- admin 调用明细（逐条 llm_usage_logs，含 token/¥/积分；LEFT JOIN users 取用户名） ----------

    /** 明细总数（与 {@link #pageDetail} 同筛选条件，供分页 total）。不 join，轻量。 */
    long countDetail(@Param("from") OffsetDateTime from,
                     @Param("to") OffsetDateTime to,
                     @Param("userId") Long userId,
                     @Param("model") String model,
                     @Param("kind") String kind,
                     @Param("status") String status,
                     @Param("traceId") String traceId,
                     @Param("taskId") Long taskId,
                     @Param("projectGroupId") Long projectGroupId);

    /**
     * 逐条明细分页（含 username/displayName via LEFT JOIN users）。user_id 可空（系统调用）→ LEFT JOIN 不丢行。
     * <p>offset/size 由 service 算好（{@code (page-1)*size}）；按 created_at 倒序（最新在前）。
     * <p>8x Chunk7：{@code traceId}(chat 关联)/{@code taskId}(媒体关联) 为 drill-down 反查键，非空时精确过滤。
     * <p>计划5 Step8：+projectGroupName（LEFT JOIN project_groups）；{@code projectGroupId} 非空时精确过滤组池消耗行。
     */
    List<UsageDetailVO> pageDetail(@Param("from") OffsetDateTime from,
                                   @Param("to") OffsetDateTime to,
                                   @Param("userId") Long userId,
                                   @Param("model") String model,
                                   @Param("kind") String kind,
                                   @Param("status") String status,
                                   @Param("traceId") String traceId,
                                   @Param("taskId") Long taskId,
                                   @Param("projectGroupId") Long projectGroupId,
                                   @Param("offset") long offset,
                                   @Param("size") long size);

    @Select("SELECT * FROM llm_usage_logs WHERE trace_id=#{traceId} ORDER BY created_at")
    List<LlmUsageLogEntity> findByTraceId(String traceId);

    @Select("SELECT trace_id FROM llm_usage_logs WHERE id=#{id}")
    String findTraceIdById(Long id);

    /**
     * 安全体系 S3 · SEC-FR-056：会话累计 token（input+output）。
     * 命中 V122 partial index（WHERE session_id IS NOT NULL）；COALESCE 保证无行返 0。
     */
    @Select("SELECT COALESCE(SUM(tokens_input + tokens_output), 0) FROM llm_usage_logs"
            + " WHERE session_id=#{sessionId}")
    long sumTokensBySession(@Param("sessionId") String sessionId);
}
