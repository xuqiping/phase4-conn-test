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
    void seedance2Fast_cappedAt480p720p() {
        // MVR-4：fast/mini 档仅 480p/720p（官方参数表修正，原误标 1080p）
        MediaModelCapability cap = service.resolve("doubao-seedance-2-0-fast-260128", null);
        assertEquals(9, cap.getMaxImages());
        assertFalse(cap.getSupportedResolutions().contains("4K"));
        assertFalse(cap.getSupportedResolutions().contains("1080p"));
        assertTrue(cap.getSupportedResolutions().contains("480p"));
        assertTrue(cap.getSupportedResolutions().contains("720p"));
    }

    // ---------------- MVR-4：Seedance 2.5 前缀默认 ----------------

    @Test
    void seedance25_dashWriting_fullCapability() {
        // -2-5 写法：30图/10视频/10音频/总50、4-30s、≤4K、音频生成
        MediaModelCapability cap = service.resolve("doubao-seedance-2-5-260901", null);
        assertEquals(30, cap.getMaxImages());
        assertEquals(10, cap.getMaxVideos());
        assertEquals(10, cap.getMaxAudios());
        assertEquals(50, cap.getMaxAttachments());
        assertEquals(4, cap.getMinDuration());
        assertEquals(30, cap.getMaxDuration());
        assertTrue(cap.getSupportedResolutions().contains("4K"));
        assertTrue(cap.isSupportsGenerateAudio());
    }

    @Test
    void seedance25_dotWriting_sameCapability() {
        // -2.5 写法与 -2-5 同命中（供应商模型 id 两种写法都可能出现）
        MediaModelCapability cap = service.resolve("doubao-seedance-2.5-pro", null);
        assertEquals(30, cap.getMaxImages());
        assertEquals(30, cap.getMaxDuration());
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
    void cdanceAlias_unknownPrefix_liftedToFullByConfig() {
        // 无前缀别名（如 ctaigw 网关的 Cdance2.0）无 config → 保守兜底 1 图。
        // 即「参考图回退成 1 / 上传区消失」根因：模型 id 不含 seedance-2，且 provider config 为空。
        MediaModelCapability noConfig = service.resolve("Cdance2.0", null);
        assertEquals(1, noConfig.getMaxImages());
        // V64 迁移注入 capabilities.Cdance2.0（无 supportedResolutions）→ 多模态能力提升到 2.0 真实值。
        // 但 supportedResolutions 缺失 → 保留未知兜底 RES_UPTO_1080（无 4K）=「Cdance2.0 没 4K」根因。
        String v64Config = "{\"capabilities\":{\"Cdance2.0\":{\"maxImages\":9,\"maxVideos\":3,\"maxAudios\":3,\"maxAttachments\":12,\"supportsGenerateAudio\":true,\"videoDataUri\":true}}}";
        MediaModelCapability v64 = service.resolve("Cdance2.0", v64Config);
        assertEquals(9, v64.getMaxImages());
        assertEquals(3, v64.getMaxVideos());
        assertEquals(3, v64.getMaxAudios());
        assertEquals(12, v64.getMaxAttachments());
        assertTrue(v64.isSupportsGenerateAudio());
        assertTrue(v64.isVideoDataUri());
        assertFalse(v64.getSupportedResolutions().contains("4K"));
        assertTrue(v64.getSupportedResolutions().contains("1080p"));
    }

    @Test
    void cdanceAlias_v67FullConfig_has4K() {
        // V67 迁移在 V64 基础上补 supportedResolutions 全梯（含 4K）+ supportedRatios + duration。
        // 验证完整 config → 4K 出现，前端分辨率下拉不再缺 4K。
        String v67Config = "{\"capabilities\":{\"Cdance2.0\":{"
                + "\"maxImages\":9,\"maxVideos\":3,\"maxAudios\":3,\"maxAttachments\":12,"
                + "\"supportsGenerateAudio\":true,\"videoDataUri\":true,"
                + "\"supportedResolutions\":[\"480p\",\"720p\",\"1080p\",\"4K\"],"
                + "\"supportedRatios\":[\"21:9\",\"16:9\",\"4:3\",\"1:1\",\"3:4\",\"9:16\",\"adaptive\"],"
                + "\"minDuration\":4,\"maxDuration\":15}}}";
        MediaModelCapability cap = service.resolve("Cdance2.0", v67Config);
        assertTrue(cap.getSupportedResolutions().contains("4K"));
        assertTrue(cap.getSupportedResolutions().contains("1080p"));
        assertEquals(15, cap.getMaxDuration());
        assertEquals(4, cap.getMinDuration());
        // 全量字段仍在（V67 整对象替换不丢 V64 字段）
        assertEquals(9, cap.getMaxImages());
        assertEquals(3, cap.getMaxVideos());
        assertTrue(cap.isVideoDataUri());
    }

    @Test
    void brokenConfig_fallsBackToDefaults() {
        MediaModelCapability cap = service.resolve("doubao-seedance-2-0-260128", "{not json");
        assertEquals(9, cap.getMaxImages());
    }
}
