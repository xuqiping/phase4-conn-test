package com.superprogrammer.knowledge.trace;

import org.slf4j.MDC;

import java.util.Map;

/** RAG 请求级关联上下文；只保存 ID/用途，不保存 query、prompt 或 chunk。 */
public final class RagTraceContext {
    public static final String RETRIEVAL_RUN_ID = "retrievalRunId";
    public static final String RANKING_RUN_ID = "rankingRunId";
    public static final String MODEL_REQUEST_ID = "modelRequestId";
    public static final String CALL_PURPOSE = "callPurpose";
    public static final String KB_IDS = "kbIds";

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private RagTraceContext() {}

    public record State(String traceId, String retrievalRunId, String rankingRunId,
                        String modelRequestId, String callPurpose, Long userId, String kbIds) {
        public State withModelCall(String requestId, String purpose) {
            return new State(traceId, retrievalRunId, rankingRunId, requestId, purpose, userId, kbIds);
        }
        public State withRanking(String rankingId) {
            return new State(traceId, retrievalRunId, rankingId, modelRequestId, callPurpose, userId, kbIds);
        }
        public State withCallPurpose(String purpose) {
            return new State(traceId, retrievalRunId, rankingRunId, modelRequestId, purpose, userId, kbIds);
        }
    }

    public static State current() { return CURRENT.get(); }

    /** 临时覆盖模型调用用途；无 RAG 上下文时为 no-op。 */
    public static Scope openPurpose(String purpose) {
        State current = CURRENT.get();
        return current == null ? () -> { } : open(current.withCallPurpose(purpose));
    }

    public static Scope open(State state) {
        State previousState = CURRENT.get();
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        apply(state);
        return () -> restore(previousState, previousMdc);
    }

    public static Runnable wrap(Runnable runnable) {
        State snapshot = CURRENT.get();
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        return () -> {
            State previousState = CURRENT.get();
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            try {
                if (mdcSnapshot == null) MDC.clear(); else MDC.setContextMap(mdcSnapshot);
                CURRENT.set(snapshot);
                applyMdc(snapshot);
                runnable.run();
            } finally {
                restore(previousState, previousMdc);
            }
        };
    }

    public static void clear() {
        CURRENT.remove();
        removeRagMdc();
    }

    private static void apply(State state) {
        if (state == null) CURRENT.remove(); else CURRENT.set(state);
        applyMdc(state);
    }

    private static void applyMdc(State state) {
        removeRagMdc();
        if (state == null) return;
        put("traceId", state.traceId());
        put(RETRIEVAL_RUN_ID, state.retrievalRunId());
        put(RANKING_RUN_ID, state.rankingRunId());
        put(MODEL_REQUEST_ID, state.modelRequestId());
        put(CALL_PURPOSE, state.callPurpose());
        put(KB_IDS, state.kbIds());
    }

    private static void restore(State state, Map<String, String> mdc) {
        if (state == null) CURRENT.remove(); else CURRENT.set(state);
        if (mdc == null) MDC.clear(); else MDC.setContextMap(mdc);
    }

    private static void put(String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) MDC.put(key, String.valueOf(value));
    }

    private static void removeRagMdc() {
        MDC.remove(RETRIEVAL_RUN_ID); MDC.remove(RANKING_RUN_ID); MDC.remove(MODEL_REQUEST_ID);
        MDC.remove(CALL_PURPOSE); MDC.remove(KB_IDS);
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable { @Override void close(); }
}
