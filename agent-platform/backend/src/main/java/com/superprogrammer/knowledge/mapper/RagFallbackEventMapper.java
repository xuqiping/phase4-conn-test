package com.superprogrammer.knowledge.mapper;

import com.superprogrammer.knowledge.entity.RagFallbackEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RagFallbackEventMapper {
    @Insert("""
            INSERT INTO rag_fallback_events
                (trace_id, retrieval_run_id, ranking_run_id, stage, configured_mode,
                 effective_mode, reason_code, reason_summary, created_at)
            VALUES
                (#{traceId}, #{retrievalRunId}::uuid, #{rankingRunId}::uuid, #{stage}, #{configuredMode},
                 #{effectiveMode}, #{reasonCode}, #{reasonSummary}, COALESCE(#{createdAt}, now()))
            """)
    int insertEvent(RagFallbackEvent event);
}
