package com.superprogrammer.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.knowledge.entity.KnowledgeIndexJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeIndexJobMapper extends BaseMapper<KnowledgeIndexJob> {

    /**
     * 文档下仍未完成的 job 数（PENDING/RUNNING），用于判断整文档是否可置 INDEXED。
     * JOIN knowledge_nodes 取 document_id（job 表无 doc 维度）。
     */
    @Select("""
            SELECT COUNT(0) FROM knowledge_index_jobs j
            JOIN knowledge_nodes n ON j.node_id = n.id
            WHERE n.document_id = #{docId}
              AND j.status IN ('PENDING', 'RUNNING')
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
}
