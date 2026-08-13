package com.superprogrammer.knowledge.mapper;

import com.superprogrammer.knowledge.AbstractIntegrationTest;
import com.superprogrammer.knowledge.entity.RagFallbackEvent;
import com.superprogrammer.knowledge.entity.RagModelCall;
import com.superprogrammer.knowledge.entity.RagRankingRun;
import com.superprogrammer.knowledge.entity.RagRetrievalRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** RAG-FR-08：真实 PostgreSQL 验证 retrieval→ranking→model→fallback 关联链。 */
class RagPipelineTraceMapperIT extends AbstractIntegrationTest {

    @Autowired private RagRetrievalRunMapper retrievalMapper;
    @Autowired private RagRankingRunMapper rankingMapper;
    @Autowired private RagModelCallMapper modelCallMapper;
    @Autowired private RagFallbackEventMapper fallbackMapper;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("TRUNCATE rag_fallback_events, rag_model_calls, rag_ranking_runs, rag_retrieval_runs CASCADE");
    }

    @Test
    void insertsCompleteTraceChainWithoutSensitivePayload() {
        String retrievalId = UUID.randomUUID().toString();
        String rankingId = UUID.randomUUID().toString();
        String traceId = "trace-" + UUID.randomUUID();

        RagRetrievalRun retrieval = new RagRetrievalRun();
        retrieval.setId(retrievalId);
        retrieval.setTraceId(traceId);
        retrieval.setTenantId(1L);
        retrieval.setUserId(7L);
        retrieval.setKbIds("[11,12]");
        retrieval.setQueryHash("query-hash");
        retrieval.setQueryType("POLICY");
        retrieval.setStatus("RUNNING");
        assertEquals(1, retrievalMapper.insertRun(retrieval));

        RagRankingRun ranking = new RagRankingRun();
        ranking.setId(rankingId);
        ranking.setRetrievalRunId(retrievalId);
        ranking.setConfiguredMode("LLM");
        ranking.setEffectiveMode("DISABLED");
        ranking.setCandidateCount(30);
        ranking.setFinalCount(10);
        ranking.setStatus("SUCCEEDED");
        assertEquals(1, rankingMapper.insertRun(ranking));

        RagModelCall call = new RagModelCall();
        call.setId(UUID.randomUUID().toString());
        call.setTraceId(traceId);
        call.setRetrievalRunId(retrievalId);
        call.setRankingRunId(rankingId);
        call.setModelRequestId("model-request-1");
        call.setCallPurpose("RAG_LLM_RANK");
        call.setInputHash("input-hash");
        call.setOutputHash("output-hash");
        call.setStatus("SUCCEEDED");
        assertEquals(1, modelCallMapper.insertCall(call));

        RagFallbackEvent fallback = new RagFallbackEvent();
        fallback.setTraceId(traceId);
        fallback.setRetrievalRunId(retrievalId);
        fallback.setRankingRunId(rankingId);
        fallback.setStage("RANKING");
        fallback.setConfiguredMode("LLM");
        fallback.setEffectiveMode("DISABLED");
        fallback.setReasonCode("TEST_FALLBACK");
        assertEquals(1, fallbackMapper.insertEvent(fallback));

        Integer linked = jdbc.queryForObject("""
                SELECT count(*) FROM rag_retrieval_runs rr
                JOIN rag_ranking_runs rk ON rk.retrieval_run_id = rr.id
                JOIN rag_model_calls mc ON mc.ranking_run_id = rk.id
                JOIN rag_fallback_events fe ON fe.ranking_run_id = rk.id
                WHERE rr.trace_id = ? AND mc.input_hash = 'input-hash'
                """, Integer.class, traceId);
        assertEquals(1, linked);
    }
}
