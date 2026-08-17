package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryTurn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记忆流水账 mapper（V47 计划12；V67 二期 P1 纯个人域化）。
 * 二期 P1 定案（FR-006）：turns 纯个人域——所有取数恒 {@code user_id=self}，
 * 项目挂载/出身/离职/已删项目四列已随 V67 DROP；项目记忆取数走
 * {@link MemoryProjectEntryMapper}（收录条目，原文不出个人域）。
 */
@Mapper
public interface MemoryTurnMapper extends BaseMapper<MemoryTurn> {

    // ============================ 计划12 · D-5 拼流水账 ⑥ ============================

    /** 个人可召回流水账：本人 gen_done=true（raw gen_done=false 不参与）。
     *  direction/timeWindow 过滤 created_at。 */
    List<MemoryTurn> findPersonalRecallableTurns(@Param("userId") Long userId,
                                                 @Param("direction") String direction,
                                                 @Param("twStart") OffsetDateTime twStart,
                                                 @Param("twEnd") OffsetDateTime twEnd,
                                                 @Param("relativeDays") Integer relativeDays);

    // ============================ 计划12 · E 总结取数 + backfill + DISCARD 级联 ============================

    /** E-3 个人总结取数：本人 gen_done=true AND tag_ids 含任一 tagId。
     *  取全量（service 查 coverage 判未覆盖，决定压缩范围）；direction/timeWindow 过滤 created_at。 */
    List<MemoryTurn> findPersonalTurnsForConsolidation(@Param("userId") Long userId,
                                                       @Param("tagIds") List<Long> tagIds,
                                                       @Param("direction") String direction,
                                                       @Param("twStart") OffsetDateTime twStart,
                                                       @Param("twEnd") OffsetDateTime twEnd,
                                                       @Param("relativeDays") Integer relativeDays);

    /** E-3 STALE 重生取数：按 id 集回读未软删 turn（source_turn_ids - 已删 → 剩余 turns 重压缩）。 */
    List<MemoryTurn> findTurnsByIds(@Param("turnIds") List<Long> turnIds);

    /** E-2 backfill 取数：本人 gen_done=false 的 raw turn，分批（LIMIT ≤20/批）。 */
    List<MemoryTurn> findRawTurnsForBackfill(@Param("userId") Long userId,
                                             @Param("limit") int limit);

    /** E-2 backfill 写回：补 tag + L1/L2 + gen_done=true（单条，显式 typeHandler 绕 LambdaUpdateWrapper 坑）。 */
    int applyBackfill(@Param("id") Long id,
                      @Param("tagIds") List<Long> tagIds,
                      @Param("l1Summary") String l1Summary,
                      @Param("l2Detail") String l2Detail,
                      @Param("updatedBy") Long updatedBy);

    /** E-4 DISCARD 连带软删 source turns（按 id 集软删 deleted=1，显式版供级联批量用，返实删条数）。 */
    int softDeleteByIds(@Param("turnIds") List<Long> turnIds);

    // ============================ 计划12 · E-7 总结入口（未覆盖计数 §3.9 告警）============================

    /** E-7 个人 scope 未总结 turn 计数：gen_done=true AND 无 coverage(user_id=self) 行。
     *  设计 §3.9 line178：未总结 = gen_done=true 且无 coverage；raw(gen_done=false) 不计告警阈值。 */
    int countUncoveredPersonalTurns(@Param("userId") Long userId);

    /** E-7 个人 scope raw turn 计数（gen_done=false，hasChange 判据 + 遗忘权面板）。 */
    int countRawPersonalTurns(@Param("userId") Long userId);

    // ============================ 5x 四轮 C8 · 重新归类标签（U7）============================

    /** 重新归类候选：本人未挂目标标签的 turn（gen_done 不限——raw 行也有资格挂标签），
     *  created_at 升序（老记忆优先补），LIMIT 上限由 service 硬卡。 */
    List<MemoryTurn> findReclassifyCandidates(@Param("userId") Long userId,
                                              @Param("tagId") Long tagId,
                                              @Param("olderThanCreatedAt") OffsetDateTime olderThanCreatedAt,
                                              @Param("start") OffsetDateTime start,
                                              @Param("end") OffsetDateTime end,
                                              @Param("limit") int limit);

    /** tag_ids 原子增补目标标签（array_append + 防重条件，幂等；只增不删铁律）。返影响行数。 */
    int appendTagId(@Param("id") Long id, @Param("tagId") Long tagId);
}
