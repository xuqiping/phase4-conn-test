package com.superprogrammer.media.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.media.config.MediaGenProperties;
import com.superprogrammer.media.dto.MediaGenRequest;
import com.superprogrammer.media.dto.MediaGenResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MinimaxVideoProvider 单测（视频模型接入扩展 RE/MVR-5）。
 * 覆盖：buildCreateBody 字典档映射（768p/2k→768P/2K、duration 夹取）、content roles、
 * MockWebServer 建任务（task_id/鉴权头/错误话术直带 body）、查态 PENDING→SUCCESS→FAILED
 * （大小写容忍/话术脱敏截断/字段容错）、查询 base 推导（config 覆盖/默认）。
 */
class MinimaxVideoProviderTest {

    private final LlmProviderService llmProviderService = mock(LlmProviderService.class);
    private final MinimaxVideoProvider provider = new MinimaxVideoProvider(
            llmProviderService, new ObjectMapper(), new MediaGenProperties());

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private LlmProviderEntity providerRow(Long id, String endpoint, String config) {
        LlmProviderEntity e = new LlmProviderEntity();
        e.setId(id);
        e.setName("minimax行");
        e.setProtocol("minimax");
        e.setApiEndpoint(endpoint);
        e.setApiKeyEnc("enc");
        e.setCategory("VIDEO");
        e.setStatus("ACTIVE");
        e.setModels("[\"MiniMax-H3\"]");
        e.setConfig(config);
        return e;
    }

    private void stubProvider(Long id, String endpoint, String config) {
        when(llmProviderService.getById(id)).thenReturn(providerRow(id, endpoint, config));
        when(llmProviderService.getDecryptedApiKey(id)).thenReturn("test-key");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> contentOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("content");
    }

    // ---------- buildCreateBody ----------

    @Test
    void text2Video_minimal_requiredParamsFilled() {
        Map<String, Object> body = provider.buildCreateBody(MediaGenRequest.builder()
                .model("MiniMax-H3").prompt("一只猫").taskType(MediaGenRequest.TYPE_TEXT2VIDEO).build());
        assertEquals(1, contentOf(body).size());
        assertEquals("768P", body.get("resolution"), "resolution 官方必填，空入参兜底 768P");
        assertEquals(5, body.get("duration"), "duration 官方必填，空入参兜底 5");
        assertEquals("16:9", body.get("ratio"), "t2v ratio 必填非 adaptive，空默认 16:9");
        assertFalse(body.containsKey("watermark"), "v2 无 watermark 参数");
        assertFalse(body.containsKey("generate_audio"), "v2 无 generate_audio 参数");
        assertFalse(body.containsKey("parameters"));
    }

    @Test
    void resolutionDictionary_mapsToOfficialEnum() {
        assertEquals("2K", provider.buildCreateBody(MediaGenRequest.builder()
                        .model("m").resolution("2k").duration(5).build()).get("resolution"));
        assertEquals("768P", provider.buildCreateBody(MediaGenRequest.builder()
                        .model("m").resolution("768p").duration(5).build()).get("resolution"));
    }

    @Test
    void duration_clampedToOfficialRange() {
        assertEquals(4, provider.buildCreateBody(MediaGenRequest.builder()
                .model("m").duration(3).build()).get("duration"), "下界夹到 4");
        assertEquals(15, provider.buildCreateBody(MediaGenRequest.builder()
                .model("m").duration(16).build()).get("duration"), "上界夹到 15");
    }

    @Test
    void multimodalAttachments_emitTypedItemsWithRoles() {
        Map<String, Object> body = provider.buildCreateBody(MediaGenRequest.builder()
                .model("MiniMax-H3").prompt("p").duration(5)
                .attachments(List.of(
                        MediaGenRequest.ResolvedAttachment.builder().kind("image").url("https://a/first.png")
                                .frameRole(MediaGenRequest.FRAME_FIRST).build(),
                        MediaGenRequest.ResolvedAttachment.builder().kind("video").url("https://a/ref.mp4").build(),
                        MediaGenRequest.ResolvedAttachment.builder().kind("audio").url("https://a/ref.mp3").build()))
                .build());
        List<Map<String, Object>> content = contentOf(body);
        assertEquals(4, content.size());
        assertEquals(Map.of("url", "https://a/first.png"), content.get(1).get("image_url"));
        assertEquals("first_frame", content.get(1).get("role"));
        assertEquals("video_url", content.get(2).get("type"));
        assertEquals("reference_video", content.get(2).get("role"));
        assertEquals("audio_url", content.get(3).get("type"));
        assertEquals("reference_audio", content.get(3).get("role"));
    }

    // ---------- createTask（HTTP） ----------

