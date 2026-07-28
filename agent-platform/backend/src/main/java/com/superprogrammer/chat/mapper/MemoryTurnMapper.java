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
}
