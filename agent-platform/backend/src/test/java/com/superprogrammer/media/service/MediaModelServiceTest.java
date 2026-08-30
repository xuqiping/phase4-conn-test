package com.superprogrammer.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.media.config.MediaGenProperties;
import com.superprogrammer.media.config.MediaModelCapabilityService;
import com.superprogrammer.media.dto.ImageModelVO;
import com.superprogrammer.media.dto.MediaModelVO;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MediaModelService 单测（Step8 补齐）：视频模型目录只认 category=VIDEO +
 * 按 model 反查 provider + 能力画像合并。
 */
class MediaModelServiceTest {

    private LlmProviderService llmProviderService;
    private MediaModelService service;
    private MediaGenProperties properties;
    private SystemSettingService systemSettingService;

    @BeforeEach
    void setUp() {
        llmProviderService = mock(LlmProviderService.class);
        // 能力解析用真实实现（内置前缀默认 + config 覆盖是断言对象）
        MediaModelCapabilityService capabilityService = new MediaModelCapabilityService(new ObjectMapper());
        properties = new MediaGenProperties();
        systemSettingService = mock(SystemSettingService.class);
        service = new MediaModelService(llmProviderService, capabilityService, properties, new ObjectMapper(),
                systemSettingService);
    }

    private LlmProviderEntity provider(String name, String category, String modelsJson, String config) {
        LlmProviderEntity p = new LlmProviderEntity();
        p.setName(name);
        p.setCategory(category);
        p.setModels(modelsJson);
        p.setConfig(config);
        return p;
    }

    @Test
    void listModels_onlyVideoCategory_imageAndOthersExcluded() {
        // FR-002/FR-003：目录只出 VIDEO；CHAT/EMBEDDING/IMAGE（预留）一律不进
        when(llmProviderService.listActive()).thenReturn(List.of(
                provider("doubao", "CHAT", "[\"doubao-pro\"]", null),
                provider("embed", "EMBEDDING", "[\"bge-m3\"]", null),
                provider("img", "IMAGE", "[\"dall-e-3\"]", null),
                provider("seedance", "VIDEO", "[\"doubao-seedance-2-0-260128\"]", null)));
        List<MediaModelVO> models = service.listModels();
        assertEquals(1, models.size());
        assertEquals("doubao-seedance-2-0-260128", models.get(0).getModelId());
        assertEquals("seedance", models.get(0).getProviderName());
    }

    @Test
    void listModels_seedance2_defaultsCapability_9img3video3audio() {
        when(llmProviderService.listActive()).thenReturn(List.of(
                provider("seedance", "VIDEO", "[\"doubao-seedance-2-0-260128\"]", null)));
        MediaModelVO vo = service.listModels().get(0);
        assertEquals(9, vo.getMaxImages());
        assertEquals(3, vo.getMaxVideos());
        assertEquals(3, vo.getMaxAudios());
        assertEquals(12, vo.getMaxAttachments());
        assertTrue(vo.isSupportsGenerateAudio());
        assertTrue(vo.getSupportedResolutions().contains("4K"));
        assertFalse(vo.isReferenceVideoEnabled(), "未配置公网签名地址时必须隐藏参考视频入口");
    }

    @Test
    void listModels_referenceVideoEnabledOnlyWithHttpsSigningConfiguration() {
        properties.getReference().setPublicBaseUrl("https://media.example.com");
        properties.getReference().setSigningKey("test-secret-at-least-32-bytes-long");
        when(llmProviderService.listActive()).thenReturn(List.of(
                provider("seedance", "VIDEO", "[\"doubao-seedance-2-0-260128\"]", null)));

        MediaModelVO vo = service.listModels().get(0);

        assertTrue(vo.isReferenceVideoEnabled());
        assertFalse(vo.isVideoDataUri(), "Ark 参考视频只允许 web URL，不应再宣称支持 data URI");
    }

    @Test
    void listModels_providerConfigOverride_winsOverPrefixDefault() {
        // provider config capabilities 精确覆盖内置前缀默认
        String config = "{\"capabilities\":{\"doubao-seedance-2-0-260128\":{\"maxImages\":2,\"supportsGenerateAudio\":false}}}";
        when(llmProviderService.listActive()).thenReturn(List.of(
                provider("seedance", "VIDEO", "[\"doubao-seedance-2-0-260128\"]", config)));
        MediaModelVO vo = service.listModels().get(0);
        assertEquals(2, vo.getMaxImages());
        assertFalse(vo.isSupportsGenerateAudio());
        // 未覆盖字段保持前缀默认
        assertEquals(3, vo.getMaxVideos());
    }

