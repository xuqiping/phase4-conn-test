package com.superprogrammer.knowledge.mapper;

import com.superprogrammer.knowledge.entity.RagRetrievalRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** UUID/JSONB 运行表后续使用显式 SQL，避免 BaseMapper 自动 ResultMap 猜错 PostgreSQL 特型。 */
@Mapper
public interface RagRetrievalRunMapper {
    @Insert("""
            INSERT INTO rag_retrieval_runs
                (id, trace_id, tenant_id, user_id, kb_ids, query_hash, query_type,
                 pipeline_version_id, knowledge_snapshot, status, result_state, latency_ms,
                 error_code, error_summary, started_at, finished_at)
            VALUES
                (#{id}::uuid, #{traceId}, #{tenantId}, #{userId}, #{kbIds}::jsonb, #{queryHash}, #{queryType},
                 #{pipelineVersionId}, #{knowledgeSnapshot}, #{status}, #{resultState}, #{latencyMs},
                 #{errorCode}, #{errorSummary}, COALESCE(#{startedAt}, now()), #{finishedAt})
            """)
    int insertRun(RagRetrievalRun run);

    @Update("""
            UPDATE rag_retrieval_runs
               SET status=#{status}, result_state=#{resultState}, latency_ms=#{latencyMs},
                   error_code=#{errorCode}, error_summary=#{errorSummary}, finished_at=now()
             WHERE id=#{id}::uuid
            """)
    int finishRun(String id, String status, String resultState, long latencyMs,
                  String errorCode, String errorSummary);

    @Select("SELECT * FROM rag_retrieval_runs WHERE trace_id=#{traceId} ORDER BY started_at")
    List<RagRetrievalRun> findByTraceId(String traceId);
}
