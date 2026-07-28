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
}
