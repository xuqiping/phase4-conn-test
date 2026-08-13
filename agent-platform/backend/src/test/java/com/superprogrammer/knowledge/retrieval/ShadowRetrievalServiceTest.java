package com.superprogrammer.knowledge.retrieval;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShadowRetrievalServiceTest {
    @Test
    void samplesEnforcesBudgetAndPersistsTraceableComparison() {
        List<ShadowRetrievalService.ShadowRecord> records = new ArrayList<>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            ShadowRetrievalService service = new ShadowRetrievalService(executor, records::add);
            var result = service.run(new ShadowRetrievalService.ShadowRequest(
                    1L, 9L, 7L, "trace-c", "champion", "challenger", true, 10d, Duration.ofSeconds(1)),
                    () -> new ShadowRetrievalService.ChallengerResult("trace-x", List.of("11", "12"), 4d));

            assertEquals("SUCCEEDED", result.status());
            assertEquals("trace-x", records.get(0).challengerTraceId());
            assertEquals(List.of("11", "12"), records.get(0).rankedChunkIds());
        } finally {
            executor.shutdownNow();
        }
    }
}
