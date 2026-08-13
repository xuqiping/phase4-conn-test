package com.superprogrammer.knowledge.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.knowledge.entity.RagRankingConfig;
import com.superprogrammer.knowledge.mapper.RagRankingConfigMapper;
import com.superprogrammer.knowledge.mapper.RagAnswerCacheMapper;
import com.superprogrammer.llm.service.LlmProviderService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

/** RAG-FR-04：Ranking 配置必须显式解析，不得硬编码或偷选列表第一项。 */
class RankingConfigServiceTest {

    private final RagRankingConfigMapper mapper = mock(RagRankingConfigMapper.class);
    private final LlmProviderService providerService = mock(LlmProviderService.class);
    private final RagAnswerCacheMapper answerCacheMapper = mock(RagAnswerCacheMapper.class);
    private final RankingConfigService service = new RankingConfigService(mapper, providerService, answerCacheMapper);

    @Test
    void knowledgeBaseOverrideWinsOverAdminDefault() {
        when(mapper.findActiveForKb(9L)).thenReturn(config(9L, "LLM", "kb-chat"));
        when(mapper.findActiveDefault()).thenReturn(config(null, "LLM", "admin-chat"));
        when(providerService.listActiveModels(LlmProviderService.CATEGORY_CHAT)).thenReturn(List.of("kb-chat"));

        RankingConfigService.ResolvedRankingConfig result = service.resolve(9L);

        assertEquals("LLM", result.mode());
        assertEquals("kb-chat", result.model());
        assertEquals(RankingConfigService.Source.KNOWLEDGE_BASE, result.source());
    }

    @Test
    void adminDefaultUsedWhenKnowledgeBaseHasNoOverride() {
        when(mapper.findActiveForKb(9L)).thenReturn(null);
        when(mapper.findActiveDefault()).thenReturn(config(null, "LLM", "admin-chat"));
        when(providerService.listActiveModels(LlmProviderService.CATEGORY_CHAT)).thenReturn(List.of("admin-chat"));

        assertEquals(RankingConfigService.Source.ADMIN_DEFAULT, service.resolve(9L).source());
    }

    @Test
    void disabledModeDoesNotRequireModel() {
        when(mapper.findActiveForKb(9L)).thenReturn(config(9L, "DISABLED", null));

        RankingConfigService.ResolvedRankingConfig result = service.resolve(9L);

        assertEquals("DISABLED", result.mode());
        assertNull(result.model());
    }

    @Test
    void rerankModeChecksRerankCapability() {
        when(mapper.findActiveForKb(9L)).thenReturn(config(9L, "RERANK", "rank-model"));
        when(providerService.listActiveModels(LlmProviderService.CATEGORY_RERANK)).thenReturn(List.of("rank-model"));

        assertEquals("rank-model", service.resolve(9L).model());
    }

    @Test
    void noConfigurationFailsInsteadOfChoosingFirstProviderModel() {
        when(mapper.findActiveForKb(9L)).thenReturn(null);
        when(mapper.findActiveDefault()).thenReturn(null);
        when(providerService.listActiveModels(LlmProviderService.CATEGORY_CHAT)).thenReturn(List.of("first-model"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.resolve(9L));

        assertEquals("知识库未配置重排模式，且管理员未设置默认配置", error.getMessage());
    }

    @Test
    void unavailableExplicitModelFailsWithoutSilentReplacement() {
        when(mapper.findActiveForKb(9L)).thenReturn(config(9L, "LLM", "disabled-model"));
        when(providerService.listActiveModels(LlmProviderService.CATEGORY_CHAT)).thenReturn(List.of("other-model"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.resolve(9L));

        assertEquals("知识库重排模型不可用: disabled-model", error.getMessage());
    }

    @Test
    void saveKnowledgeBaseOverrideArchivesOldAndCreatesVersionedConfig() {
        when(providerService.listActiveModels(LlmProviderService.CATEGORY_CHAT)).thenReturn(List.of("chat-model"));
        com.superprogrammer.knowledge.dto.RankingConfigUpdateRequest request =
                new com.superprogrammer.knowledge.dto.RankingConfigUpdateRequest();
        request.setRankingMode("LLM");
        request.setModel("chat-model");
        request.setCandidateLimit(40);
        request.setFinalLimit(8);
        request.setBatchSize(10);
        request.setTimeoutMs(5000);
        request.setFallbackPolicy("FALLBACK_RRF");
        request.setHighAccuracyEnabled(true);

        service.saveForKb(9L, request, 7L);

        verify(mapper).archiveActiveForKb(9L, 7L);
        verify(mapper).insert(argThat(saved ->
                Long.valueOf(9L).equals(saved.getKbId())
                        && "LLM".equals(saved.getRankingMode())
                        && "chat-model".equals(saved.getModel())
                        && saved.getConfigVersion() != null
                        && saved.getConfigVersion().startsWith("rc-")));
        verify(answerCacheMapper).invalidateByKb(9L);
    }

    @Test
    void saveDisabledClearsModel() {
        com.superprogrammer.knowledge.dto.RankingConfigUpdateRequest request =
                new com.superprogrammer.knowledge.dto.RankingConfigUpdateRequest();
        request.setRankingMode("DISABLED");
        request.setModel("must-not-be-saved");

        service.saveDefault(request, 1L);

        verify(mapper).archiveActiveDefault(1L);
        verify(mapper).insert(argThat(saved -> saved.getKbId() == null && saved.getModel() == null));
        verify(answerCacheMapper).invalidateAllActive();
    }

    private static RagRankingConfig config(Long kbId, String mode, String model) {
        RagRankingConfig config = new RagRankingConfig();
        config.setId(1L);
        config.setKbId(kbId);
        config.setRankingMode(mode);
        config.setModel(model);
        config.setConfigVersion("v1");
        config.setCandidateLimit(30);
        config.setFinalLimit(10);
        config.setBatchSize(10);
        config.setTimeoutMs(4000);
        config.setFallbackPolicy("FAIL_CLOSED");
        config.setHighAccuracyEnabled(false);
        return config;
    }
}
