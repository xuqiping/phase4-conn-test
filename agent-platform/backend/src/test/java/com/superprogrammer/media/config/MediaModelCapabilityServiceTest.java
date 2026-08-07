package com.superprogrammer.media.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MediaModelCapabilityService 单测：前缀默认值 + provider config 精确覆盖。
 * 覆盖：seedance-2 标准/fast/mini、seedance-1 / lite-i2v、未知模型兜底、config 覆盖合并。
 */
class MediaModelCapabilityServiceTest {

    private final MediaModelCapabilityService service =
            new MediaModelCapabilityService(new ObjectMapper());

    @Test
    void seedance2Standard_fullMultimodal() {
        MediaModelCapability cap = service.resolve("doubao-seedance-2-0-260128", null);
        assertEquals(9, cap.getMaxImages());
        assertEquals(3, cap.getMaxVideos());
        assertEquals(3, cap.getMaxAudios());
        assertEquals(12, cap.getMaxAttachments());
        assertTrue(cap.getSupportedResolutions().contains("4K"));
        assertTrue(cap.isSupportsGenerateAudio());
        assertTrue(cap.isVideoDataUri());
    }

    @Test
    void seedance2Fast_cappedAt1080p() {
        MediaModelCapability cap = service.resolve("doubao-seedance-2-0-fast-260128", null);
        assertEquals(9, cap.getMaxImages());
        assertFalse(cap.getSupportedResolutions().contains("4K"));
        assertTrue(cap.getSupportedResolutions().contains("1080p"));
    }

    @Test
    void seedance1Pro_imageOnly() {
        MediaModelCapability cap = service.resolve("doubao-seedance-1-0-pro-250528", null);
        assertEquals(1, cap.getMaxImages());
        assertEquals(0, cap.getMaxVideos());
        assertEquals(0, cap.getMaxAudios());
        assertFalse(cap.isSupportsGenerateAudio());
        assertFalse(cap.isVideoDataUri());
    }

    @Test
    void seedance1LiteI2v_fourImages() {
        MediaModelCapability cap = service.resolve("doubao-seedance-1-0-lite-i2v-250428", null);
        assertEquals(4, cap.getMaxImages());
        assertEquals(4, cap.getMaxAttachments());
    }

    @Test
    void unknownModel_conservativeFallback() {
        MediaModelCapability cap = service.resolve("some-future-model", null);
        assertEquals(1, cap.getMaxImages());
        assertEquals(0, cap.getMaxVideos());
        assertEquals(1, cap.getMaxAttachments());
        assertFalse(cap.isVideoDataUri());
    }

    @Test
    void configOverride_beatsPrefixDefault() {
        String config = "{\"capabilities\":{\"doubao-seedance-2-0-260128\":{\"maxVideos\":0,\"videoDataUri\":false}}}";
        MediaModelCapability cap = service.resolve("doubao-seedance-2-0-260128", config);
        // 覆盖字段生效
        assertEquals(0, cap.getMaxVideos());
        assertFalse(cap.isVideoDataUri());
        // 未覆盖字段保留前缀默认
        assertEquals(9, cap.getMaxImages());
        assertEquals(12, cap.getMaxAttachments());
    }

    @Test
    void configOverride_onlyAppliesToExactModel() {
        String config = "{\"capabilities\":{\"other-model\":{\"maxImages\":1}}}";
        MediaModelCapability cap = service.resolve("doubao-seedance-2-0-260128", config);
        assertEquals(9, cap.getMaxImages());
    }

    @Test
    void brokenConfig_fallsBackToDefaults() {
        MediaModelCapability cap = service.resolve("doubao-seedance-2-0-260128", "{not json");
        assertEquals(9, cap.getMaxImages());
    }
}