    @Test
    void resolveProviderByModel_hit_returnsOwnerProvider() {
        LlmProviderEntity video = provider("seedance", "VIDEO", "[\"Cdance2.0\"]", null);
        when(llmProviderService.listActive()).thenReturn(List.of(
                provider("doubao", "CHAT", "[\"Cdance2.0\"]", null), // CHAT 行同名模型不算数
                video));
        assertSame(video, service.resolveProviderByModel("Cdance2.0"));
    }

    @Test
    void resolveProviderByModel_miss_returnsNull() {
        when(llmProviderService.listActive()).thenReturn(List.of(
                provider("seedance", "VIDEO", "[\"Cdance2.0\"]", null)));
        assertNull(service.resolveProviderByModel("nonexistent-model"));
        assertNull(service.resolveProviderByModel(null));
        assertNull(service.resolveProviderByModel("  "));
    }

    @Test
    void firstModelOf_emptyOrBadJson_returnsNull() {
        assertNull(service.firstModelOf(provider("a", "VIDEO", null, null)));
        assertNull(service.firstModelOf(provider("b", "VIDEO", "[]", null)));
        assertNull(service.firstModelOf(provider("c", "VIDEO", "not-json", null)));
        assertEquals("m1", service.firstModelOf(provider("d", "VIDEO", "[\"m1\",\"m2\"]", null)));
    }

    // ---------- 修复III C2（2x-2）：图片模型目录带管理员默认标记 ----------

    private void givenImageProviders() {
        when(llmProviderService.listActive()).thenReturn(List.of(
                provider("seedream", "IMAGE", "[\"seedream-4.0\",\"seedream-lite\"]", null)));
    }

    @Test
    void listImageModels_configuredDefault_marksMatchingModelOnly() {
        givenImageProviders();
        when(systemSettingService.getDefaultImageModel()).thenReturn("seedream-lite");
        List<ImageModelVO> models = service.listImageModels();
        assertEquals(2, models.size());
        assertFalse(models.get(0).isDefaultModel(), "seedream-4.0 非配置默认");
        assertTrue(models.get(1).isDefaultModel(), "seedream-lite 命中配置默认");
    }

    @Test
    void listImageModels_noDefaultConfigured_noModelMarked() {
        givenImageProviders();
        when(systemSettingService.getDefaultImageModel()).thenReturn(null);
        List<ImageModelVO> models = service.listImageModels();
        assertTrue(models.stream().noneMatch(ImageModelVO::isDefaultModel), "未配置默认 → 无标记，前端回落第一个");
    }

    @Test
    void listImageModels_staleDefault_notInCatalog_noModelMarked() {
        // 读宽容：配置的模型已被下架 → 无命中项，等价未配置
        givenImageProviders();
        when(systemSettingService.getDefaultImageModel()).thenReturn("removed-model");
        List<ImageModelVO> models = service.listImageModels();
        assertTrue(models.stream().noneMatch(ImageModelVO::isDefaultModel));
    }

    // ---------- 全局默认视频模型标记（仿生图默认范式） ----------

    private void givenVideoProviders() {
        when(llmProviderService.listActive()).thenReturn(List.of(
                provider("minimax-h3", "VIDEO", "[\"minimax-h3\",\"minimax-h3-regeneration\"]", null)));
    }

    @Test
    void listModels_configuredDefault_marksMatchingModelOnly() {
        givenVideoProviders();
        when(systemSettingService.getDefaultVideoModel()).thenReturn("minimax-h3");
        List<MediaModelVO> models = service.listModels();
        assertEquals(2, models.size());
        assertTrue(models.get(0).isDefaultModel(), "minimax-h3 命中配置默认");
        assertFalse(models.get(1).isDefaultModel(), "regeneration 非配置默认");
    }

    @Test
    void listModels_noDefaultConfigured_noModelMarked() {
        givenVideoProviders();
        when(systemSettingService.getDefaultVideoModel()).thenReturn(null);
        List<MediaModelVO> models = service.listModels();
        assertTrue(models.stream().noneMatch(MediaModelVO::isDefaultModel), "未配置默认 → 无标记，前端回落第一个");
    }

    @Test
    void listModels_staleDefault_notInCatalog_noModelMarked() {
        // 读宽容：配置的默认模型已被下架 → 无命中项，等价未配置
        givenVideoProviders();
        when(systemSettingService.getDefaultVideoModel()).thenReturn("removed-model");
        List<MediaModelVO> models = service.listModels();
        assertTrue(models.stream().noneMatch(MediaModelVO::isDefaultModel));
    }
}
