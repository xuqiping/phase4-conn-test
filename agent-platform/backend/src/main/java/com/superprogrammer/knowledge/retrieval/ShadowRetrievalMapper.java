package com.superprogrammer.knowledge.retrieval;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface ShadowRetrievalMapper {
    @Insert("""
            INSERT INTO rag_shadow_comparisons(tenant_id,kb_id,user_id,champion_trace_id,challenger_trace_id,
              champion_version,challenger_version,status,ranked_chunk_ids,cost,error_summary,created_at)
            VALUES(#{tenantId},#{kbId},#{userId},#{championTraceId},#{challengerTraceId},#{championVersion},
              #{challengerVersion},#{status},#{rankedChunkIds}::jsonb,#{cost},#{errorSummary},#{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Row row);
    class Row {
        public Long id; public Long tenantId; public Long kbId; public Long userId;
        public String championTraceId; public String challengerTraceId; public String championVersion;
        public String challengerVersion; public String status; public String rankedChunkIds;
        public Double cost; public String errorSummary; public java.time.OffsetDateTime createdAt;
    }
}
