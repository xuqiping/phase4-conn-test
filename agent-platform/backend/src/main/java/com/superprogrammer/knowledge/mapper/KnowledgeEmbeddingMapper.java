package com.superprogrammer.knowledge.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * knowledge_embeddings_doubao 写入（v6 §8.3）。
 * halfvec 列用自定义 SQL（`#{halfvec}::halfvec`），MyBatis-Plus 默认 handler 不支持 halfvec。
 *
 * upsert 用 ON CONFLICT(node_id)：node_id 唯一 → 同 node 重嵌/更新就地覆盖，
 * 保证一个 node 只有一行向量（I1：content_hash 始终与 node.content_hash 对齐）。
 */
@Mapper
public interface KnowledgeEmbeddingMapper {

    @Insert("""
            INSERT INTO knowledge_embeddings_doubao
                (node_id, tenant_id, kb_id, node_level, embedding_model, embedding, content_hash, context_hash, created_at)
            VALUES
                (#{nodeId}, #{tenantId}, #{kbId}, #{nodeLevel}, #{embeddingModel}, #{halfvec}::halfvec, #{contentHash}, #{contextHash}, now())
            ON CONFLICT (node_id) DO UPDATE SET
                embedding       = EXCLUDED.embedding,
                embedding_model = EXCLUDED.embedding_model,
                content_hash    = EXCLUDED.content_hash,
                context_hash    = EXCLUDED.context_hash
                -- 表无 updated_at（V17 §8.3 仅 created_at）；重嵌就地覆盖，created_at 保留
            """)
    void upsert(@Param("nodeId") Long nodeId,
                @Param("tenantId") Long tenantId,
                @Param("kbId") Long kbId,
                @Param("nodeLevel") String nodeLevel,
                @Param("embeddingModel") String embeddingModel,
                @Param("halfvec") String halfvec,
                @Param("contentHash") String contentHash,
                @Param("contextHash") String contextHash);

    /** 阶段7 对账：删 KB 下孤儿向量（node 软删/丢失）。 */
    @Delete("""
            DELETE FROM knowledge_embeddings_doubao e
             WHERE e.kb_id = #{kbId}
               AND (NOT EXISTS (SELECT 1 FROM knowledge_nodes n WHERE n.id = e.node_id AND n.deleted = 0)
                    OR EXISTS (SELECT 1 FROM knowledge_nodes n WHERE n.id = e.node_id AND n.status = 'ARCHIVED'))
            """)
    int deleteOrphansByKb(@Param("kbId") Long kbId);
}
