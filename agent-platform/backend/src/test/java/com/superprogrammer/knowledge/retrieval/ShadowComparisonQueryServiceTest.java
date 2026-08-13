package com.superprogrammer.knowledge.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShadowComparisonQueryServiceTest {
    @Test
    void scopesEveryQueryByTenantAndKnowledgeBase() {
        ShadowRetrievalMapper mapper = mock(ShadowRetrievalMapper.class);
        ShadowComparisonQueryService service = new ShadowComparisonQueryService(mapper);
        when(mapper.findRecent(3L, 9L, "FAILED", 20)).thenReturn(List.of());

        assertEquals(List.of(), service.findRecent(3L, 9L, "FAILED", 20));

        verify(mapper).findRecent(3L, 9L, "FAILED", 20);
    }
}
