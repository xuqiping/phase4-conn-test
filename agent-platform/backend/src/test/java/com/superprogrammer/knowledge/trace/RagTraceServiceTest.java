package com.superprogrammer.knowledge.trace;

import com.superprogrammer.knowledge.mapper.RagModelCallMapper;
import com.superprogrammer.knowledge.mapper.RagRankingRunMapper;
import com.superprogrammer.knowledge.mapper.RagRetrievalRunMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagTraceServiceTest {
    @Mock RagRetrievalRunMapper retrievalMapper;
    @Mock RagRankingRunMapper rankingMapper;
    @Mock RagModelCallMapper modelCallMapper;

    @Test
    void AC_RAG_TRACE_003_retrievalLifecyclePersistsSameTraceAndTerminalState() {
        MDC.put("traceId", "web-trace-9");
        RagTraceService service = new RagTraceService(retrievalMapper, rankingMapper, modelCallMapper);
        try (var run = service.beginRetrieval(List.of(2L, 1L), "secret query", 7L, "ASK")) {
            assertEquals("web-trace-9", RagTraceContext.current().traceId());
            run.succeed("SUPPORTED");
        } finally { MDC.clear(); RagTraceContext.clear(); }

        verify(retrievalMapper).insertRun(argThat(row ->
                "web-trace-9".equals(row.getTraceId()) && !"secret query".equals(row.getQueryHash())
                        && "RUNNING".equals(row.getStatus())));
        verify(retrievalMapper).finishRun(anyString(), eq("SUCCEEDED"), eq("SUPPORTED"), anyLong(), isNull(), isNull());
    }

    @Test
    void AC_RAG_TRACE_004_modelCallUsesCurrentRetrievalAndStoresOnlyHashes() {
        RagTraceService service = new RagTraceService(retrievalMapper, rankingMapper, modelCallMapper);
        try (var retrieval = service.beginRetrieval(List.of(1L), "query", 7L, "ASK")) {
            try (var call = service.beginModelCall("chat-model", "provider-a", "full prompt", "ANSWER_GENERATION")) {
                assertNotNull(MDC.get("modelRequestId"));
                call.succeed("full output", 12, 4);
            }
            retrieval.succeed("SUPPORTED");
        } finally { MDC.clear(); RagTraceContext.clear(); }

        verify(modelCallMapper).insertCall(argThat(row ->
                "ANSWER_GENERATION".equals(row.getCallPurpose())
                        && !"full prompt".equals(row.getInputHash())
                        && "RUNNING".equals(row.getStatus())));
        verify(modelCallMapper).finishCall(anyString(), eq("SUCCEEDED"),
                argThat(hash -> hash != null && !"full output".equals(hash)), eq(12), eq(4), anyLong(), isNull());
    }

    @Test
    void AC_RAG_TRACE_005_rankingLifecycleLinksContextAndPersistsRealEffectiveMode() {
        RagTraceService service = new RagTraceService(retrievalMapper, rankingMapper, modelCallMapper);
        try (var retrieval = service.beginRetrieval(List.of(1L), "query", 7L, "ASK")) {
            try (var ranking = service.beginRanking("LLM", "HEURISTIC_PROXY", null,
                    "rc-1", 12, "11,12,13", "P0_PROXY")) {
                assertNotNull(MDC.get("rankingRunId"));
                ranking.succeed(5);
            }
            retrieval.succeed("SUPPORTED");
        } finally { MDC.clear(); RagTraceContext.clear(); }

        verify(rankingMapper).insertRun(argThat(row ->
                "LLM".equals(row.getConfiguredMode())
                        && "HEURISTIC_PROXY".equals(row.getEffectiveMode())
                        && !"11,12,13".equals(row.getCandidateHash())
                        && "RUNNING".equals(row.getStatus())));
        verify(rankingMapper).finishRun(anyString(), eq("SUCCEEDED"), eq(5), anyLong(), isNull());
    }

    @Test
    void AC_RAG_TRACE_006_detachedModelCallRestoresContextInsideAsyncCallbackOnly() {
        RagTraceService service = new RagTraceService(retrievalMapper, rankingMapper, modelCallMapper);
        try (var retrieval = service.beginRetrieval(List.of(1L), "query", 7L, "ASK")) {
            String retrievalId = MDC.get("retrievalRunId");
            try (var call = service.beginModelCall("chat-model", "provider-a", "prompt", "ANSWER_GENERATION")) {
                String modelRequestId = MDC.get("modelRequestId");
                call.detach();
                assertNull(MDC.get("modelRequestId"));
                assertEquals(retrievalId, MDC.get("retrievalRunId"));
                call.runWithContext(() -> assertEquals(modelRequestId, MDC.get("modelRequestId")));
                assertNull(MDC.get("modelRequestId"));
                call.cancel();
            }
            retrieval.succeed("SUPPORTED");
        } finally { MDC.clear(); RagTraceContext.clear(); }
    }
}
