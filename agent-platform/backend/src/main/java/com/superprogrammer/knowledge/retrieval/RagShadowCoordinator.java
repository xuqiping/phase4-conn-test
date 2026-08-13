package com.superprogrammer.knowledge.retrieval;

import com.superprogrammer.billing.context.BillingContext;
import com.superprogrammer.knowledge.migration.RagRolloutService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Starts a best-effort shadow comparison after the Champion response has been computed. */
@Slf4j
public class RagShadowCoordinator {
    private final ShadowRetrievalService shadowService;
    private final RagRolloutService rolloutService;
    private final Executor executor;
    private final RagShadowProperties properties;

    public RagShadowCoordinator(ShadowRetrievalService shadowService, RagRolloutService rolloutService,
                                Executor executor, RagShadowProperties properties) {
        this.shadowService = shadowService; this.rolloutService = rolloutService;
        this.executor = executor; this.properties = properties;
    }

    public void afterChampion(long tenantId, long kbId, long userId, String championTraceId,
                              String championVersion, Supplier<ShadowRetrievalService.ChallengerResult> challenger) {
        if (!properties.isEnabled()) return;
        RagRolloutService.RolloutState state = rolloutService.status(kbId);
        String challengerVersion = state.configVersion();
        if (state.percentage() <= 0 || challengerVersion == null || challengerVersion.equals(championVersion)) return;
        boolean sampled = stableSample(kbId, userId, properties.getSamplePercentage());
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        executor.execute(() -> {
            try {
                if (mdc == null) MDC.clear(); else MDC.setContextMap(mdc);
                MDC.remove("traceId"); MDC.remove("retrievalRunId"); MDC.remove("rankingRunId");
                BillingContext.set(userId);
                shadowService.run(new ShadowRetrievalService.ShadowRequest(
                        tenantId, kbId, userId, championTraceId, championVersion, challengerVersion,
                        sampled, properties.getBudgetPerRequest(), Duration.ofMillis(properties.getTimeoutMs())), challenger);
            } catch (Exception error) {
                log.warn("RAG shadow comparison failed kbId={} errorType={}", kbId, error.getClass().getSimpleName());
            } finally {
                BillingContext.clear(); MDC.clear();
            }
        });
    }

    private static boolean stableSample(long kbId, long userId, int percentage) {
        if (percentage <= 0) return false;
        if (percentage >= 100) return true;
        return Math.floorMod(Long.hashCode(kbId * 31 + userId), 100) < percentage;
    }
}
