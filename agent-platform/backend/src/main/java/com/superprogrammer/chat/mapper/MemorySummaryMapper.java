package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemorySummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
