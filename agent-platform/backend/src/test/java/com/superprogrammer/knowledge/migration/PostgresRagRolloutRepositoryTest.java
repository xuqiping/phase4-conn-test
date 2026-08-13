package com.superprogrammer.knowledge.migration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class PostgresRagRolloutRepositoryTest {
    @Test
    void persistsCurrentAndPreviousStateForRestartSafeRollback() {
        RagRolloutMapper mapper = mock(RagRolloutMapper.class);
        PostgresRagRolloutRepository repository = new PostgresRagRolloutRepository(mapper);
        RagRolloutService.RolloutState first = new RagRolloutService.RolloutState(7L, 5, "cfg-1", 9L);
        RagRolloutService.RolloutState second = new RagRolloutService.RolloutState(7L, 20, "cfg-2", 10L);

        repository.save(second, first);
        when(mapper.find(7L)).thenReturn(RagRolloutMapper.RolloutRow.of(second, first));

        RagRolloutService.RolloutHistory loaded = repository.find(7L);
        assertEquals("cfg-2", loaded.current().configVersion());
        assertEquals("cfg-1", loaded.previous().configVersion());
        verify(mapper).upsert(any());
    }
}
