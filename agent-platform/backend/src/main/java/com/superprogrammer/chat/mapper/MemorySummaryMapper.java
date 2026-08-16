package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemorySummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记忆总结 mapper（V47 计划12）。
 * 总结恒只读自己（user_id=self，他人总结不可见防污染）；project_id 单值 scope（NULL=个人）。
 */
@Mapper
public interface MemorySummaryMapper extends BaseMapper<MemorySummary> {

    /** 作者在某 scope 的总结。projectId=null → 个人(project_id IS NULL)。 */
    List<MemorySummary> findByUserAndScope(@Param("userId") Long userId,
                                           @Param("projectId") Long projectId);

    /** 计划12 · D-4 · 召回读总结：user_id=self 恒只读自己（向量14 不受 ACL），
     *  tag_id IN T + scope(personalOn 个人 OR projectIds 项目) + status≠STALE + timeWindow(summarized_at)。 */
    List<MemorySummary> findSummariesForRecall(@Param("userId") Long userId,
                                               @Param("tagIds") List<Long> tagIds,
                                               @Param("projectIds") List<Long> projectIds,
                                               @Param("personalOn") boolean personalOn,
                                               @Param("twStart") OffsetDateTime twStart,
                                               @Param("twEnd") OffsetDateTime twEnd,
                                               @Param("relativeDays") Integer relativeDays);

    // ============================ 计划12 · E 总结层 + DISCARD 级联 ============================

    /** E-3/E-4 同 (user, tag, scope) 的 CLEAN 总结（时序互斥冲突判定用， projectId=null→个人）。
     *  返全部 CLEAN（service 喂 judge 判并存/互斥）；PENDING_CONFLICT 不再触发新冲突（已挂起待裁）。
     *  5x #4：direction 非空 → 仅同向/双向总结参与冲突判定（INPUT/OUTPUT 侧面互不撞车）；null 不过滤。 */
    List<MemorySummary> findCleanByUserTagScope(@Param("userId") Long userId,
                                                @Param("tagId") Long tagId,
                                                @Param("projectId") Long projectId,
                                                @Param("direction") String direction);

    /** E-4 同 (user, tag, scope) 指定 status 的总结（裁决时找 PENDING_CONFLICT 的「另一方」）。 */
    List<MemorySummary> findByUserTagScopeStatus(@Param("userId") Long userId,
                                                 @Param("tagId") Long tagId,
                                                 @Param("projectId") Long projectId,
                                                 @Param("status") String status);

    /** E-3 防膨胀：同 (user, tag, scope) 已存总结条数（>阈值 N 再压缩一次，链缩短）。 */
    int countByUserTagScope(@Param("userId") Long userId,
                            @Param("tagId") Long tagId,
                            @Param("projectId") Long projectId);

    /** E-3/E-4 定向改 status（PENDING_CONFLICT / STALE / CLEAN），防 updateById 覆盖 source_turn_ids。 */
    int markStatus(@Param("id") Long id, @Param("status") String status);

    /** E-6 worker STALE 重生取数：本人 status=STALE 总结（重压缩后清 STALE）。 */
    List<MemorySummary> findStaleByUser(@Param("userId") Long userId);

    /** E-4 §3.8 级联 / 12h 查：source_turn_ids @> [turnId] 的全部总结（含他人引用方）。
     *  service 层按 user_id 分自己/他人（12h 拒判仅看他人引用方 summarized_at）。 */
    List<MemorySummary> findSummariesReferencingTurn(@Param("turnId") Long turnId);

    /** E-4 KEEP_NEW/OLD/DISCARD 软删总结（按 id 集，显式 deleted=1 供批量，返实删条数）。 */
    int softDeleteByIds(@Param("summaryIds") List<Long> summaryIds);

    /** E-6 worker STALE 重生：更新 L1/L2 文本 + 置 status（CLEAN 重生完/STALE 保留）。 */
    int updateTextAndStatus(@Param("id") Long id,
                            @Param("l1") String l1,
                            @Param("l2") String l2,
                            @Param("status") String status);

    // ============================ 二期 P4 · 项目共享总结（V70）============================

    /** P4 项目共享总结读取（FR-301）：scope_owner=PROJECT 的项目总结，全员可读（成员咽喉在 service 层）。 */
    List<MemorySummary> findProjectSharedSummaries(@Param("projectId") Long projectId);

    /** P4 同 (project, tag, scope_owner=PROJECT) CLEAN 总结（共享总结时序互斥冲突判定用）。
     *  5x #4：direction 非空 → 仅同向/双向总结参与冲突判定；null 不过滤。 */
    List<MemorySummary> findCleanByProjectTagScope(@Param("projectId") Long projectId,
                                                   @Param("tagId") Long tagId,
                                                   @Param("direction") String direction);

    /** P4 同 (project, tag, scope_owner=PROJECT) 指定 status 总结（裁决找 PENDING_CONFLICT 另一方）。 */
    List<MemorySummary> findByProjectTagScopeStatus(@Param("projectId") Long projectId,
                                                    @Param("tagId") Long tagId,
                                                    @Param("status") String status);

    /** P4 删条目级联（FR-304）：source_entry_ids @> [entryId] 的全部总结（含共享总结）。 */
    List<MemorySummary> findSummariesReferencingEntry(@Param("entryId") Long entryId);

    /** P4 worker STALE 重生取数：项目共享总结（user_id IS NULL）status=STALE。 */
    List<MemorySummary> findStaleProjectShared();

    /** P4（FR-303）撤销授权钩子：parent 项目共享总结中 provenance 含 child 条目的一批量 STALE
     *  （worker 重压取数=当前 ACTIVE 链实时算，重压后不含 child 内容）。 */
    int markProjectSharedStaleByChildEntries(@Param("parentProjectId") Long parentProjectId,
                                             @Param("childProjectId") Long childProjectId);

    /** P4 worker 条目级 STALE 重生：更新文本 + status + source_entry_ids provenance（实时算链后的新源集）。 */
    int updateTextStatusAndEntries(@Param("id") Long id,
                                   @Param("l1") String l1,
                                   @Param("l2") String l2,
                                   @Param("status") String status,
                                   @Param("sourceEntryIds") List<Long> sourceEntryIds);
}
