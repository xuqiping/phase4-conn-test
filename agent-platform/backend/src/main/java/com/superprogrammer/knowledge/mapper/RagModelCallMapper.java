package com.superprogrammer.knowledge.mapper;

import com.superprogrammer.knowledge.entity.RagModelCall;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RagModelCallMapper {
    @Insert("""
            INSERT INTO rag_model_calls
                (id, trace_id, retrieval_run_id, ranking_run_id, model_request_id,
                 provider_request_id, call_purpose, model_config_id, model_name, provider_name,
                 input_hash, output_hash, prompt_tokens, completion_tokens, cost_points,
                 status, latency_ms, error_summary, started_at, finished_at)
            VALUES
                (#{id}::uuid, #{traceId}, #{retrievalRunId}::uuid, #{rankingRunId}::uuid, #{modelRequestId},
                 #{providerRequestId}, #{callPurpose}, #{modelConfigId}, #{modelName}, #{providerName},
                 #{inputHash}, #{outputHash}, #{promptTokens}, #{completionTokens}, #{costPoints},
                 #{status}, #{latencyMs}, #{errorSummary}, COALESCE(#{startedAt}, now()), #{finishedAt})
            """)
    int insertCall(RagModelCall call);

    @Update("""
            UPDATE rag_model_calls
               SET status=#{status}, output_hash=#{outputHash}, prompt_tokens=#{promptTokens},
                   completion_tokens=#{completionTokens}, latency_ms=#{latencyMs},
                   error_summary=#{errorSummary}, finished_at=now()
             WHERE id=#{id}::uuid
            """)
    int finishCall(String id, String status, String outputHash, Integer promptTokens,
                   Integer completionTokens, long latencyMs, String errorSummary);

    @Select("SELECT * FROM rag_model_calls WHERE trace_id=#{traceId} ORDER BY started_at")
    List<RagModelCall> findByTraceId(String traceId);

    @Select("SELECT trace_id FROM rag_model_calls WHERE model_request_id=#{modelRequestId} ORDER BY started_at DESC LIMIT 1")
    String findTraceIdByModelRequestId(String modelRequestId);
}
