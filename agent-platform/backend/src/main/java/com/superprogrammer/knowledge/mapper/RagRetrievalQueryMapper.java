package com.superprogrammer.knowledge.mapper;

import com.superprogrammer.knowledge.dto.RagQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * RAG 检索读 SQL（v6 §4/§6.1）。集中 pgvector + BM25 + 权限查询，便于 per-model 表切换时局部修改。
 *
 * ⚠️ custom SQL 绕过 MyBatis-Plus @TableLogic → 所有 deleted 过滤硬写 deleted=0。
 * ⚠️ §6.1 强制 WHERE：status=ACTIVE / deleted=0 / e.content_hash=n.content_hash / e.embedding_model=kb.embedding_model
 *    / document_id⊆visible_set（post-ANN）/ metadata 硬过滤。dense 召回 SQL 是单一真相源，业务层禁绕过。
 */
@Mapper
public interface RagRetrievalQueryMapper {

    /**
     * step5：dense L0 召回（HNSW cosine）。强制 §6.1 WHERE。
     * allDocs=true 时省略 document_id 谓词（admin/owner 读 KB 全量，P1 由构造保证）。
     * <code>&lt;=&gt;</code> = pgvector halfvec cosine 距离 [0,2]，sim = 1 - distance。
     */
    @Select("""
            <script>
            SELECT n.id AS node_id,
                   n.document_id AS document_id,
                   n.title AS title,
                   n.content AS content,
                   (e.embedding &lt;=&gt; #{qHalf}::halfvec) AS cosine_distance
            FROM knowledge_embeddings_doubao e
            JOIN knowledge_nodes n      ON n.id = e.node_id
            JOIN knowledge_bases kb     ON kb.id = n.kb_id
            JOIN knowledge_documents d  ON d.id = n.document_id
            WHERE kb.id = #{kbId}
              AND kb.deleted = 0
              AND n.level = 'L0'
              AND n.status = 'ACTIVE'
              AND n.deleted = 0
              AND e.content_hash = n.content_hash
              AND e.embedding_model = kb.embedding_model
              AND d.deleted = 0
              AND d.current_version_id IS NOT NULL
              AND (d.effective_at IS NULL OR d.effective_at &lt;= now())
              AND (d.expired_at IS NULL OR d.expired_at &gt; now())
              <if test="!allDocs">
                AND n.document_id IN
                <foreach collection="docIds" item="did" open="(" separator="," close=")">#{did}</foreach>
              </if>
              <if test="docTypes != null and docTypes.size() > 0">
                AND d.doc_type IN
                <foreach collection="docTypes" item="dt" open="(" separator="," close=")">#{dt}</foreach>
              </if>
            ORDER BY e.embedding &lt;=&gt; #{qHalf}::halfvec
            LIMIT #{maxL0}
            </script>
            """)
    List<RagQueryRow.DenseRecallRow> denseRecallL0(@Param("kbId") Long kbId,
                                                    @Param("qHalf") String qHalf,
                                                    @Param("allDocs") boolean allDocs,
                                                    @Param("docIds") List<Long> docIds,
                                                    @Param("docTypes") List<String> docTypes,
                                                    @Param("maxL0") int maxL0);

    /**
     * step5（Phase3）：dense L1 文档召回（HNSW cosine，doc 级语义锚）。
     * FROM knowledge_doc_embeddings_doubao JOIN knowledge_documents，无 node level 过滤。
     * 召回时不复校 content_hash（L1 无 node 可比对；drift 靠重解析触发新 UPSERT_L1 job 接管）。
     * <code>&lt;=&gt;</code> = pgvector halfvec cosine 距离 [0,2]，sim = 1 - distance。
     */
    @Select("""
            <script>
            SELECT d.id AS document_id,
                   d.title AS title,
                   (e.embedding &lt;=&gt; #{qHalf}::halfvec) AS cosine_distance
            FROM knowledge_doc_embeddings_doubao e
            JOIN knowledge_documents d ON d.id = e.document_id
            JOIN knowledge_bases kb    ON kb.id = d.kb_id
            WHERE kb.id = #{kbId}
              AND kb.deleted = 0
              AND d.deleted = 0
              AND d.current_version_id IS NOT NULL
              AND (d.effective_at IS NULL OR d.effective_at &lt;= now())
              AND (d.expired_at IS NULL OR d.expired_at &gt; now())
              AND e.embedding_model = kb.embedding_model
              <if test="!allDocs">
                AND d.id IN
                <foreach collection="docIds" item="did" open="(" separator="," close=")">#{did}</foreach>
              </if>
              <if test="docTypes != null and docTypes.size() > 0">
                AND d.doc_type IN
                <foreach collection="docTypes" item="dt" open="(" separator="," close=")">#{dt}</foreach>
              </if>
            ORDER BY e.embedding &lt;=&gt; #{qHalf}::halfvec
            LIMIT #{maxL1}
            </script>
            """)
    List<RagQueryRow.L1RecallRow> denseRecallL1(@Param("kbId") Long kbId,
                                                  @Param("qHalf") String qHalf,
                                                  @Param("allDocs") boolean allDocs,
                                                  @Param("docIds") List<Long> docIds,
                                                  @Param("docTypes") List<String> docTypes,
                                                  @Param("maxL1") int maxL1);

