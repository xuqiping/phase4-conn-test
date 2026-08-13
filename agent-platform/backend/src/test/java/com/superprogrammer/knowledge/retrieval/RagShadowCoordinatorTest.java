package com.superprogrammer.knowledge.retrieval;

import com.superprogrammer.knowledge.migration.RagRolloutService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RagShadowCoordinatorTest {

    @Test
    void challengerFailureNeverChangesChampionResponseAndIsPersisted() {
        List<ShadowRetrievalService.ShadowRecord> records = new ArrayList<>();
        var challengerExecutor = Executors.newSingleThreadExecutor();
        try {
            ShadowRetrievalService shadow = new ShadowRetrievalService(challengerExecutor, records::add);
            RagRolloutService rollout = new RagRolloutService(kbId -> 0);
            rollout.configure(9L, 20, "rc-next", 1L, true,
                    new RagRolloutService.Readiness(true, true, true));
            Executor direct = Runnable::run;
            RagShadowCoordinator coordinator = new RagShadowCoordinator(
                    shadow, rollout, direct, new RagShadowProperties(true, 100, 10, 1000));

            assertDoesNotThrow(() -> coordinator.afterChampion(
                    1L, 9L, 7L, "trace-champion", "rc-current",
                    () -> { throw new IllegalStateException("secret query must not leak"); }));

            assertEquals(1, records.size());
            assertEquals("FAILED", records.get(0).status());
            assertEquals("IllegalStateException", records.get(0).errorSummary());
            assertEquals("rc-next", records.get(0).challengerVersion());
        } finally {
            challengerExecutor.shutdownNow();
        }
    }
}
