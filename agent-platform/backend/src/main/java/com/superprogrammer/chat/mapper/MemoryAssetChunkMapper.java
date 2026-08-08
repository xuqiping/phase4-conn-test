package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryAssetChunk;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 文件分块 mapper（V69 记忆二期 P3）。
 * <p>
 * CRUD 走 BaseMapper。chunk_embedding halfvec 写入走 {@link #insertWithEmbedding} 自定义 SQL
 * （{@code #{halfvec}::halfvec} 强转，KnowledgeEmbeddingMapper 同款）；
 * 向量 top-k 检索在 Step 3 补（halfvec 裸 {@code <=>} 须转义 {@code &lt;=&gt;}，V33 旧教训）。
 */
@Mapper
public interface MemoryAssetChunkMapper extends BaseMapper<MemoryAssetChunk> {

    /** 插一条带向量的分块（BaseEntity 填充对注解 SQL 不生效，时间列 SQL 内 NOW()）。 */
    @Insert("INSERT INTO memory_asset_chunks(asset_memory_id, chunk_no, chunk_text, page_ref, chunk_embedding, "
            + "created_at, updated_at) VALUES (#{assetMemoryId}, #{chunkNo}, #{chunkText}, #{pageRef}, "
            + "#{halfvec}::halfvec, NOW(), NOW())")
    int insertWithEmbedding(@Param("assetMemoryId") Long assetMemoryId,
                            @Param("chunkNo") int chunkNo,
                            @Param("chunkText") String chunkText,
                            @Param("pageRef") String pageRef,
                            @Param("halfvec") String halfvec);

    /** 重试重解析前软清旧分块（部分唯一索引不挡重建）。 */
    @Update("UPDATE memory_asset_chunks SET deleted = 1, updated_at = NOW() "
            + "WHERE asset_memory_id = #{assetMemoryId} AND deleted = 0")
    int softDeleteByMemoryId(@Param("assetMemoryId") Long assetMemoryId);
}
