package com.superprogrammer.knowledge.migration;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RagRolloutServiceTest {

    @Test
    void keepsBucketStableAndRejectsUnsafeRollout() {
        RagRolloutService service = new RagRolloutService(kbId -> 3);
        RagRolloutService.Readiness ready = new RagRolloutService.Readiness(true, true, true);

        assertThrows(IllegalArgumentException.class,
                () -> service.configure(7L, 10, "cfg-1", 9L, true, ready));
        assertThrows(IllegalStateException.class,
                () -> service.configure(7L, 20, "cfg-1", 9L, true,
                        new RagRolloutService.Readiness(false, true, true)));

        RagRolloutService.RolloutState state = service.configure(7L, 20, "cfg-1", 9L, true, ready);
        assertEquals(20, state.percentage());
        assertEquals(9L, state.operatorId());
        assertEquals(state.challengerSelected(1001L), state.challengerSelected(1001L));
        assertEquals(state.challengerSelected(1001L), service.useChallenger(7L, 1001L));
    }

    @Test
    void rollbackRestoresPreviousRouteAndInvalidatesCache() {
        java.util.List<String> switchedSnapshots = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<String> activeSnapshot =
                new java.util.concurrent.atomic.AtomicReference<>("snap-a");
        RagRolloutService service = new RagRolloutService(kbId -> 4,
                new RagRolloutService.InMemoryRepository(),
                new RagRolloutService.IndexRoute() {
                    public String activeSnapshot(long kbId) { return activeSnapshot.get(); }
                    public void switchSnapshot(long kbId, String snapshotId) { switchedSnapshots.add(snapshotId); }
                });
        RagRolloutService.Readiness ready = new RagRolloutService.Readiness(true, true, true);
        service.configure(7L, 20, "cfg-1", 9L, true, ready);
        activeSnapshot.set("snap-b");
        service.configure(7L, 50, "cfg-2", 10L, true, ready);

        RagRolloutService.RolloutState rolledBack = service.rollback(7L, 11L, true);

        assertEquals(20, rolledBack.percentage());
        assertEquals("cfg-1", rolledBack.configVersion());
        assertEquals(11L, rolledBack.operatorId());
        assertEquals(1, service.cacheInvalidations(7L));
        assertEquals(java.util.List.of("snap-a"), switchedSnapshots);
    }
}
