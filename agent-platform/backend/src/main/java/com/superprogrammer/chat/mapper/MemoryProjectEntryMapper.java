package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryProjectEntry;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目记忆条目 mapper（V65 记忆二期 P1）。
 * <p>
 * P1 阶段 CRUD 走 BaseMapper + LambdaQueryWrapper（tag_ids BIGINT[] 由实体
 * {@code @TableField(typeHandler=LongArrayTypeHandler)} 处理）；召回合流的批量查询在
 * Step 5 按需补自定义 SQL。
 */
@Mapper
public interface MemoryProjectEntryMapper extends BaseMapper<MemoryProjectEntry> {
}