    /** step6：取 top-M L0 的 L2 子节点（parent-anchored），限候选文档。 */
    @Select("""
            <script>
            SELECT n.id AS node_id, n.document_id AS document_id, n.parent_id AS parent_id,
                   n.title AS title, n.content AS content, n.content_hash AS content_hash
            FROM knowledge_nodes n
            WHERE n.kb_id = #{kbId}
              AND n.level = 'L2'
              AND n.status = 'ACTIVE'
              AND n.deleted = 0
              AND n.parent_id IN
              <foreach collection="parentIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
              AND n.document_id IN
              <foreach collection="docIds" item="did" open="(" separator="," close=")">#{did}</foreach>
            ORDER BY n.document_id, n.id
            </script>
            """)
    List<RagQueryRow.L2Row> fetchL2Children(@Param("kbId") Long kbId,
                                             @Param("parentIds") List<Long> parentIds,
                                             @Param("docIds") List<Long> docIds);

    /**
     * step6（Phase3）：L1 命中文档的 L2 子节点（doc 级召回补全）。
     * 与 fetchL2Children 区别：不限 parent∈topM（L1 命中但 L0 未进 topM 的文档，其 L2 仍取）。
     * per-doc 截断由 service 层 perDocL2Cap 控制。
     */
    @Select("""
            <script>
            SELECT n.id AS node_id, n.document_id AS document_id, n.parent_id AS parent_id,
                   n.title AS title, n.content AS content, n.content_hash AS content_hash
            FROM knowledge_nodes n
            WHERE n.kb_id = #{kbId}
              AND n.level = 'L2'
              AND n.status = 'ACTIVE'
              AND n.deleted = 0
              AND n.document_id IN
              <foreach collection="docIds" item="did" open="(" separator="," close=")">#{did}</foreach>
            ORDER BY n.document_id, n.id
            </script>
            """)
    List<RagQueryRow.L2Row> fetchL2ChildrenByDoc(@Param("kbId") Long kbId,
                                                   @Param("docIds") List<Long> docIds);

    /**
     * step6（Phase2）：jieba-BM25 预筛。查 content_tokens_tsv（jieba 分词后空格串生成的 'simple' tsvector）。
     * query 已由 JiebaTokenizer.tokenize 分词为空格串。
     *
     * <p>语义 OR（非 AND）：plainto_tsquery 整串是 AND，换说法 query 含"如何"等节点没有的词会全丢 →
     * 拆 per-token OR（任一 token 命中即召回），bm25_rank 为命中 token 的 ts_rank 之和（多命中靠前）。
     * 逐 token 用 plainto_tsquery（安全，无 | &amp; 操作符注入风险）。
     *
     * <p>存量节点 content_tokens IS NULL → content_tokens_tsv 空 → 不命中，优雅降级（回填后生效）。
     * 空 query → string_to_array 产 [''] → @@ 不命中 → 空返回，安全。
     */
    @Select("""
            <script>
            SELECT n.id AS node_id, n.document_id AS document_id, n.parent_id AS parent_id,
                   n.title AS title, n.content AS content, n.content_hash AS content_hash,
                   (SELECT COALESCE(SUM(ts_rank(n.content_tokens_tsv, plainto_tsquery('simple', tok))), 0)
                    FROM unnest(string_to_array(#{query}, ' ')) AS tok) AS bm25_rank
            FROM knowledge_nodes n
            WHERE n.kb_id = #{kbId}
              AND n.level = 'L2'
              AND n.status = 'ACTIVE'
              AND n.deleted = 0
              AND EXISTS (SELECT 1 FROM unnest(string_to_array(#{query}, ' ')) AS tok
                          WHERE n.content_tokens_tsv @@ plainto_tsquery('simple', tok))
              AND n.document_id IN
              <foreach collection="docIds" item="did" open="(" separator="," close=")">#{did}</foreach>
            ORDER BY bm25_rank DESC
            </script>
            """)
    List<RagQueryRow.L2Row> bm25HitsJieba(@Param("kbId") Long kbId,
                                          @Param("query") String tokenizedQuery,
                                          @Param("docIds") List<Long> docIds);

