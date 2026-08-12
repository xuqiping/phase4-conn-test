package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.dto.MemoryProjectEntryVO;
import com.superprogrammer.chat.dto.TurnProjectIndexRow;
import com.superprogrammer.chat.entity.MemoryProjectEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 项目记忆条目 mapper（V65 记忆二期 P1）。
 * <p>
 * CRUD 走 BaseMapper + LambdaQueryWrapper（tag_ids BIGINT[] 由实体
 * {@code @TableField(typeHandler=LongArrayTypeHandler)} 处理）；
 * 审核/列表查询走 {@link #listByProject} 自定义 SQL（join users 出作者名）。
 * 召回合流的批量查询在 Step 5 按需补。
 */
@Mapper
public interface MemoryProjectEntryMapper extends BaseMapper<MemoryProjectEntry> {

    /** 项目条目列表（审核页/成员「我的条目」）：status 过滤 + authorUserId 收窄（成员仅看自己产生的）。
     *  join users 出 authorName；按 created_at 倒序。 */
    List<MemoryProjectEntryVO> listByProject(@Param("projectId") Long projectId,
                                             @Param("status") String status,
                                             @Param("authorUserId") Long authorUserId);

    /** 召回合流（FR-007 ①.5）：一批项目内全部 ACTIVE 条目（带 tag_ids + authorName），按 created_at 倒序封顶 200。
     *  调用方须先把 projectIds 过滤为「读者是其 ACTIVE 成员」的集（读权咽喉在本类查询之外）。 */
    List<MemoryProjectEntryVO> listActiveForRecall(@Param("projectIds") List<Long> projectIds);

    /** P3 Step 4（FR-204）放行裁决：该文件被某 ACTIVE FILE 条目引用 且 请求者对该条目项目有读权
     *  —— ACTIVE 项目成员（memory_project_members）<b>或</b> ACTIVE 被授权方（memory_project_user_grants，
     *  教学课件场景：项目授权的子账号同样可下载被召回的收录附件）。 */
    @Select("SELECT COUNT(*) FROM memory_project_entries e "
            + "JOIN ("
            + "  SELECT project_id FROM memory_project_members WHERE user_id = #{userId} AND status = 'ACTIVE'"
            + "  UNION"
            + "  SELECT project_id FROM memory_project_user_grants WHERE user_id = #{userId} AND status = 'ACTIVE' AND deleted = 0"
            + ") p ON p.project_id = e.project_id "
            + "WHERE e.deleted = 0 AND e.status = 'ACTIVE' AND e.content_type = 'FILE' AND e.file_id = #{fileId}")
    long countAccessibleFileEntries(@Param("fileId") String fileId, @Param("userId") Long userId);

    /** P3 Step 4（FR-204）幂等去重：同项目同文件已有未删 FILE 条目 → 重路由跳过（防重试重灌重复条目）。 */
    @Select("SELECT COUNT(*) FROM memory_project_entries WHERE deleted = 0 AND content_type = 'FILE' "
            + "AND project_id = #{projectId} AND file_id = #{fileId}")
    long countFileEntry(@Param("projectId") Long projectId, @Param("fileId") String fileId);

    /** P3 Step 4（FR-204）失效：作者删文件 → 引用该文件的 FILE 条目全软删（条目标失效）。 */
    @Update("UPDATE memory_project_entries SET deleted = 1, updated_at = NOW() "
            + "WHERE deleted = 0 AND content_type = 'FILE' AND file_id = #{fileId}")
    int softDeleteFileEntries(@Param("fileId") String fileId);

    /** P4（FR-301/302/305）总结入口 hasChange/未覆盖计数：一批项目 ACTIVE 条目中，
     *  在 (scope 项目, 主体) 下无 entry_coverage 行的条数。userId=null → 共享覆盖行（IS NULL）。 */
    int countUncoveredEntries(@Param("sourceProjectIds") List<Long> sourceProjectIds,
                              @Param("scopeProjectId") Long scopeProjectId,
                              @Param("userId") Long userId);

    /** P4（FR-304）turn 删除级联：查引用这些 turn 的未删条目 id（波及总结标 STALE 用）。 */
    List<Long> findActiveIdsBySourceTurnIds(@Param("turnIds") List<Long> turnIds);

    /** P4（FR-304）turn 删除级联：引用这些 turn 的条目全软删。 */
    int softDeleteBySourceTurnIds(@Param("turnIds") List<Long> turnIds);

    /** 二期 P2：这些流水账（turn）被收录到的项目（未删条目，ACTIVE+PENDING_REVIEW）。
     *  一条 turn 可能命中多项目、同项目多 tag 会产生多行——service 按 turnId 分组并对 projectId 去重。 */
    List<TurnProjectIndexRow> findProjectIndexByTurnIds(@Param("turnIds") List<Long> turnIds);
}

