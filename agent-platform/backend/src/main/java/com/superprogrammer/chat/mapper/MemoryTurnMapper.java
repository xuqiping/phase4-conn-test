package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryTurn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记忆流水账 mapper（V47 计划12）。
 * 读方法内联 SCOPE_FILTER（见 xml）——自己 ∪ accessible 项目集（向量 1/2）。
 * updateProjectIds 显式 typeHandler——绕开 LambdaUpdateWrapper 不读 typeHandler 的坑（V33 教训）。
 */
@Mapper
public interface MemoryTurnMapper extends BaseMapper<MemoryTurn> {

    /** 当前用户可见的流水账（自己的 + accessible 项目集内的）。 */
    List<MemoryTurn> findVisibleTurns(@Param("userId") Long userId,
                                      @Param("accessibleProjectIds") List<Long> accessibleProjectIds);

    /** 挂载项目——显式 typeHandler，供 L11 多挂/卸用。 */
    int updateProjectIds(@Param("id") Long id, @Param("projectIds") List<Long> projectIds);

    // ============================ 计划12 · D-5 拼流水账 ⑥ ============================

    /** 个人 scope 可召回流水账：本人 born_personal=true AND gen_done=true（项目出身不进个人召回）。
     *  raw（gen_done=false）不参与。direction/timeWindow 过滤 created_at。 */
    List<MemoryTurn> findPersonalRecallableTurns(@Param("userId") Long userId,
                                                 @Param("direction") String direction,
                                                 @Param("twStart") OffsetDateTime twStart,
                                                 @Param("twEnd") OffsetDateTime twEnd,
                                                 @Param("relativeDays") Integer relativeDays);

    /** 项目 scope 可召回流水账：project_ids 含 X 且 user_id ∈ readableAuthors AND gen_done=true。
     *  readableAuthorIds 须非空（Patcher 层空集 skip，防越权向量14）。 */
    List<MemoryTurn> findProjectRecallableTurns(@Param("projectId") Long projectId,
                                                @Param("readerId") Long readerId,
                                                @Param("readableAuthorIds") List<Long> readableAuthorIds,
                                                @Param("direction") String direction,
                                                @Param("twStart") OffsetDateTime twStart,
                                                @Param("twEnd") OffsetDateTime twEnd,
                                                @Param("relativeDays") Integer relativeDays);

    // ============================ 计划12 · E 总结取数 + backfill + DISCARD 级联 ============================

    /** E-3 个人总结取数：本人 born_personal=true AND gen_done=true AND tag_ids 含任一 tagId。
     *  取全量（service 查 coverage 判未覆盖，决定压缩范围）；direction/timeWindow 过滤 created_at。 */
    List<MemoryTurn> findPersonalTurnsForConsolidation(@Param("userId") Long userId,
                                                       @Param("tagIds") List<Long> tagIds,
                                                       @Param("direction") String direction,
                                                       @Param("twStart") OffsetDateTime twStart,
                                                       @Param("twEnd") OffsetDateTime twEnd,
                                                       @Param("relativeDays") Integer relativeDays);

    /** E-3 项目总结取数：project_ids 含 X AND user_id ∈ authorIds AND gen_done=true AND tag_ids 含任一 tagId。
     *  authorIds 须非空（service 层 ∩ readableAuthors 后空集 skip，向量 14）。 */
    List<MemoryTurn> findProjectTurnsForConsolidation(@Param("projectId") Long projectId,
                                                      @Param("authorIds") List<Long> authorIds,
                                                      @Param("tagIds") List<Long> tagIds,
                                                      @Param("direction") String direction,
                                                      @Param("twStart") OffsetDateTime twStart,
                                                      @Param("twEnd") OffsetDateTime twEnd,
                                                      @Param("relativeDays") Integer relativeDays);

    /** E-3 STALE 重生取数：按 id 集回读未软删 turn（source_turn_ids - 已删 → 剩余 turns 重压缩）。 */
    List<MemoryTurn> findTurnsByIds(@Param("turnIds") List<Long> turnIds);

    /** E-2 backfill 取数：scope 内 gen_done=false 的 raw turn，分批（LIMIT ≤20/批）。
     *  projectId=null+bornPersonal=true → 个人 raw；否则项目 raw（project_ids 含 X）。 */
    List<MemoryTurn> findRawTurnsForBackfill(@Param("userId") Long userId,
                                             @Param("projectId") Long projectId,
                                             @Param("bornPersonal") boolean bornPersonal,
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

    /** E-7 个人 scope 未总结 turn 计数：gen_done=true AND born_personal=true AND 无 coverage(user_id=self) 行。
     *  设计 §3.9 line178：未总结 = gen_done=true 且无 coverage；raw(gen_done=false) 不计告警阈值。 */
    int countUncoveredPersonalTurns(@Param("userId") Long userId);

    /** E-7 个人 scope raw turn 计数（gen_done=false，hasChange 判据 + 遗忘权面板）。 */
    int countRawPersonalTurns(@Param("userId") Long userId);

    // ============================ 计划12 · F-4b 前置 · 生命周期折叠板（§3.7）============================

    /** F-4b 前置：本人 turns deleted_project_ids 引用的已删项目列表（去重 + join 项目名绕软删 + turn 计数）。 */
    List<com.superprogrammer.chat.dto.MemoryLifecycleProjectVO> findMyDeletedProjects(@Param("userId") Long userId);

    /** F-4b restore 前置校验：本人 deleted_project_ids 含该项目的未软删 turn 计数（0 = 无待拉取）。 */
    int countMyTurnsInDeletedProject(@Param("userId") Long userId, @Param("projectId") Long projectId);

    /** F-4b copy-to：本人在 fromProjectId 的 turns 追加挂 newProjectId（copy 非 move，原挂载 + departed 标记不动）。 */
    int appendProjectToMyTurns(@Param("userId") Long userId,
                               @Param("fromProjectId") Long fromProjectId,
                               @Param("newProjectId") Long newProjectId);

    /** F-4b restore：本人 turns 移出 deleted_project_ids 的该项目 + 重挂 newProjectId（仅拉 turn 不拉 summary）。 */
    int restoreMyTurnsFromDeletedProject(@Param("userId") Long userId,
                                         @Param("deletedProjectId") Long deletedProjectId,
                                         @Param("newProjectId") Long newProjectId);

    /** 项目名查询（绕 projects @TableLogic 软删过滤——已删项目也要取名做默认命名）。 */
    String findProjectNameAnyState(@Param("projectId") Long projectId);

    // ============================ 计划12 · 生命周期写侧 hook（§3.7）============================

    /** 写侧 hook · 成员离职：本人挂在该项目的 turns 追加 departed_project_ids（不卸载不删数据）。幂等。 */
    int appendDepartedProjectToMyTurns(@Param("userId") Long userId,
                                       @Param("projectId") Long projectId);

    /** 写侧 hook · 项目删除：全部作者挂在该项目的 turns 追加 deleted_project_ids（不移除 project_ids）。幂等。 */
    int markProjectDeletedForAllTurns(@Param("projectId") Long projectId);

    /** 写侧 hook · 项目删除：曾写记忆的成员 + 各自 turn 数（PROJECT_DELETED_AFFECTED 通知接收者，§3.7 M1）。 */
    List<com.superprogrammer.chat.dto.MemoryProjectAffectedAuthorVO> findAuthorsWithTurnsInProject(@Param("projectId") Long projectId);
}
