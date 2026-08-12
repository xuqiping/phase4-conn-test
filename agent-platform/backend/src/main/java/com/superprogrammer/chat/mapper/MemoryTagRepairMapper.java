package com.superprogrammer.chat.mapper;

import com.superprogrammer.chat.entity.MemoryTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * V77 大类重映射/孤儿锚点回填的数据出口（管理员一次性 repair 工具，非用户路径）。
 * <p>
 * 跨 6 表 tag_id 重指 + 孤儿 anchor 重生 + topic 改大类。{@code memory_tags.tag_id} 是
 * summaries/turns/entries/coverage×2/conflicts 的关联键，归并 = loser 引用全重指 survivor 后软删。
 * <b>仅 {@link com.superprogrammer.chat.service.internal.MemoryTagRepairService} 调用</b>，dry-run 可审，单事务包裹。
 *
 * <table border=1>
 * <tr><th>表</th><th>列</th><th>UNIQUE 冲突</th></tr>
 * <tr><td>memory_summaries</td><td>tag_id</td><td>无（仅索引）→ 直接 UPDATE</td></tr>
 * <tr><td>memory_conflicts</td><td>tag_id</td><td>无 → 直接 UPDATE</td></tr>
 * <tr><td>memory_turns</td><td>tag_ids[]</td><td>无 → array_replace</td></tr>
 * <tr><td>memory_project_entries</td><td>tag_ids[]</td><td>无 → array_replace</td></tr>
 * <tr><td>memory_entry_coverage</td><td>tag_id</td><td>UNIQUE(entry_id,project_id,tag_id,user_id) → 删冲突行后 UPDATE</td></tr>
 * <tr><td>memory_summary_coverage</td><td>tag_id</td><td>UNIQUE(turn_id,tag_id,project_id,user_id) → 删冲突行后 UPDATE</td></tr>
 * </table>
 */
@Mapper
public interface MemoryTagRepairMapper {

    /** 孤儿标签：anchor_embedding IS NULL（embedding 404 期间生成，路径③/路由粗筛永远跳过）。 */
    List<MemoryTag> findNullAnchorTags();

    /** 改 topic 为大类 + 重生 anchor（halfvec/tokens null 时保留旧值）+ 清 needs_review。 */
    int updateTopicAndAnchor(@Param("id") Long id,
                             @Param("topic") String topic,
                             @Param("anchorHalfvec") String anchorHalfvec,
                             @Param("anchorTokens") String anchorTokens);

    /** 软删 loser 标签（引用已重指 survivor）。 */
    int softDeleteTag(@Param("id") Long id);

    /** 合并 loser 别名到 survivor（去重）。 */
    int mergeAliases(@Param("survivorId") Long survivorId, @Param("loserId") Long loserId);

    // ---- scalar tag_id 重指（无 UNIQUE 冲突，直接 UPDATE）----

    int reassignSummariesTagId(@Param("loser") Long loser, @Param("survivor") Long survivor);

    int reassignConflictTagId(@Param("loser") Long loser, @Param("survivor") Long survivor);

    // ---- array tag_ids 重指（array_replace）----

    int reassignTurnsTagIds(@Param("loser") Long loser, @Param("survivor") Long survivor);

    int reassignEntriesTagIds(@Param("loser") Long loser, @Param("survivor") Long survivor);

    // ---- 带 UNIQUE 冲突的 coverage 重指（先删 loser 与 survivor 冲突行，再 UPDATE 剩余）----

    int reassignEntryCoverageTagId(@Param("loser") Long loser, @Param("survivor") Long survivor);

    int reassignSummaryCoverageTagId(@Param("loser") Long loser, @Param("survivor") Long survivor);
}