    @Test
    void createTask_returnsTaskId_andSendsOfficialBody() throws Exception {
        stubProvider(9L, server.url("/v2/video_generation").toString(), null);
        server.enqueue(new MockResponse().setBody("{\"task_id\":\"424010985738629\"}"));
        MediaGenRequest request = MediaGenRequest.builder()
                .model("MiniMax-H3").prompt("太空歌剧").resolution("2k").duration(5).ratio("16:9")
                .providerId(9L).build();
        String taskId = provider.createTask(request);
        assertEquals("424010985738629", taskId);
        RecordedRequest recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/v2/video_generation", recorded.getPath());
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"));
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"resolution\":\"2K\""), "出参官方大写枚举: " + body);
        assertTrue(body.contains("\"duration\":5"));
    }

    @Test
    void createTask_httpError_messageCarriesGatewayBody() {
        stubProvider(9L, server.url("/v2/video_generation").toString(), null);
        server.enqueue(new MockResponse().setResponseCode(402)
                .setBody("{\"type\":\"error\",\"error\":{\"message\":\"insufficient balance (1008)\"}}"));
        MediaGenRequest request = MediaGenRequest.builder()
                .model("MiniMax-H3").prompt("p").resolution("768p").duration(5).providerId(9L).build();
        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> provider.createTask(request));
        assertTrue(ex.getMessage().contains("HTTP 402"), "状态码透传: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("insufficient balance"), "网关真实原因直带（errorMsg 落库即用户可读）");
    }

    // ---------- queryTask（HTTP） ----------

    @Test
    void queryTask_pendingThenSuccess_viaDerivedQueryPath() throws Exception {
        stubProvider(9L, server.url("/v2/video_generation").toString(), null);
        server.enqueue(new MockResponse().setBody("{\"task\":{\"id\":\"1\",\"status\":\"Preparing\"}}"));
        MediaGenResult pending = provider.queryTask("1", 9L);
        assertEquals(MediaGenResult.STATUS_PENDING, pending.getStatus());
        RecordedRequest q1 = server.takeRequest();
        assertEquals("/v2/query/video_generation/1", q1.getPath(),
                "查询路径由建任务 URL 剥尾段推导");

        server.enqueue(new MockResponse().setBody(
                "{\"task\":{\"id\":\"1\",\"status\":\"Success\","
                        + "\"content\":{\"url\":\"https://cdn.example.com/x.mp4\"},"
                        + "\"usage\":{\"total_seconds\":5}}}"));
        MediaGenResult done = provider.queryTask("1", 9L);
        assertEquals(MediaGenResult.STATUS_SUCCEEDED, done.getStatus());
        assertEquals("https://cdn.example.com/x.mp4", done.getResultUrl());
        assertEquals(5L, done.getUsageTokens());
    }

    @Test
    void queryTask_failed_errorMsgRedactedTruncated() {
        String longMsg = "x".repeat(300);
        MediaGenResult r = provider.parseQueryResult(
                "{\"task\":{\"status\":\"failed\",\"error\":{\"message\":\"" + longMsg + "\"}}}");
        assertEquals(MediaGenResult.STATUS_FAILED, r.getStatus());
        assertEquals(256, r.getErrorMsg().length(), "失败话术截断 256，不整段透传上游");
    }

    @Test
    void queryTask_failedWithoutError_fallsBackToFixedMessage() {
        MediaGenResult r = provider.parseQueryResult("{\"task\":{\"status\":\"cancelled\"}}");
        assertEquals(MediaGenResult.STATUS_FAILED, r.getStatus());
        assertEquals("MiniMax 任务失败", r.getErrorMsg());
        assertNull(r.getResultUrl());
    }

    @Test
    void queryTask_fieldTolerance() {
        // 无 task 包裹 / 未知 status / succeeded 缺 usage —— 均不炸、不误判 FAILED
        assertEquals(MediaGenResult.STATUS_RUNNING,
                provider.parseQueryResult("{\"base_resp\":{}}").getStatus());
        assertEquals(MediaGenResult.STATUS_RUNNING,
                provider.parseQueryResult("{\"task\":{\"status\":\"weird\"}}").getStatus());
        MediaGenResult okNoUsage = provider.parseQueryResult(
                "{\"task\":{\"status\":\"succeeded\",\"content\":{\"url\":\"u\"}}}");
        assertEquals(MediaGenResult.STATUS_SUCCEEDED, okNoUsage.getStatus());
        assertNull(okNoUsage.getUsageTokens());
    }

    // ---------- 查询 base 推导 ----------

    @Test
    void deriveQueryBase_configOverrideWins() {
        LlmProviderEntity e = providerRow(9L, "https://gw.example.com/custom/create",
                "{\"queryEndpoint\":\"https://gw.example.com/custom/query/\"}");
        assertEquals("https://gw.example.com/custom/query", provider.deriveQueryBase(e),
                "config 覆盖优先，剥尾随斜杠");
    }

    @Test
    void deriveQueryBase_defaultFromCreateUrl() {
        assertEquals("https://api.minimax.io/v2/query/video_generation",
                provider.deriveQueryBase(providerRow(9L, "https://api.minimax.io/v2/video_generation/", null)),
                "默认：剥建任务尾段 /video_generation + 拼 /query/video_generation");
        assertEquals("https://gw.example.com/v2/video_generation/extra/query/video_generation",
                provider.deriveQueryBase(providerRow(9L, "https://gw.example.com/v2/video_generation/extra", null)),
                "非标准路径兜底尾拼");
    }
}
