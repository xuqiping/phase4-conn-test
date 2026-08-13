package com.superprogrammer.knowledge.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.entity.RagRetrievalRun;
import com.superprogrammer.knowledge.entity.RagModelCall;
import com.superprogrammer.knowledge.entity.RagRankingRun;
import com.superprogrammer.knowledge.mapper.RagModelCallMapper;
import com.superprogrammer.knowledge.mapper.RagRankingRunMapper;
import com.superprogrammer.knowledge.mapper.RagRetrievalRunMapper;
import com.superprogrammer.knowledge.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** RAG Trace 生命周期持久化咽喉。观测写失败只告警，不覆盖主业务结果。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagTraceService {
    private final RagRetrievalRunMapper retrievalMapper;
    private final RagRankingRunMapper rankingMapper;
    private final RagModelCallMapper modelCallMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RetrievalScope beginRetrieval(List<Long> kbIds, String query, Long userId, String queryType) {
        String traceId = firstText(MDC.get("traceId"), UUID.randomUUID().toString().replace("-", ""));
        String runId = UUID.randomUUID().toString();
        List<Long> normalizedKbIds = kbIds == null ? List.of() : new ArrayList<>(kbIds);
        normalizedKbIds.sort(Long::compareTo);
        String kbJson = toJson(normalizedKbIds);
        RagRetrievalRun row = new RagRetrievalRun();
        row.setId(runId); row.setTraceId(traceId); row.setTenantId(1L); row.setUserId(userId);
        row.setKbIds(kbJson); row.setQueryHash(HashUtil.sha256(query)); row.setQueryType(queryType);
        row.setStatus("RUNNING"); row.setStartedAt(OffsetDateTime.now());
        safe(() -> retrievalMapper.insertRun(row), "创建 retrieval run", runId);
        RagTraceContext.Scope context = RagTraceContext.open(new RagTraceContext.State(
                traceId, runId, null, null, null, userId, kbJson));
        log.info("RAG 检索开始 queryType={}", queryType);
        return new RetrievalScope(runId, System.nanoTime(), context);
    }

    public ModelCallScope beginModelCall(String model, String provider, String input, String defaultPurpose) {
        RagTraceContext.State parent = RagTraceContext.current();
        if (parent == null || parent.retrievalRunId() == null) return new ModelCallScope();
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String callId = UUID.randomUUID().toString();
        String purpose = firstText(parent.callPurpose(), defaultPurpose);
        RagModelCall row = new RagModelCall();
        row.setId(callId); row.setTraceId(parent.traceId()); row.setRetrievalRunId(parent.retrievalRunId());
        row.setRankingRunId(parent.rankingRunId()); row.setModelRequestId(requestId);
        row.setCallPurpose(purpose); row.setModelName(model); row.setProviderName(provider);
        row.setInputHash(HashUtil.sha256(input)); row.setStatus("RUNNING"); row.setStartedAt(OffsetDateTime.now());
        safe(() -> modelCallMapper.insertCall(row), "创建 model call", callId);
        RagTraceContext.State callState = parent.withModelCall(requestId, purpose);
        RagTraceContext.Scope context = RagTraceContext.open(callState);
        log.info("RAG 模型调用开始 model={} provider={}", model, provider);
        return new ModelCallScope(callId, System.nanoTime(), callState, context);
    }

    public RankingScope beginRanking(String configuredMode, String effectiveMode, Long modelConfigId,
                                     String configVersion, int candidateCount, String candidateSummary,
                                     String fallbackReason) {
        RagTraceContext.State parent = RagTraceContext.current();
        if (parent == null || parent.retrievalRunId() == null) return new RankingScope();
        String rankingId = UUID.randomUUID().toString();
        RagRankingRun row = new RagRankingRun();
        row.setId(rankingId); row.setRetrievalRunId(parent.retrievalRunId());
        row.setConfiguredMode(firstText(configuredMode, "DISABLED"));
        row.setEffectiveMode(firstText(effectiveMode, "DISABLED"));
        row.setModelConfigId(modelConfigId); row.setRankingConfigVersion(configVersion);
        row.setCandidateCount(candidateCount); row.setFinalCount(0);
        row.setCandidateHash(HashUtil.sha256(candidateSummary)); row.setFallbackReason(truncate(fallbackReason));
        row.setStatus("RUNNING"); row.setStartedAt(OffsetDateTime.now());
        safe(() -> rankingMapper.insertRun(row), "创建 ranking run", rankingId);
        RagTraceContext.Scope context = RagTraceContext.open(parent.withRanking(rankingId));
        log.info("RAG 重排开始 configuredMode={} effectiveMode={} candidates={}",
                configuredMode, effectiveMode, candidateCount);
        return new RankingScope(rankingId, System.nanoTime(), context);
    }

    private void finish(String runId, long startNanos, String status, String resultState,
                        String errorCode, String errorSummary) {
        long latency = (System.nanoTime() - startNanos) / 1_000_000L;
        safe(() -> retrievalMapper.finishRun(runId, status, resultState, latency,
                errorCode, truncate(errorSummary)), "结束 retrieval run", runId);
        log.info("RAG 检索结束 status={} resultState={} latencyMs={}", status, resultState, latency);
    }

    private void safe(Runnable write, String action, String id) {
        try { write.run(); } catch (Exception e) { log.warn("{}失败(不阻塞主链) id={} : {}", action, id, e.toString()); }
    }

    private String toJson(List<Long> ids) {
        try { return objectMapper.writeValueAsString(ids); } catch (Exception ignored) { return "[]"; }
    }
    private static String firstText(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String truncate(String value) { return value == null ? null : value.substring(0, Math.min(1000, value.length())); }

    public final class RetrievalScope implements AutoCloseable {
        private final String runId; private final long startNanos; private final RagTraceContext.Scope context;
        private boolean finished;
        private RetrievalScope(String runId, long startNanos, RagTraceContext.Scope context) {
            this.runId = runId; this.startNanos = startNanos; this.context = context;
        }
        public String runId() { return runId; }
        public void succeed(String resultState) { complete("SUCCEEDED", resultState, null, null); }
        public void abstain(String resultState) { complete("ABSTAINED", resultState, null, null); }
        public void fail(String errorCode, String summary) { complete("FAILED", "ERROR", errorCode, summary); }
        private void complete(String status, String result, String code, String summary) {
            if (finished) return; finished = true;
            finish(runId, startNanos, status, result, code, summary);
        }
        @Override public void close() {
            if (!finished) fail("UNFINISHED", "RAG retrieval scope closed without terminal state");
            context.close();
        }
    }

    public final class ModelCallScope implements AutoCloseable {
        private final String callId; private final long startNanos; private final RagTraceContext.State callState;
        private final RagTraceContext.Scope context;
        private final boolean noop; private boolean finished;
        private boolean detached;
        private ModelCallScope(String callId, long startNanos, RagTraceContext.State callState,
                               RagTraceContext.Scope context) {
            this.callId = callId; this.startNanos = startNanos; this.callState = callState;
            this.context = context; this.noop = false;
        }
        private ModelCallScope() {
            this.callId = null; this.startNanos = 0; this.callState = null; this.context = null; this.noop = true;
        }
        public void succeed(String output, Integer promptTokens, Integer completionTokens) {
            finish("SUCCEEDED", output, promptTokens, completionTokens, null);
        }
        public void fail(String summary) { finish("FAILED", null, null, null, summary); }
        public void cancel() { finish("CANCELLED", null, null, null, "cancelled"); }
        public void detach() {
            if (noop || detached) return;
            context.close();
            detached = true;
        }
        public void runWithContext(Runnable action) {
            if (noop) { action.run(); return; }
            if (!detached) { action.run(); return; }
            try (var ignored = RagTraceContext.open(callState)) { action.run(); }
        }
        private void finish(String status, String output, Integer promptTokens, Integer completionTokens, String error) {
            if (noop || finished) return; finished = true;
            long latency = (System.nanoTime() - startNanos) / 1_000_000L;
            safe(() -> modelCallMapper.finishCall(callId, status, HashUtil.sha256(output), promptTokens,
                    completionTokens, latency, truncate(error)), "结束 model call", callId);
            log.info("RAG 模型调用结束 status={} latencyMs={}", status, latency);
        }
        @Override public void close() {
            if (noop) return;
            if (!finished) fail("model call scope closed without terminal state");
            if (!detached) context.close();
        }
    }

    public final class RankingScope implements AutoCloseable {
        private final String rankingId; private final long startNanos; private final RagTraceContext.Scope context;
        private final boolean noop; private boolean finished;
        private RankingScope(String rankingId, long startNanos, RagTraceContext.Scope context) {
            this.rankingId = rankingId; this.startNanos = startNanos; this.context = context; this.noop = false;
        }
        private RankingScope() { this.rankingId = null; this.startNanos = 0; this.context = null; this.noop = true; }
        public void succeed(int finalCount) { finish("SUCCEEDED", finalCount, null); }
        public void fail(String reason) { finish("FAILED", 0, reason); }
        private void finish(String status, int finalCount, String reason) {
            if (noop || finished) return; finished = true;
            long latency = (System.nanoTime() - startNanos) / 1_000_000L;
            safe(() -> rankingMapper.finishRun(rankingId, status, finalCount, latency, truncate(reason)),
                    "结束 ranking run", rankingId);
            log.info("RAG 重排结束 status={} finalCount={} latencyMs={}", status, finalCount, latency);
        }
        @Override public void close() {
            if (noop) return;
            if (!finished) fail("ranking scope closed without terminal state");
            context.close();
        }
    }
}
