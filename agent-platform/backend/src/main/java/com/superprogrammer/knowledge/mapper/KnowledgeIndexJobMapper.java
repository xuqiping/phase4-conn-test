package com.superprogrammer.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.knowledge.entity.KnowledgeIndexJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeIndexJobMapper extends BaseMapper<KnowledgeIndexJob> {

    /** 将当前有效 C2/E3（兼容存量 level=L2）节点按目标快照入队。 */
    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO knowledge_index_jobs
                (node_id, kb_id, job_type, content_hash, context_hash, version_id, parser_version,
                 chunker_version, embedding_model, pipeline_version, target_snapshot_id,
                 target_physical_index, idempotency_key, created_at, updated_at)
            SELECT n.id, n.kb_id, 'REINDEX', n.content_hash,
                   COALESCE(n.context_hash, '__phase1_placeholder__'), n.version_id,
                   v.parser_version, COALESCE(n.metadata::jsonb ->> 'chunkerVersion', 'legacy'),
                   b.embedding_model, #{pipelineVersion}, #{snapshotId}, #{physicalIndex},
                   md5(n.id || ':' || n.content_hash || ':' || #{snapshotId} || ':REINDEX'),
                   now(), now()
              FROM knowledge_nodes n
              JOIN knowledge_bases b ON b.id=n.kb_id AND b.deleted=0
              LEFT JOIN knowledge_document_versions v ON v.id=n.version_id AND v.deleted=0
             WHERE n.kb_id=#{kbId} AND n.deleted=0 AND n.status='ACTIVE'
               AND n.level='L2'
               AND COALESCE(n.metadata::jsonb ->> 'granularity', 'C2') IN ('C2','E3')
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
    int enqueueSnapshotJobs(@Param("kbId") long kbId, @Param("snapshotId") String snapshotId,
                            @Param("physicalIndex") String physicalIndex,
                            @Param("pipelineVersion") String pipelineVersion);

    @Select("""
            SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE status='DONE') AS done,
                   COUNT(*) FILTER (WHERE status IN ('FAILED','DEAD')) AS failed,
                   COUNT(*) FILTER (WHERE status='CANCELLED') AS cancelled
              FROM knowledge_index_jobs
             WHERE kb_id=#{kbId} AND target_snapshot_id=#{snapshotId}
            """)
    SnapshotJobCounts countSnapshotJobs(@Param("kbId") long kbId, @Param("snapshotId") String snapshotId);

    @org.apache.ibatis.annotations.Update("""
            UPDATE knowledge_index_jobs SET status='CANCELLED', locked_until=NULL, updated_at=now()
             WHERE kb_id=#{kbId} AND target_snapshot_id=#{snapshotId}
               AND status IN ('PENDING','RUNNING')
            """)
    int cancelSnapshotJobs(@Param("kbId") long kbId, @Param("snapshotId") String snapshotId);

    record SnapshotJobCounts(int total, int done, int failed, int cancelled) {}

    /**
     * S1/C2/E3 节点索引任务幂等入队。任务指纹已包含内容、上下文与全套版本快照；
     * 相同任务重放命中唯一键后直接跳过，不把可预期重复当成解析失败。
     */
    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO knowledge_index_jobs
                (node_id, kb_id, job_type, content_hash, context_hash, version_id, parser_version,
                 chunker_version, embedding_model, pipeline_version, idempotency_key, created_at, updated_at)
            VALUES
                (#{j.nodeId}, #{j.kbId}, #{j.jobType}, #{j.contentHash},
                 COALESCE(#{j.contextHash}, '__phase1_placeholder__'), #{j.versionId}, #{j.parserVersion},
                 #{j.chunkerVersion}, #{j.embeddingModel}, #{j.pipelineVersion}, #{j.idempotencyKey}, now(), now())
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
    int insertNodeJobIgnoreConflict(@Param("j") KnowledgeIndexJob job);

    /**
     * 文档下仍未完成的 job 数（PENDING/RUNNING），用于判断整文档是否可置 INDEXED。
     * JOIN knowledge_nodes 取 document_id（job 表无 doc 维度）。
     */
    /**
     * 文档下仍未完成的 job 数（PENDING/RUNNING），用于判断整文档是否可置 INDEXED。
     * 覆盖两类 job：node 锚定（UPSERT/REINDEX，node_id∈该 doc 节点）+ doc 锚定（UPSERT_L1，node_id NULL、document_id=docId）。
     * Phase3 前 JOIN nodes 按 node_id 取 document_id → UPSERT_L1（node_id NULL）漏计 → doc 提前 INDEXED bug 已修。
     */
    @Select("""
            SELECT COUNT(0) FROM knowledge_index_jobs j
            WHERE j.status IN ('PENDING', 'RUNNING')
              AND (j.document_id = #{docId}
                   OR j.node_id IN (SELECT id FROM knowledge_nodes WHERE document_id = #{docId}))
            """)
    Long countPendingRunningByDoc(@Param("docId") Long docId);

    // ============================ 阶段7 对账（ReconciliationJob）============================

    /** KB 下终态失败 job 数（DEAD + FAILED），供对账报告 dead_job_count。 */
    @Select("""
            SELECT COUNT(0) FROM knowledge_index_jobs
             WHERE kb_id = #{kbId}
               AND status IN ('DEAD', 'FAILED')
            """)
    Long countDeadFailedByKb(@Param("kbId") Long kbId);

    /** KB 下卡住的 RUNNING job（锁已过期未被重新认领），供报告 + 观察。 */
    @Select("""
            SELECT COUNT(0) FROM knowledge_index_jobs
             WHERE kb_id = #{kbId}
               AND status = 'RUNNING'
               AND locked_until IS NOT NULL
               AND locked_until < now()
            """)
    Long countStuckRunningByKb(@Param("kbId") Long kbId);

    /** 漂移：ACTIVE node 的 content_hash ≠ 其 embedding 行 content_hash（须补 REINDEX 的 node_id）。 */
    @Select("""
            SELECT n.id FROM knowledge_nodes n
            JOIN knowledge_embeddings_doubao e ON e.node_id = n.id
            WHERE n.kb_id = #{kbId}
              AND n.status = 'ACTIVE'
              AND n.deleted = 0
              AND n.content_hash <> e.content_hash
            """)
    List<Long> findDriftedNodeIds(@Param("kbId") Long kbId);

    /** 孤儿向量：embedding 行对应的 node 软删/丢失（node_id IS NULL 或 deleted≠0 或 ARCHIVED）。 */
    @Select("""
            SELECT COUNT(0) FROM knowledge_embeddings_doubao e
            LEFT JOIN knowledge_nodes n ON n.id = e.node_id
            WHERE e.kb_id = #{kbId}
              AND (n.id IS NULL OR n.deleted <> 0 OR n.status = 'ARCHIVED')
            """)
    Long countOrphanEmbeddings(@Param("kbId") Long kbId);

    /**
     * 入 REINDEX job，幂等：idempotency_key UNIQUE（sha256(nodeId:contentHash:REINDEX)），
     * ON CONFLICT DO NOTHING 保证同 node+hash 不重复入队（已 PENDING/RUNNING/DONE 的 drift 不重复修复）。
     * content_hash 取 node 当前值（enqueue 时读）；job_type='REINDEX'。返回 1=新入队，0=已存在跳过。
     */
    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO knowledge_index_jobs
                (node_id, kb_id, job_type, content_hash, idempotency_key, created_at, updated_at)
            VALUES
                (#{j.nodeId}, #{j.kbId}, 'REINDEX', #{j.contentHash}, #{j.idempotencyKey}, now(), now())
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
    int insertReindexJobIgnoreConflict(@Param("j") com.superprogrammer.knowledge.entity.KnowledgeIndexJob j);

    /**
     * 入 UPSERT_L1 job（Phase3，doc 级 L1 向量），幂等：idempotency_key UNIQUE
     * （sha256(docId:l1hash:UPSERT_L1)），ON CONFLICT DO NOTHING 保证同 doc+同 l1 不重复入队
     * （重解析 l1 未变→跳过；l1 变→新 hash 新 job 接管）。
     * node_id=NULL（doc 级 job），document_id 锚定文档。返回 1=新入队，0=已存在跳过。
     */
    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO knowledge_index_jobs
                (node_id, document_id, kb_id, job_type, content_hash, version_id, parser_version,
                 chunker_version, embedding_model, pipeline_version, idempotency_key, created_at, updated_at)
            VALUES
                (NULL, #{j.documentId}, #{j.kbId}, 'UPSERT_L1', #{j.contentHash}, #{j.versionId}, #{j.parserVersion},
                 #{j.chunkerVersion}, #{j.embeddingModel}, #{j.pipelineVersion}, #{j.idempotencyKey}, now(), now())
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
    int insertL1JobIgnoreConflict(@Param("j") com.superprogrammer.knowledge.entity.KnowledgeIndexJob j);

    /**
     * 入 UPSERT_IMAGE job（WP5 Step2，doc 级图片向量），幂等：idempotency_key UNIQUE
     * （sha256(docId:fileRefHash:versionId:model:pipeline:UPSERT_IMAGE)），ON CONFLICT DO NOTHING
     * 保证同 doc+同原件不重复入队（重解析未换图→跳过；换图→新 hash 新 job 接管）。
     * node_id=NULL（doc 级 job），document_id 锚定文档。返回 1=新入队，0=已存在跳过。
     */
    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO knowledge_index_jobs
                (node_id, document_id, kb_id, job_type, content_hash, version_id, parser_version,
                 chunker_version, embedding_model, pipeline_version, idempotency_key, created_at, updated_at)
            VALUES
                (NULL, #{j.documentId}, #{j.kbId}, 'UPSERT_IMAGE', #{j.contentHash}, #{j.versionId}, #{j.parserVersion},
                 #{j.chunkerVersion}, #{j.embeddingModel}, #{j.pipelineVersion}, #{j.idempotencyKey}, now(), now())
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
    int insertImageJobIgnoreConflict(@Param("j") com.superprogrammer.knowledge.entity.KnowledgeIndexJob j);
}
