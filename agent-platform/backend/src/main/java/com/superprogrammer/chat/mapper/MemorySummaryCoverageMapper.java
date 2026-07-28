package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemorySummaryCoverage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 记忆覆盖表 mapper（V47 计划12）。
 * 召回恒只认 user_id=召回者自己的行（allCovered 判定依据，向量 1）。
 */
@Mapper
public interface MemorySummaryCoverageMapper extends BaseMapper<MemorySummaryCoverage> {

    /** 作者在给定 turn 集上的覆盖行。防 N+1：批量 IN 一次取。 */
    List<MemorySummaryCoverage> findByUserAndTurns(@Param("userId") Long userId,
                                                   @Param("turnIds") List<Long> turnIds);

    // ============================ 计划12 · E 总结写入 + 级联清 ============================

    /** E-3 批量写 coverage（总结吃进的 turn×tag 行）。UNIQUE 冲突（NULLS NOT DISTINCT）→ DO NOTHING 幂等。 */
    int batchInsert(@Param("rows") List<MemorySummaryCoverage> rows);

    /** E-4 级联清：删某 summary 的全部 coverage 行（KEEP_NEW/OLD/DISCARD 软删 summary 时）。 */
    int deleteBySummaryId(@Param("summaryId") Long summaryId);

    /** E-4 §3.8 级联：删 turn 集的 coverage 行（turn 软删 / DISCARD 连带软删 source turns 时，
     *  按 (turn_id, user_id=作者) 清——他人对该 turn 的 coverage 不动，仅清被软删 turn 作者侧）。 */
    int deleteByTurnIdsAndUser(@Param("turnIds") List<Long> turnIds,
                               @Param("userId") Long userId);
}
