package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryEntryCoverage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 条目级覆盖表 mapper（V70 记忆二期 P4，FR-305）。
 * <p>
 * 共享总结覆盖行 user_id IS NULL；成员个人压缩覆盖行 user_id=成员（各自幂等，互不影响）。
 */
@Mapper
public interface MemoryEntryCoverageMapper extends BaseMapper<MemoryEntryCoverage> {

    /** 一批条目在某 (scope 项目, tag, 主体) 下已覆盖的 entry_id 集（未覆盖判定=幂等，无新增不调 LLM）。
     *  userId=null → 查共享行（user_id IS NULL）；非空 → 查该成员个人行。 */
    List<Long> findCoveredEntryIds(@Param("entryIds") List<Long> entryIds,
                                   @Param("projectId") Long projectId,
                                   @Param("tagId") Long tagId,
                                   @Param("userId") Long userId);

    /** 批量写覆盖行。UNIQUE 冲突（NULLS NOT DISTINCT）→ DO NOTHING 幂等（V47 batchInsert 范式）。 */
    int batchInsert(@Param("rows") List<MemoryEntryCoverage> rows);

    /** 级联清：删某 summary 的全部条目覆盖行（裁决败方软删 / STALE 重建前清）。 */
    int deleteBySummaryId(@Param("summaryId") Long summaryId);
}
