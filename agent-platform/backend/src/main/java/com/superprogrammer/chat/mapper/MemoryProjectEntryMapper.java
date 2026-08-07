package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.dto.MemoryProjectEntryVO;
import com.superprogrammer.chat.entity.MemoryProjectEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}

