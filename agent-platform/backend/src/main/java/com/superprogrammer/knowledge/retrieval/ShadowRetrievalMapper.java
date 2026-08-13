package com.superprogrammer.knowledge.retrieval;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.util.List;

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

    @Select("""
            <script>
            SELECT id,tenant_id AS tenantId,kb_id AS kbId,user_id AS userId,
                   champion_trace_id AS championTraceId,challenger_trace_id AS challengerTraceId,
                   champion_version AS championVersion,challenger_version AS challengerVersion,
                   status,ranked_chunk_ids::text AS rankedChunkIds,cost,error_summary AS errorSummary,
                   created_at AS createdAt
              FROM rag_shadow_comparisons
             WHERE tenant_id=#{tenantId} AND kb_id=#{kbId}
             <if test="status != null and status != ''">AND status=#{status}</if>
             ORDER BY created_at DESC
             LIMIT #{limit}
            </script>
            """)
    List<Row> findRecent(@Param("tenantId") long tenantId, @Param("kbId") long kbId,
                         @Param("status") String status, @Param("limit") int limit);
    class Row {
        public Long id; public Long tenantId; public Long kbId; public Long userId;
        public String championTraceId; public String challengerTraceId; public String championVersion;
        public String challengerVersion; public String status; public String rankedChunkIds;
        public Double cost; public String errorSummary; public java.time.OffsetDateTime createdAt;
    }
}
