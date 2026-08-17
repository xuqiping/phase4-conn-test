package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.dto.AssetChunkCount;
import com.superprogrammer.chat.dto.FileChunkHit;
import com.superprogrammer.chat.entity.MemoryAssetChunk;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

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

    /** Step 3：命中记忆的分块计数（卡片「共N块」，GROUP BY 一次查防 N+1）。 */    @Select("SELECT asset_memory_id, COUNT(*) AS cnt FROM memory_asset_chunks "
            + "WHERE deleted = 0 AND asset_memory_id = ANY(#{memoryIds,typeHandler=com.superprogrammer.common.typehandler.LongArrayTypeHandler}) "
            + "GROUP BY asset_memory_id")
    List<AssetChunkCount> countByMemoryIds(@Param("memoryIds") List<Long> memoryIds);

    /**
     * Step 3 深读（FR-203）：query 向量在命中记忆的分块里取 cosine top-k（距离 ≤ maxDistance 过滤噪声，坑表⑥）。
     * 注解 SQL 无 XML 转义问题（{@code <=>} 裸写；XML 才须 {@code &lt;=&gt;}，V33 旧教训）。
     * null 向量分块（embed 降级）天然排除。
     * <p>
     * 5x 四轮 U3：<b>每文件分块上限 perFileLimit</b>（ROW_NUMBER 窗口按 asset_memory_id 分组取最近邻）——
     * 单文件分块多的文件不能吃满 top-k 挤掉其他文件（原 LIMIT 全局排导致单文件垄断）。
     */
    @Select("SELECT id, asset_memory_id, chunk_no, chunk_text, page_ref, distance FROM ("
            + "SELECT id, asset_memory_id, chunk_no, chunk_text, page_ref, "
            + "(chunk_embedding <=> #{halfvec}::halfvec) AS distance, "
            + "ROW_NUMBER() OVER (PARTITION BY asset_memory_id "
            + "ORDER BY (chunk_embedding <=> #{halfvec}::halfvec) ASC) AS rn "
            + "FROM memory_asset_chunks "
            + "WHERE deleted = 0 AND chunk_embedding IS NOT NULL "
            + "AND asset_memory_id = ANY(#{memoryIds,typeHandler=com.superprogrammer.common.typehandler.LongArrayTypeHandler}) "
            + "AND (chunk_embedding <=> #{halfvec}::halfvec) <= #{maxDistance}) ranked "
            + "WHERE rn <= #{perFileLimit} ORDER BY distance ASC LIMIT #{limit}")
    List<FileChunkHit> searchTopK(@Param("memoryIds") List<Long> memoryIds,
                                  @Param("halfvec") String halfvec,
                                  @Param("maxDistance") double maxDistance,
                                  @Param("perFileLimit") int perFileLimit,
                                  @Param("limit") int limit);
}
