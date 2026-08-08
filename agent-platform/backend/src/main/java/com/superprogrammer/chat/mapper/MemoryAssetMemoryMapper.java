package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryAssetMemory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件记忆 mapper（V69 记忆二期 P3）。
 * <p>
 * CRUD 走 BaseMapper + LambdaQueryWrapper（tag_ids BIGINT[] 由实体
 * {@code @TableField(typeHandler=LongArrayTypeHandler)} 处理）。
 * 召回按 tag 命中查询在 Step 3 按需补自定义 SQL。
 */
@Mapper
public interface MemoryAssetMemoryMapper extends BaseMapper<MemoryAssetMemory> {
}
