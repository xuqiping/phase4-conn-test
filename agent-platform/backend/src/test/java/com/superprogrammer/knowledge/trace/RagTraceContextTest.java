package com.superprogrammer.knowledge.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RagTraceContextTest {
    @AfterEach void clear() { MDC.clear(); RagTraceContext.clear(); }

    @Test
    void AC_RAG_TRACE_001_scopeWritesAndRestoresMdcAndThreadLocal() {
        MDC.put("traceId", "http-trace");
        try (var ignored = RagTraceContext.open(new RagTraceContext.State(
                "http-trace", "retrieval-1", "ranking-1", null, "QUERY_EXPANSION", 7L, "[1,2]"))) {
            assertEquals("retrieval-1", MDC.get("retrievalRunId"));
            assertEquals("ranking-1", MDC.get("rankingRunId"));
            assertEquals("QUERY_EXPANSION", MDC.get("callPurpose"));
            assertEquals("retrieval-1", RagTraceContext.current().retrievalRunId());
        }
        assertEquals("http-trace", MDC.get("traceId"));
        assertNull(MDC.get("retrievalRunId"));
        assertNull(RagTraceContext.current());
    }

    @Test
    void AC_RAG_TRACE_002_wrapPropagatesSnapshotToBareThreadAndCleansIt() throws Exception {
        try (var ignored = RagTraceContext.open(new RagTraceContext.State(
                "trace-2", "retrieval-2", null, null, "ANSWER_GENERATION", 8L, "[3]"))) {
            java.util.concurrent.atomic.AtomicReference<Map<String, String>> seen = new java.util.concurrent.atomic.AtomicReference<>();
            Thread thread = new Thread(RagTraceContext.wrap(() -> seen.set(MDC.getCopyOfContextMap())));
            thread.start(); thread.join();
            assertEquals("retrieval-2", seen.get().get("retrievalRunId"));
            assertEquals("ANSWER_GENERATION", seen.get().get("callPurpose"));
        }
    }
}