    /**
     * step1：USER 直接可见文档集（KB/DIRECTORY/DOCUMENT 三级 can_read 并集）。admin/owner 由 service 短路跳过。
     * ROLE/DEPARTMENT/SERVICE_ACCOUNT 聚合留阶段4（DEV-visible-set）。
     */
    @Select("""
            SELECT DISTINCT d.id
            FROM knowledge_documents d
            WHERE d.kb_id = #{kbId}
              AND d.deleted = 0
              AND d.current_version_id IS NOT NULL
              AND (d.effective_at IS NULL OR d.effective_at &lt;= now())
              AND (d.expired_at IS NULL OR d.expired_at &gt; now())
              AND (
                EXISTS (SELECT 1 FROM knowledge_permissions p
                        WHERE p.tenant_id = #{tenantId} AND p.subject_type='USER' AND p.subject_id=#{userId}
                          AND p.target_type='DOCUMENT' AND p.target_id=d.id AND p.can_read=TRUE)
                OR EXISTS (SELECT 1 FROM knowledge_permissions p
                        WHERE p.tenant_id = #{tenantId} AND p.subject_type='USER' AND p.subject_id=#{userId}
                          AND p.target_type='DIRECTORY' AND p.target_id=d.directory_id AND p.can_read=TRUE)
                OR EXISTS (SELECT 1 FROM knowledge_permissions p
                        WHERE p.tenant_id = #{tenantId} AND p.subject_type='USER' AND p.subject_id=#{userId}
                          AND p.target_type='KB' AND p.target_id=#{kbId} AND p.can_read=TRUE)
              )
            """)
    List<Long> computeVisibleDocs(@Param("tenantId") Long tenantId,
                                  @Param("kbId") Long kbId,
                                  @Param("userId") Long userId);

    /** step3 辅助：allDocs=true 且指定 docTypes 时，枚举 KB 内该类型文档。 */
    @Select("""
            <script>
            SELECT id FROM knowledge_documents
            WHERE kb_id = #{kbId} AND deleted = 0
              AND current_version_id IS NOT NULL
              AND (effective_at IS NULL OR effective_at &lt;= now())
              AND (expired_at IS NULL OR expired_at &gt; now())
              AND doc_type IN
              <foreach collection="docTypes" item="dt" open="(" separator="," close=")">#{dt}</foreach>
            </script>
            """)
    List<Long> listKbDocIdsByType(@Param("kbId") Long kbId,
                                  @Param("docTypes") List<String> docTypes);

    /** step8 I3：evidence 装载前 content_hash 复校（node 现值 vs 捕获值）。 */
    @Select("""
            SELECT n.id, n.content_hash AS node_hash, e.content_hash AS embed_hash,
                   n.metadata AS metadata
            FROM knowledge_nodes n
            LEFT JOIN knowledge_embeddings_doubao e ON e.node_id = n.id
            WHERE n.id = #{nodeId} AND n.deleted = 0
            """)
    RagQueryRow.HashVerifyRow reverifyNode(@Param("nodeId") Long nodeId);

    /** 当前知识快照：只返回 Hash，不返回任何文档或 Chunk 正文。 */
    @Select("""
            <script>
            SELECT md5(COALESCE(string_agg(
                       n.id::text || ':' || COALESCE(n.content_hash, '') || ':' || n.status,
                       '|' ORDER BY n.id), ''))
            FROM knowledge_nodes n
            WHERE n.deleted = 0
              AND n.kb_id IN
              <foreach collection="kbIds" item="kbId" open="(" separator="," close=")">#{kbId}</foreach>
            </script>
            """)
    String computeKnowledgeSnapshot(@Param("kbIds") List<Long> kbIds);

    /** step8：L1 文档元数据（outline/importantRules 注入用）+ IMAGE/FILE 原件回显字段（JOIN stored_files）。 */
    @Select("""
            SELECT d.id, d.title, d.doc_type, d.l1_metadata,
                   d.authority_level, d.confidentiality_level,
                   d.effective_at, d.expired_at,
                   d.file_ref AS file_ref,
                   sf.mime AS mime,
                   sf.original_name AS original_name
            FROM knowledge_documents d
            LEFT JOIN stored_files sf ON sf.file_id = REPLACE(d.file_ref, '/api/files/', '')
            WHERE d.id = #{docId} AND d.deleted = 0
              AND d.current_version_id IS NOT NULL
              AND (d.effective_at IS NULL OR d.effective_at &lt;= now())
              AND (d.expired_at IS NULL OR d.expired_at &gt; now())
            """)
    RagQueryRow.L1Row fetchL1Metadata(@Param("docId") Long docId);

    /** HNSW ef_search 调参（可选，>0 时同事务先 SET LOCAL）。 */
    @Update("SET LOCAL hnsw.ef_search = #{ef}")
    void setHnswEfSearch(@Param("ef") int ef);
}
