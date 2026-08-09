package com.superprogrammer.media.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.media.dto.MediaImageRequest;
import com.superprogrammer.media.dto.MediaImageResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * ArkImageProvider 请求体构建 + 响应解析单测（buildBody/parseResult 均 package-private 直测）。
 *
 * <p>覆盖：① 同步固定项 response_format=url/stream=false；② pro guidance_scale 入参/lite sequential+组图/联网 tools
 * 按模型特性条件入参；③ parseResult 收集 data[].url、跳 error 项、usage.generated_images 兜底、空 data→失败。
 */
class ArkImageProviderTest {

    private final ArkImageProvider provider = new ArkImageProvider(
            mock(LlmProviderService.class), new ObjectMapper());

    // ---------- buildBody ----------

    @Test
    void buildBody_text2image_minimal_syncFixed() {
        Map<String, Object> body = provider.buildBody(MediaImageRequest.builder()
                .model("doubao-seedream-5.0-lite").prompt("一只猫").build());
        assertEquals("doubao-seedream-5.0-lite", body.get("model"));
        assertEquals("url", body.get("response_format"), "同步固定 response_format=url");
        assertEquals(false, body.get("stream"), "MVP 固定 stream=false");
        assertFalse(body.containsKey("image"), "纯文生图无参考图，不得传 image");
        assertFalse(body.containsKey("guidance_scale"), "lite 不支持 guidance_scale");
        assertFalse(body.containsKey("sequential_image_generation"), "未开组图不传");
        assertFalse(body.containsKey("tools"), "未开联网不传");
    }

    @Test
    void buildBody_pro_guidanceScaleEmitted_noSequential() {
        Map<String, Object> body = provider.buildBody(MediaImageRequest.builder()
                .model("doubao-seedream-5.0-pro-0724").prompt("p")
                .guidanceScale(7.5)
                .optimizeMode("fast")
                .size("2K")
                .build());
        assertEquals(7.5, body.get("guidance_scale"), "pro guidance_scale 入参");
        assertEquals(Map.of("mode", "fast"), body.get("optimize_prompt_options"), "optimize 走嵌套 mode");
        assertEquals("2K", body.get("size"));
        assertFalse(body.containsKey("sequential_image_generation"), "pro 不支持组图");
        assertFalse(body.containsKey("tools"), "pro 不支持联网");
    }

    @Test
    void buildBody_lite_sequentialAndWebSearchEmitted() {
        Map<String, Object> body = provider.buildBody(MediaImageRequest.builder()
                .model("doubao-seedream-5.0-lite").prompt("p")
                .sequential("auto")
                .maxImages(4)
                .webSearch(true)
                .outputFormat("png")
                .watermark(false)
                .build());
        assertEquals("auto", body.get("sequential_image_generation"));
        assertEquals(Map.of("max_images", 4), body.get("sequential_image_generation_options"),
                "sequential=auto 时带 max_images");
        assertEquals("png", body.get("output_format"));
        assertEquals(false, body.get("watermark"));
        assertEquals(List.of(Map.of("type", "web_search")), body.get("tools"), "联网→tools.web_search");
        assertFalse(body.containsKey("guidance_scale"));
    }

    @Test
    void buildBody_refImages_asImageArray() {
        Map<String, Object> body = provider.buildBody(MediaImageRequest.builder()
                .model("m").prompt("p")
                .refImageUrls(List.of("data:image/png;base64,A", "data:image/png;base64,B"))
                .build());
        assertEquals(List.of("data:image/png;base64,A", "data:image/png;base64,B"), body.get("image"));
    }

    @Test
    void buildBody_sequentialDisabled_noMaxImages() {
        Map<String, Object> body = provider.buildBody(MediaImageRequest.builder()
                .model("m").prompt("p").sequential("disabled").maxImages(4).build());
        assertEquals("disabled", body.get("sequential_image_generation"));
        assertFalse(body.containsKey("sequential_image_generation_options"),
                "sequential!=auto 时不带 max_images");
    }

    // ---------- parseResult ----------

    @Test
    void parseResult_success_collectsUrlsAndUsage() {
        String resp = "{\"data\":[{\"url\":\"http://a/1.png\"},{\"url\":\"http://a/2.png\"}],"
                + "\"usage\":{\"generated_images\":2,\"output_tokens\":1200}}";
        MediaImageResult r = provider.parseResult(resp);
        assertTrue(r.isSuccess());
        assertEquals(List.of("http://a/1.png", "http://a/2.png"), r.getImageUrls());
        assertEquals(2, r.getGeneratedImages());
        assertEquals(1200L, r.getOutputTokens());
    }

    @Test
    void parseResult_partialError_skipsErrorItems() {
        // 单张部分失败（item 带 error）：跳过 error 项，只收成功 url
        String resp = "{\"data\":[{\"url\":\"http://a/1.png\"},{\"error\":{\"message\":\"blocked\"}}],"
                + "\"usage\":{\"generated_images\":1}}";
        MediaImageResult r = provider.parseResult(resp);
        assertTrue(r.isSuccess());
        assertEquals(List.of("http://a/1.png"), r.getImageUrls());
    }

    @Test
    void parseResult_usageMissing_fallbackToUrlCount() {
        String resp = "{\"data\":[{\"url\":\"http://a/1.png\"},{\"url\":\"http://a/2.png\"}]}";
        MediaImageResult r = provider.parseResult(resp);
        assertTrue(r.isSuccess());
        assertEquals(2, r.getGeneratedImages(), "usage 缺失时按 url 数兜底");
    }

    @Test
    void parseResult_emptyData_returnsFailure() {
        String resp = "{\"data\":[],\"error\":{\"message\":\"content policy\"}}";
        MediaImageResult r = provider.parseResult(resp);
        assertFalse(r.isSuccess());
        assertTrue(r.getErrorMsg().contains("content policy"));
    }

    @Test
    void parseResult_allErrorItems_returnsFailure() {
        String resp = "{\"data\":[{\"error\":{\"message\":\"x\"}},{\"error\":{\"message\":\"y\"}}]}";
        MediaImageResult r = provider.parseResult(resp);
        assertFalse(r.isSuccess(), "全 error→失败");
    }

    @Test
    void parseResult_garbage_returnsFailure() {
        MediaImageResult r = provider.parseResult("not json");
        assertFalse(r.isSuccess());
    }
}
