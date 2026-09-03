package com.superprogrammer.knowledge.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * knowledge_image_embeddings_doubao 写入（WP5 Step2，V173）。
 * IMAGE 文档级图片向量：每文档 1 行（原件 bytes→多模态 embed）。
 * halfvec 列用自定义 SQL（`#{halfvec}::halfvec`），与 {@link KnowledgeEmbeddingMapper} 同款。
 *
 * upsert 用 ON CONFLICT(document_id)：document_id 唯一 → 同文档重嵌就地覆盖
 * （重换图→重解析→新 UPSERT_IMAGE job→本 upsert 接管）。
 */
@Mapper
public interface KnowledgeImageEmbeddingMapper {

    @Insert("""
            INSERT INTO knowledge_image_embeddings_doubao
                (document_id, tenant_id, kb_id, embedding_model, embedding, content_hash, created_at)
            VALUES
                (#{documentId}, #{tenantId}, #{kbId}, #{embeddingModel}, #{halfvec}::halfvec, #{contentHash}, now())
            ON CONFLICT (document_id) DO UPDATE SET
                embedding       = EXCLUDED.embedding,
                embedding_model = EXCLUDED.embedding_model,
                content_hash    = EXCLUDED.content_hash
                -- 表无 updated_at（V173 仅 created_at）；重嵌就地覆盖，created_at 保留
            """)
    void upsert(@Param("documentId") Long documentId,
                @Param("tenantId") Long tenantId,
                @Param("kbId") Long kbId,
                @Param("embeddingModel") String embeddingModel,
                @Param("halfvec") String halfvec,
                @Param("contentHash") String contentHash);

    /**
     * 删文档：硬删该文档图片向量行（doc 软删/重解析时同步清，与
     * {@code KnowledgeEmbeddingMapper.deleteByDocument} 同语义同事务）。
     * FK ON DELETE CASCADE 兜底（doc 硬删）；软删路径仍需显式清（emb 表硬删语义）。
     */
    @Delete("DELETE FROM knowledge_image_embeddings_doubao WHERE document_id = #{documentId}")
    int deleteByDocument(@Param("documentId") Long documentId);
}
