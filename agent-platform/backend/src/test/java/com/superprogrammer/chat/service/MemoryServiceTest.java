package com.superprogrammer.chat.service;

import com.superprogrammer.chat.entity.UserMemory;
import com.superprogrammer.chat.mapper.UserMemoryMapper;
import com.superprogrammer.chat.service.internal.MemoryBlockClassifier;
import com.superprogrammer.chat.service.internal.MemoryConflictJudge;
import com.superprogrammer.chat.service.internal.MemoryScope;
import com.superprogrammer.knowledge.service.QueryExpansionService;
import com.superprogrammer.knowledge.service.RagConfig;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * V27 记忆冲突重构后的 MemoryService 单测（V33 改 MemoryScope 签名）。
 * scope 用 globalOnly（includeGlobal=true, projectIds=[]）= 只读 is_global = 今天行为，契约不变。
 * 全量召回改走 mapper.findFullContext（限 scope）；向量/关键词召回 mapper 加 scope 两参。
 */
@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock private UserMemoryMapper memoryMapper;
    @Mock private MemoryBlockClassifier classifier;
    @Mock private MemoryConflictJudge judge;
    @Mock private MemoryConflictService conflictService;
    @Mock private LlmGateway llmGateway;
    @Mock private QueryExpansionService queryExpansion;
    @Mock private SystemSettingService systemSettingService;
    @Mock private ObjectMapper objectMapper;
    @Mock private com.superprogrammer.chat.service.internal.MemoryQueryCache queryCache;
    @Mock private java.util.concurrent.Executor memoryTaskExecutor;

    @InjectMocks
    private MemoryService memoryService;

    private UserMemory testMemory;
    private static final MemoryScope GLOBAL = MemoryScope.globalOnly(100L);

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

    // ============================ 全量召回（LLM_FULL_CONTEXT，限 scope）============================

    @Test
    void buildFullContext_withMemories() {
        when(memoryMapper.findFullContext(eq(100L), any(), anyBoolean(), any())).thenReturn(List.of(testMemory));

        String context = memoryService.buildFullContext(GLOBAL, null);

        assertNotNull(context);
        assertTrue(context.contains("PREFERENCE"));
        assertTrue(context.contains("language"));
        assertTrue(context.contains("Java"));
    }

    @Test
    void buildFullContext_noMemories() {
        when(memoryMapper.findFullContext(eq(100L), any(), anyBoolean(), any())).thenReturn(List.of());

        String context = memoryService.buildFullContext(GLOBAL, null);

        assertNull(context);
    }

    @Test
    void buildFullContext_flagsConflictingMemoryWithCounterpart() {
        testMemory.setConflictId(7L);
        UserMemory counterpart = new UserMemory();
        counterpart.setId(2L);
        counterpart.setUserId(100L);
        counterpart.setMemoryValue("Python");
        when(memoryMapper.findFullContext(eq(100L), any(), anyBoolean(), any())).thenReturn(List.of(testMemory));
        when(memoryMapper.findByConflictId(7L)).thenReturn(List.of(counterpart));

        String context = memoryService.buildFullContext(GLOBAL, null);

        assertNotNull(context);
        assertTrue(context.contains("[⚠️冲突]"));
        assertTrue(context.contains("Java"));
        assertTrue(context.contains("Python"));
    }

    @Test
    void buildFullContext_overThreshold_triggersTwoStageKeyFilter() {
        testMemory.setMemoryKey("language");
        List<UserMemory> many = new java.util.ArrayList<>();
        many.add(testMemory);
        for (int i = 2; i <= 21; i++) {
            UserMemory m = new UserMemory();
            m.setId((long) i);
            m.setUserId(100L);
            m.setMemoryKey("noise_key_" + i);
            m.setMemoryValue("noise" + i);
            m.setConfidence(new BigDecimal("0.9"));
            many.add(m);
        }
        when(memoryMapper.findFullContext(eq(100L), any(), anyBoolean(), any())).thenReturn(many);
        when(systemSettingService.getMemoryFullContextThreshold()).thenReturn(20);
        when(judge.selectRelevantKeysBlocks(eq("我用什么编程"), any(), any())).thenReturn(dims("language"));

        String context = memoryService.buildFullContext(GLOBAL, "我用什么编程");

        assertNotNull(context);
        assertTrue(context.contains("Java"));
        assertFalse(context.contains("noise2"));
    }

    @Test
    void buildFullContext_overThreshold_llmSelectsNone_returnsNull() {
        List<UserMemory> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            UserMemory m = new UserMemory();
            m.setId((long) i);
            m.setUserId(100L);
            m.setMemoryKey("k" + i);
            m.setMemoryValue("v" + i);
            m.setConfidence(new BigDecimal("0.9"));
            many.add(m);
        }
        when(memoryMapper.findFullContext(eq(100L), any(), anyBoolean(), any())).thenReturn(many);
        when(systemSettingService.getMemoryFullContextThreshold()).thenReturn(20);
        when(judge.selectRelevantKeysBlocks(any(), any(), any())).thenReturn(dimsEmpty());

        String context = memoryService.buildFullContext(GLOBAL, "无关问题");

        assertNull(context);
    }

    @Test
    void buildFullContext_zhKeyLanguage_usesKeyZh() {
        testMemory.setMemoryKey("language");
        testMemory.setMemoryKeyZh("编程语言");
        when(memoryMapper.findFullContext(eq(100L), any(), anyBoolean(), any())).thenReturn(List.of(testMemory));
        when(systemSettingService.getMemoryKeyLanguage()).thenReturn("ZH");

        String context = memoryService.buildFullContext(GLOBAL, null);

        assertNotNull(context);
        assertTrue(context.contains("编程语言"));
        assertTrue(context.contains("Java"));
    }

    @Test
    void buildFullContext_zhKeyLanguage_fallsBackToEnWhenKeyZhBlank() {
        testMemory.setMemoryKey("language");
        testMemory.setMemoryKeyZh("  ");
        when(memoryMapper.findFullContext(eq(100L), any(), anyBoolean(), any())).thenReturn(List.of(testMemory));
        when(systemSettingService.getMemoryKeyLanguage()).thenReturn("ZH");

        String context = memoryService.buildFullContext(GLOBAL, null);

        assertNotNull(context);
        assertTrue(context.contains("language"));
    }

    // ============================ 分流入口 buildMemoryContext(scope, query) ============================

    @Test
    void buildMemoryContext_fullMode_delegatesToFullContext() {
        when(systemSettingService.getMemoryRetrievalMode()).thenReturn("LLM_FULL_CONTEXT");
        when(memoryMapper.findFullContext(eq(100L), any(), anyBoolean(), any())).thenReturn(List.of(testMemory));

        String context = memoryService.buildMemoryContext(GLOBAL, "随便问点啥");

        assertNotNull(context);
        assertTrue(context.contains("Java"));
        verifyNoInteractions(llmGateway);
    }

    @Test
    void buildMemoryContext_fullMode_default_whenSettingNull() {
        when(systemSettingService.getMemoryRetrievalMode()).thenReturn(null);
        when(memoryMapper.findFullContext(eq(100L), any(), anyBoolean(), any())).thenReturn(List.of(testMemory));

        String context = memoryService.buildMemoryContext(GLOBAL, "query");

        assertNotNull(context);
        verifyNoInteractions(llmGateway);
    }

    // ============================ 向量召回（EMBEDDING_VECTOR，限 scope）============================

    @Test
    void buildMemoryContext_vectorMode_injectsTopKHits() {
        when(systemSettingService.getMemoryRetrievalMode()).thenReturn("EMBEDDING_VECTOR");
        when(llmGateway.embed(eq("我女儿"), eq(RagConfig.MEMORY_EMBED_MODEL))).thenReturn(new float[]{0.1f, 0.2f});
        when(memoryMapper.findTopKByVector(eq(100L), any(String.class), anyDouble(), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of(testMemory));

        String context = memoryService.buildMemoryContext(GLOBAL, "我女儿");

        assertNotNull(context);
        assertTrue(context.contains("Java"));
    }

    @Test
    void buildMemoryContext_vectorMode_noHits_returnsNull() {
        when(systemSettingService.getMemoryRetrievalMode()).thenReturn("EMBEDDING_VECTOR");
        when(llmGateway.embed(any(), any())).thenReturn(new float[]{0.1f});
        when(memoryMapper.findTopKByVector(any(), any(), anyDouble(), anyInt(), anyBoolean(), any())).thenReturn(List.of());

        String context = memoryService.buildMemoryContext(GLOBAL, "无关问题");

        assertNull(context);
    }

    @Test
    void buildMemoryContext_vectorMode_blankQuery_returnsNull() {
        when(systemSettingService.getMemoryRetrievalMode()).thenReturn("EMBEDDING_VECTOR");

        String context = memoryService.buildMemoryContext(GLOBAL, "   ");

        assertNull(context);
        verifyNoInteractions(llmGateway);
    }

    @Test
    void buildMemoryContext_vectorMode_embedFailure_returnsNullNotThrow() {
        when(systemSettingService.getMemoryRetrievalMode()).thenReturn("EMBEDDING_VECTOR");
        when(llmGateway.embed(any(), any())).thenThrow(new RuntimeException("provider down"));

        String context = memoryService.buildMemoryContext(GLOBAL, "query");

        assertNull(context);
    }

    // ============================ 混合召回（VECTOR_KEYWORD，限 scope）============================

    @Test
    void buildMemoryContext_hybridMode_keywordHitAfterVectorMiss() {
        when(systemSettingService.getMemoryRetrievalMode()).thenReturn("VECTOR_KEYWORD");
        when(llmGateway.embed(any(), eq(RagConfig.MEMORY_EMBED_MODEL))).thenReturn(new float[]{0.1f});
        when(memoryMapper.findTopKByVector(any(), any(), anyDouble(), anyInt(), anyBoolean(), any())).thenReturn(List.of());
        when(memoryMapper.findByKeyword(eq(100L), any(), anyBoolean(), any())).thenReturn(List.of(testMemory));

        String context = memoryService.buildMemoryContext(GLOBAL, "带女儿去哪玩");

        assertNotNull(context);
        assertTrue(context.contains("Java"));
    }

    @Test
    void buildMemoryContext_hybridMode_vectorAndKeywordUnion() {
        when(systemSettingService.getMemoryRetrievalMode()).thenReturn("VECTOR_KEYWORD");
        when(llmGateway.embed(any(), any())).thenReturn(new float[]{0.1f});
        when(memoryMapper.findTopKByVector(any(), any(), anyDouble(), anyInt(), anyBoolean(), any())).thenReturn(List.of(testMemory));
        when(memoryMapper.findByKeyword(any(), any(), anyBoolean(), any())).thenReturn(List.of(testMemory));

        String context = memoryService.buildMemoryContext(GLOBAL, "我女儿");

        assertNotNull(context);
        assertTrue(context.contains("Java"));
    }

    @Test
    void buildMemoryContext_hybridMode_llmFallbackWhenBothMiss() {
        when(systemSettingService.getMemoryRetrievalMode()).thenReturn("VECTOR_KEYWORD");
        when(llmGateway.embed(any(), any())).thenReturn(new float[]{0.1f});
        when(memoryMapper.findTopKByVector(any(), any(), anyDouble(), anyInt(), anyBoolean(), any())).thenReturn(List.of());
        when(memoryMapper.findByKeyword(any(), any(), anyBoolean(), any())).thenReturn(List.of());
        when(memoryMapper.findAllClean(eq(100L), anyBoolean(), any())).thenReturn(List.of(testMemory));
        when(judge.selectRelevantKeysBlocks(any(), any(), any())).thenReturn(dims("language"));

        String context = memoryService.buildMemoryContext(GLOBAL, "推荐点啥");

        assertNotNull(context);
        assertTrue(context.contains("Java"));
    }

    @Test
    void buildMemoryContext_hybridMode_fallbackEmptyReturnsNull() {
        when(systemSettingService.getMemoryRetrievalMode()).thenReturn("VECTOR_KEYWORD");
        when(llmGateway.embed(any(), any())).thenReturn(new float[]{0.1f});
        when(memoryMapper.findTopKByVector(any(), any(), anyDouble(), anyInt(), anyBoolean(), any())).thenReturn(List.of());
        when(memoryMapper.findByKeyword(any(), any(), anyBoolean(), any())).thenReturn(List.of());
        when(memoryMapper.findAllClean(eq(100L), anyBoolean(), any())).thenReturn(List.of(testMemory));
        when(judge.selectRelevantKeysBlocks(any(), any(), any())).thenReturn(dimsEmpty());

        String context = memoryService.buildMemoryContext(GLOBAL, "推荐点啥");

        assertNull(context);
    }

    @Test
    void buildMemoryContext_hybridMode_blankQueryReturnsNull() {
        when(systemSettingService.getMemoryRetrievalMode()).thenReturn("VECTOR_KEYWORD");

        String context = memoryService.buildMemoryContext(GLOBAL, "   ");

        assertNull(context);
        verifyNoInteractions(llmGateway);
    }

    // ============================ LLM_KEY 召回（V38 anchor 粗筛 + RRF + 双维度精排）============================

    /** 缓存 passthrough：loader 直接跑，便于单测验证精排链路（真实缓存命中/evict 由 MemoryQueryCacheTest 覆盖）。 */
    @SuppressWarnings("unchecked")
    private static java.util.function.Supplier<List<String>> passthroughLoader(
            org.mockito.invocation.InvocationOnMock i) {
        return ((java.util.function.Supplier<List<String>>) i.getArgument(2));
    }

    @Test
    void buildMemoryContext_llmKeyMode_rerankSelectsAndInjects() {
        when(systemSettingService.getMemoryRetrievalMode()).thenReturn("LLM_KEY");
        when(systemSettingService.getLlmKeyCoarseTopN()).thenReturn(40);
        when(systemSettingService.getLlmKeyRerank()).thenReturn(true);
        when(queryExpansion.expand(any(), eq(RagConfig.MEMORY_EMBED_MODEL)))
                .thenReturn(new QueryExpansionService.ExpandedQuery("带家人出去玩", List.of("half1")));
        when(memoryMapper.findTopKByAnchor(eq(100L), any(), anyDouble(), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of(testMemory));
        when(memoryMapper.findAnchorBm25(eq(100L), any(), anyInt(), anyBoolean(), any())).thenReturn(List.of());
        when(queryCache.getRerankKeys(any(), any(), any())).thenAnswer(i -> passthroughLoader(i).get());
        when(judge.selectRelevantKeysBlocks(any(), any(), any())).thenReturn(dims("language"));

        String context = memoryService.buildMemoryContext(GLOBAL, "带家人出去玩");

        assertNotNull(context);
        assertTrue(context.contains("Java"));
    }

    @Test
    void buildMemoryContext_llmKeyMode_rerankOffInjectsTopN() {
        when(systemSettingService.getMemoryRetrievalMode()).thenReturn("LLM_KEY");
        when(systemSettingService.getLlmKeyCoarseTopN()).thenReturn(40);
        when(systemSettingService.getLlmKeyRerank()).thenReturn(false);
        when(queryExpansion.expand(any(), eq(RagConfig.MEMORY_EMBED_MODEL)))
                .thenReturn(new QueryExpansionService.ExpandedQuery("带家人出去玩", List.of("half1")));
        when(memoryMapper.findTopKByAnchor(eq(100L), any(), anyDouble(), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of(testMemory));
        when(memoryMapper.findAnchorBm25(eq(100L), any(), anyInt(), anyBoolean(), any())).thenReturn(List.of());

        String context = memoryService.buildMemoryContext(GLOBAL, "带家人出去玩");

        assertNotNull(context);
        assertTrue(context.contains("Java"));
        verify(judge, never()).selectRelevantKeysBlocks(any(), any(), any());
    }

    @Test
    void buildMemoryContext_llmKeyMode_bothChannelsEmptyReturnsNull() {
        when(systemSettingService.getMemoryRetrievalMode()).thenReturn("LLM_KEY");
        when(systemSettingService.getLlmKeyCoarseTopN()).thenReturn(40);
        when(systemSettingService.getLlmKeyRerank()).thenReturn(true);
        when(queryExpansion.expand(any(), eq(RagConfig.MEMORY_EMBED_MODEL)))
                .thenReturn(new QueryExpansionService.ExpandedQuery("带家人出去玩", List.of("half1")));
        when(memoryMapper.findTopKByAnchor(eq(100L), any(), anyDouble(), anyInt(), anyBoolean(), any())).thenReturn(List.of());
        when(memoryMapper.findAnchorBm25(eq(100L), any(), anyInt(), anyBoolean(), any())).thenReturn(List.of());

        String context = memoryService.buildMemoryContext(GLOBAL, "带家人出去玩");

        assertNull(context);
        verify(judge, never()).selectRelevantKeysBlocks(any(), any(), any());
    }

    @Test
    void buildMemoryContext_llmKeyMode_expandFailsFallsBackToCanonicalEmbed() {
        when(systemSettingService.getMemoryRetrievalMode()).thenReturn("LLM_KEY");
        when(systemSettingService.getLlmKeyCoarseTopN()).thenReturn(40);
        when(systemSettingService.getLlmKeyRerank()).thenReturn(false);
        when(queryExpansion.expand(any(), any())).thenThrow(new RuntimeException("expand down"));
        when(llmGateway.embed(any(), eq(RagConfig.MEMORY_EMBED_MODEL))).thenReturn(new float[]{0.1f});
        when(memoryMapper.findTopKByAnchor(eq(100L), any(), anyDouble(), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of(testMemory));
        when(memoryMapper.findAnchorBm25(eq(100L), any(), anyInt(), anyBoolean(), any())).thenReturn(List.of());

        String context = memoryService.buildMemoryContext(GLOBAL, "带家人出去玩");

        assertNotNull(context);
        assertTrue(context.contains("Java"));
        verify(llmGateway).embed("带家人出去玩", RagConfig.MEMORY_EMBED_MODEL);
    }

    // ============================ 老数据回填（backfillEntities：entities 召回词袋 + key_zh 中文标签）============================

    @Test
    void backfillEntities_noNullRows_returnsZero() {
        when(memoryMapper.findBackfillCandidates()).thenReturn(List.of());

        int n = memoryService.backfillEntities();

        assertEquals(0, n);
        verify(memoryMapper, never()).updateEntitiesAndKeyZh(any(), any(), any());
        verify(memoryMapper, never()).updateAnchor(any(), any(), any());
    }

    @Test
    void backfillEntities_updatesNullRowsWithEntitiesAndKeyZh() throws Exception {
        UserMemory m1 = new UserMemory();
        m1.setId(7L);
        m1.setUserId(100L);
        m1.setMemoryKey("child_name");
        m1.setMemoryValue("啊闪");
        when(memoryMapper.findBackfillCandidates()).thenReturn(List.of(m1));
        when(judge.batchExtractEntities(any())).thenReturn(java.util.Map.of(7L,
                new MemoryConflictJudge.BackfillRow(List.of("女儿", "孩子", "小孩", "啊闪"), "女儿")));
        when(objectMapper.writeValueAsString(any())).thenReturn("[\"女儿\",\"孩子\",\"小孩\",\"啊闪\"]");
        when(llmGateway.embed(any(), eq(RagConfig.MEMORY_EMBED_MODEL))).thenReturn(new float[]{0.1f});

        int n = memoryService.backfillEntities();

        assertEquals(1, n);
        verify(memoryMapper).updateEntitiesAndKeyZh(eq(7L), eq("[\"女儿\",\"孩子\",\"小孩\",\"啊闪\"]"), eq("女儿"));
        verify(memoryMapper).updateAnchor(eq(7L), any(), any());
    }

    @Test
    void backfillEntities_emptyEntitiesAndKeyZh_marksProcessed() {
        UserMemory m1 = new UserMemory();
        m1.setId(8L);
        m1.setUserId(100L);
        m1.setMemoryKey("misc");
        m1.setMemoryValue("无实体");
        when(memoryMapper.findBackfillCandidates()).thenReturn(List.of(m1));
        when(judge.batchExtractEntities(any())).thenReturn(java.util.Map.of(8L,
                new MemoryConflictJudge.BackfillRow(List.of(), null)));
        when(llmGateway.embed(any(), eq(RagConfig.MEMORY_EMBED_MODEL))).thenReturn(new float[]{0.1f});

        int n = memoryService.backfillEntities();

        assertEquals(1, n);
        verify(memoryMapper).updateEntitiesAndKeyZh(eq(8L), eq("[]"), eq(""));
        verify(memoryMapper).updateAnchor(eq(8L), any(), any());
    }

    /** C3：entities/key_zh 已存在仅 anchor_embedding 缺失 → 跳 LLM 抽取，只 embed anchor 落两列（幂等，省 LLM）。 */
    @Test
    void backfillEntities_anchorOnlyMissing_skipsExtractEmbedsAnchor() {
        UserMemory m1 = new UserMemory();
        m1.setId(9L);
        m1.setUserId(100L);
        m1.setMemoryKey("language");
        m1.setMemoryKeyZh("语言");
        m1.setEntities("[\"Java\"]");
        m1.setMemoryValue("Java");
        when(memoryMapper.findBackfillCandidates()).thenReturn(List.of(m1));
        when(llmGateway.embed(any(), eq(RagConfig.MEMORY_EMBED_MODEL))).thenReturn(new float[]{0.1f});

        int n = memoryService.backfillEntities();

        assertEquals(1, n);
        verify(judge, never()).batchExtractEntities(any());
        verify(memoryMapper, never()).updateEntitiesAndKeyZh(any(), any(), any());
        verify(memoryMapper).updateAnchor(eq(9L), any(), any());
    }

    // ============================ 预览召回过程透出（V38 D1/D2）============================

    @Test
    void previewContext_llmKeyMode_exposesCandidatesSelectedKeysChannels() {
        when(systemSettingService.getMemoryRetrievalMode()).thenReturn("LLM_KEY");
        when(systemSettingService.getLlmKeyCoarseTopN()).thenReturn(40);
        when(systemSettingService.getLlmKeyRerank()).thenReturn(true);
        when(memoryMapper.countByScope(eq(100L), any(), anyBoolean(), any())).thenReturn(5L);
        when(queryExpansion.expand(any(), eq(RagConfig.MEMORY_EMBED_MODEL)))
                .thenReturn(new QueryExpansionService.ExpandedQuery("带家人出去玩", List.of("half1")));
        when(memoryMapper.findTopKByAnchor(eq(100L), any(), anyDouble(), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of(testMemory));
        when(memoryMapper.findAnchorBm25(eq(100L), any(), anyInt(), anyBoolean(), any())).thenReturn(List.of());
        when(queryCache.getRerankKeys(any(), any(), any())).thenAnswer(i -> passthroughLoader(i).get());
        when(judge.selectRelevantKeysBlocks(any(), any(), any())).thenReturn(dims("language"));

        com.superprogrammer.chat.dto.MemoryContextPreviewVO vo = memoryService.previewContext(GLOBAL, "带家人出去玩");

        assertNotNull(vo);
        assertEquals("LLM_KEY", vo.getMode());
        assertNotNull(vo.getCandidates());
        assertEquals(1, vo.getCandidates().size());
        assertEquals("language", vo.getCandidates().get(0).getMemoryKey());
        assertEquals("vector", vo.getCandidates().get(0).getChannel());
        assertEquals(List.of("language"), vo.getSelectedKeys());
        assertNotNull(vo.getChannels());
        assertEquals(1, vo.getChannels().getVector());
        assertEquals(0, vo.getChannels().getBm25());
    }

    /** RelevantDims 构造：keys={key}、blocks={""}（testMemory blockLabel=null→""）、keysZh 空（testMemory keyZh=null 通配）。 */
    private static MemoryConflictJudge.RelevantDims dims(String key) {
        return new MemoryConflictJudge.RelevantDims(Set.of(key), Set.of(), Set.of(""));
    }

    /** RelevantDims 全空：模拟 LLM 判三维均无相关 → AND 空 → 不注入。 */
    private static MemoryConflictJudge.RelevantDims dimsEmpty() {
        return new MemoryConflictJudge.RelevantDims(Set.of(), Set.of(), Set.of());
    }
}
