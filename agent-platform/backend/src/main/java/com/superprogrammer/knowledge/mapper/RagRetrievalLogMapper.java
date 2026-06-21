package com.superprogrammer.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.knowledge.entity.RagRetrievalLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * rag_retrieval_logs 审计流：写入走自定义 @Insert（halfvec/json 列规避默认 handler）；
 * 查询/删除走 BaseMapper（管理员审计 / 清理，见 RagRetrievalLogService）。无业务更新。
 */
@Mapper
public interface RagRetrievalLogMapper extends BaseMapper<RagRetrievalLog> {

    @Insert("""
            INSERT INTO rag_retrieval_logs
                (trace_id, tenant_id, user_id, identity_type, kb_ids, query, rewritten_query, mode,
                 candidates_l0, l2_lexical_fallback, evidence_l2, memory_hits, crag_verdict,
                 token_budget, latency_ms, created_at)
            VALUES
                (#{traceId}, #{tenantId}, #{userId}, #{identityType}, #{kbIds}, #{query}, #{rewrittenQuery}, #{mode},
                 #{candidatesL0}, #{l2LexicalFallback}, #{evidenceL2}, #{memoryHits}, #{cragVerdict},
                 #{tokenBudget}, #{latencyMs}, now())
            """)
    void insertTrace(RagRetrievalLog log);
}

