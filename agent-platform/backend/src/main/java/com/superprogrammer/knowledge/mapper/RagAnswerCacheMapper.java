package com.superprogrammer.knowledge.mapper;

import com.superprogrammer.knowledge.dto.CacheCandidateRow;
import com.superprogrammer.knowledge.entity.RagAnswerCache;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * rag_answer_cache 读写（v6 §8.9a，阶段4-B）。
 *
 * <p>halfvec key_embedding 用自定义 SQL（`#{keyHalf}::halfvec`），MyBatis-Plus 默认 handler 不支持。
 * <p>**强制 per-user**：searchCandidates 恒带 `scope_user_id = #{scopeUserId}`，HNSW 跨用户近邻被 WHERE 滤掉。
 */
@Mapper
public interface RagAnswerCacheMapper {

    /**
     * step2 命中候选检索：HNSW key_embedding 近邻（按 cosine 距离升序）+ per-user + ACTIVE 过滤。
     * 返回 top-N 候选由 service 逐个 P2/P3 验，首个通过即命中。
     */
    @Select("""
            <script>
            SELECT id,
                   query_canonical,
                   (key_embedding &lt;=&gt; #{qHalf}::halfvec) AS cosine_distance,
                   answer,
                   provenance_node_ids,
                   evidence_hashes,
                   permission_signature,
                   confidence
            FROM rag_answer_cache
            WHERE scope_user_id = #{scopeUserId}
              AND status = 'ACTIVE'
            ORDER BY key_embedding &lt;=&gt; #{qHalf}::halfvec
            LIMIT #{topN}
            </script>
            """)
    List<CacheCandidateRow> searchCandidates(@Param("scopeUserId") Long scopeUserId,
                                             @Param("qHalf") String qHalf,
                                             @Param("topN") int topN);

    /**
     * 写缓存（无 ON CONFLICT —— 自然键语义化，重复/近义行靠 decay_at 清理，阶段7 ReconciliationJob 兜底）。
     * key_embedding 走 `#{keyHalf}::halfvec`（预序列化 "[v0,v1,...]" 字面量）。
     */
    @Insert("""
            INSERT INTO rag_answer_cache
                (tenant_id, scope_user_id, kb_ids, query_canonical, key_embedding, key_embedding_model,
                 answer, provenance_node_ids, evidence_hashes, permission_signature, confidence,
                 usage_count, decay_at, status, created_at, updated_at)
            VALUES
                (#{c.tenantId}, #{c.scopeUserId}, #{c.kbIds}, #{c.queryCanonical}, #{keyHalf}::halfvec, #{c.keyEmbeddingModel},
                 #{c.answer}, #{c.provenanceNodeIds}, #{c.evidenceHashes}, #{c.permissionSignature}, #{c.confidence},
                 #{c.usageCount}, #{c.decayAt}, #{c.status}, now(), now())
            """)
    void insert(@Param("c") RagAnswerCache c, @Param("keyHalf") String keyHalf);

    /** 命中时计数 + 刷新 updated_at（命中频次观测 + LRU 清理依据）。 */
    @Update("UPDATE rag_answer_cache SET usage_count = usage_count + 1, updated_at = now() WHERE id = #{id}")
    void bumpUsage(@Param("id") Long id);
}
