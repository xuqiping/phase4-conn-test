package com.superprogrammer.chat.service;

import com.superprogrammer.chat.entity.UserMemory;
import com.superprogrammer.chat.mapper.UserMemoryMapper;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock private UserMemoryMapper memoryMapper;
    @Mock private LlmGateway llmGateway;

    @InjectMocks
    private MemoryService memoryService;

    private UserMemory testMemory;

    @BeforeEach
    void setUp() {
        testMemory = new UserMemory();
        testMemory.setId(1L);
        testMemory.setUserId(100L);
        testMemory.setCategory("PREFERENCE");
        testMemory.setMemoryKey("language");
        testMemory.setMemoryValue("Java");
        testMemory.setConfidence(new BigDecimal("0.9"));
    }

    @Test
    void buildMemoryContext_withMemories() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(testMemory));

        String context = memoryService.buildMemoryContext(100L);

        assertNotNull(context);
        assertTrue(context.contains("PREFERENCE"));
        assertTrue(context.contains("language"));
        assertTrue(context.contains("Java"));
    }

    @Test
    void buildMemoryContext_noMemories() {
        when(memoryMapper.selectList(any())).thenReturn(List.of());

        String context = memoryService.buildMemoryContext(100L);

        assertNull(context);
    }

    @Test
    void extractMemoriesAsync_parsesValidJson() {
        when(llmGateway.chat(any())).thenReturn(LlmResponse.builder()
                .content("""
                        [{"category":"PREFERENCE","key":"editor","value":"VSCode","confidence":0.8}]""")
                .build());
        when(memoryMapper.selectOne(any())).thenReturn(null);
        when(memoryMapper.insert(any())).thenReturn(1);

        memoryService.extractMemoriesAsync(100L, "I use VSCode", "Good choice!");

        verify(memoryMapper, timeout(2000)).insert(any(UserMemory.class));
    }

    @Test
    void extractMemoriesAsync_emptyResponse() {
        when(llmGateway.chat(any())).thenReturn(LlmResponse.builder()
                .content("[]").build());

        memoryService.extractMemoriesAsync(100L, "Hello", "Hi");

        verify(memoryMapper, never()).insert(any());
    }

    @Test
    void extractMemoriesAsync_llmFailure_doesNotThrow() {
        when(llmGateway.chat(any())).thenThrow(new RuntimeException("LLM down"));

        assertDoesNotThrow(() -> memoryService.extractMemoriesAsync(100L, "test", "test"));
    }
}
