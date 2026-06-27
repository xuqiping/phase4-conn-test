package com.superprogrammer.knowledge.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * knowledge_doc_embeddings_doubao 写入（V36，Phase3 L1 向量通道）。
 * halfvec 列用自定义 SQL（`#{halfvec}::halfvec`），MyBatis-Plus 默认 handler 不支持 halfvec。
 *
 * upsert 用 ON CONFLICT(document_id)：document_id 唯一 → 同 doc 重嵌/更新就地覆盖，
 * 保证一文档只一行 L1 向量。
 */
@Mapper
public interface KnowledgeDocEmbeddingMapper {

    @Insert("""
            INSERT INTO knowledge_doc_embeddings_doubao
                (document_id, tenant_id, kb_id, embedding_model, embedding, content_hash, created_at)
            VALUES
                (#{documentId}, #{tenantId}, #{kbId}, #{embeddingModel}, #{halfvec}::halfvec, #{contentHash}, now())
            ON CONFLICT (document_id) DO UPDATE SET
                embedding       = EXCLUDED.embedding,
                embedding_model = EXCLUDED.embedding_model,
                content_hash    = EXCLUDED.content_hash
                -- 表无 updated_at（V36 仅 created_at）；重嵌就地覆盖，created_at 保留
            """)
    void upsert(@Param("documentId") Long documentId,
                @Param("tenantId") Long tenantId,
                @Param("kbId") Long kbId,
                @Param("embeddingModel") String embeddingModel,
                @Param("halfvec") String halfvec,
                @Param("contentHash") String contentHash);

    /** 删文档：硬删该文档 L1 向量行。doc 软删时显式清（doc 硬删走 ON DELETE CASCADE）。 */
    @Delete("DELETE FROM knowledge_doc_embeddings_doubao WHERE document_id = #{documentId}")
    int deleteByDocument(@Param("documentId") Long documentId);
}
