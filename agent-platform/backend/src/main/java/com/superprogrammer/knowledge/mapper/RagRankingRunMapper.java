package com.superprogrammer.knowledge.mapper;

import com.superprogrammer.knowledge.entity.RagRankingRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RagRankingRunMapper {
    @Insert("""
            INSERT INTO rag_ranking_runs
                (id, retrieval_run_id, configured_mode, effective_mode, model_config_id,
                 ranking_config_version, candidate_count, final_count, candidate_hash,
                 fallback_reason, status, latency_ms, started_at, finished_at)
            VALUES
                (#{id}::uuid, #{retrievalRunId}::uuid, #{configuredMode}, #{effectiveMode}, #{modelConfigId},
                 #{rankingConfigVersion}, #{candidateCount}, #{finalCount}, #{candidateHash},
                 #{fallbackReason}, #{status}, #{latencyMs}, COALESCE(#{startedAt}, now()), #{finishedAt})
            """)
    int insertRun(RagRankingRun run);

    @Update("""
            UPDATE rag_ranking_runs
               SET status=#{status}, final_count=#{finalCount}, latency_ms=#{latencyMs},
                   fallback_reason=COALESCE(#{fallbackReason}, fallback_reason), finished_at=now()
             WHERE id=#{id}::uuid
            """)
    int finishRun(String id, String status, int finalCount, long latencyMs, String fallbackReason);

    @Select("""
            SELECT rr.* FROM rag_ranking_runs rr
            JOIN rag_retrieval_runs r ON r.id=rr.retrieval_run_id
            WHERE r.trace_id=#{traceId} ORDER BY rr.started_at
            """)
    List<RagRankingRun> findByTraceId(String traceId);
}
