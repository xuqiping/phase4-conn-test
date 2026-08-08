package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryAssetChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件分块 mapper（V69 记忆二期 P3）。
 * <p>
 * CRUD 走 BaseMapper。chunk_embedding halfvec 向量写入/检索走自定义 SQL
 * （Step 2/3 补，halfvec 裸 {@code <=>} 须转义 {@code &lt;=&gt;}，V33 旧教训）。
 */
@Mapper
public interface MemoryAssetChunkMapper extends BaseMapper<MemoryAssetChunk> {
}
