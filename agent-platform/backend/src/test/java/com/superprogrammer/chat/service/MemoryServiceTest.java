package com.superprogrammer.chat.service;

import com.superprogrammer.chat.entity.UserMemory;
import com.superprogrammer.chat.mapper.UserMemoryMapper;
import com.superprogrammer.chat.service.internal.MemoryBlockClassifier;
import com.superprogrammer.chat.service.internal.MemoryConflictJudge;
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

/**
 * V27 记忆冲突重构后的 MemoryService 单测。
 * 注：旧 extractMemoriesAsync(long,String,String) 已被同步 processMemory 取代，
 * 其抽取/归块/冲突判定链由 judge/classifier/conflictService 协作，覆盖见后续阶段7 扩展。
 * 此处仅守 buildMemoryContext 注入契约（FLAGGED 前缀 + counterpart 聚合）。
 */
@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock private UserMemoryMapper memoryMapper;
    @Mock private MemoryBlockClassifier classifier;
    @Mock private MemoryConflictJudge judge;
    @Mock private MemoryConflictService conflictService;

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
    void buildMemoryContext_flagsConflictingMemoryWithCounterpart() {
        testMemory.setConflictId(7L);
        UserMemory counterpart = new UserMemory();
        counterpart.setId(2L);
        counterpart.setUserId(100L);
        counterpart.setMemoryValue("Python");
        // 主行查询（conflictId!=null 过滤前已含）+ counterpart 查询（同 conflictId 排除自身）
        when(memoryMapper.selectList(any())).thenReturn(List.of(testMemory), List.of(counterpart));

        String context = memoryService.buildMemoryContext(100L);

        assertNotNull(context);
        assertTrue(context.contains("[⚠️冲突]"));
        assertTrue(context.contains("Java"));
        assertTrue(context.contains("Python"));
    }
}
