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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DashscopeVideoProvider 单测（视频模型接入扩展 RF/MVR-6，HappyHorse 1.1 图生视频）。
 * 覆盖：buildCreateBody 官方 input/parameters 包裹（首帧 media 必选恰 1/无 ratio/resolution 大写映射/
 * duration [3,15] 夹取/watermark 平台口径）、纯图生视频 fail-fast（无图/多图/视频音频参考）、
 * MockWebServer 建任务（X-DashScope-Async 头/task_id/错误话术直带 body）、查态 PENDING→SUCCESS→FAILED
 * （UNKNOWN 过期终态/话术脱敏截断/字段容错）、查询 base 推导（config 覆盖/截 /api/v1/取 host）。
 */
class DashscopeVideoProviderTest {

    private final LlmProviderService llmProviderService = mock(LlmProviderService.class);
    private final DashscopeVideoProvider provider = new DashscopeVideoProvider(
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
        e.setName("百炼行");
        e.setProtocol("dashscope");
        e.setApiEndpoint(endpoint);
        e.setApiKeyEnc("enc");
        e.setCategory("VIDEO");
        e.setStatus("ACTIVE");
        e.setModels("[\"happyhorse-1.1-i2v\"]");
        e.setConfig(config);
        return e;
    }

    private void stubProvider(Long id, String endpoint, String config) {
        when(llmProviderService.getById(id)).thenReturn(providerRow(id, endpoint, config));
        when(llmProviderService.getDecryptedApiKey(id)).thenReturn("test-key");
    }

    private MediaGenRequest imageRequest(String url) {
        return MediaGenRequest.builder()
                .model("happyhorse-1.1-i2v").prompt("一只猫在草地上奔跑")
                .attachments(List.of(MediaGenRequest.ResolvedAttachment.builder()
                        .kind("image").url(url).fileId("f1").build()))
                .build();
    }

    private List<MediaGenRequest.ResolvedAttachment> oneImage() {
        return List.of(MediaGenRequest.ResolvedAttachment.builder()
                .kind("image").url("https://a/f.png").build());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parametersOf(Map<String, Object> body) {
        return (Map<String, Object>) body.get("parameters");
    }

    // ---------- buildCreateBody ----------

    @Test
    void image2Video_minimal_officialWrappedBody() {
        Map<String, Object> body = provider.buildCreateBody(imageRequest("https://a/first.png"));
        assertEquals("happyhorse-1.1-i2v", body.get("model"));
        Map<String, Object> input = (Map<String, Object>) body.get("input");
        assertEquals("一只猫在草地上奔跑", input.get("prompt"));
        assertEquals(List.of(Map.of("type", "first_frame", "url", "https://a/first.png")),
                input.get("media"), "media 必选，type=first_frame 恰 1 张");
        Map<String, Object> parameters = parametersOf(body);
        assertEquals("720P", parameters.get("resolution"), "空入参回落 720P（非官方默认 1080P，平台口径）");
        assertEquals(5, parameters.get("duration"), "duration 默认 5");
        assertEquals(false, parameters.get("watermark"), "watermark 平台口径 null=false 不加");
        assertFalse(parameters.containsKey("ratio"), "图生视频无 ratio（宽高跟随首帧）");
        assertFalse(parameters.containsKey("generate_audio"), "官方无音频参数");
        assertFalse(body.containsKey("content"), "与 Ark 扁平 content 不同，官方 input/parameters 包裹");
    }

    @Test
    void resolutionDictionary_mapsToOfficialEnum() {
        MediaGenRequest r480 = MediaGenRequest.builder().model("m").resolution("480p")
                .attachments(oneImage()).build();
        assertEquals("480P", parametersOf(provider.buildCreateBody(r480)).get("resolution"));
        MediaGenRequest r1080 = MediaGenRequest.builder().model("m").resolution("1080p")
                .attachments(oneImage()).build();
        assertEquals("1080P", parametersOf(provider.buildCreateBody(r1080)).get("resolution"),
                "1080P 官方已核实支持");
    }

    @Test
    void duration_clampedToOfficialRange() {
        MediaGenRequest low = MediaGenRequest.builder().model("m").duration(2)
                .attachments(oneImage()).build();
        assertEquals(3, parametersOf(provider.buildCreateBody(low)).get("duration"), "下界夹到 3（官方 [3,15]，非 4）");
        MediaGenRequest high = MediaGenRequest.builder().model("m").duration(16)
                .attachments(oneImage()).build();
        assertEquals(15, parametersOf(provider.buildCreateBody(high)).get("duration"), "上界夹到 15");
    }

    @Test
    void blankPrompt_omitted_notEmptyString() {
        MediaGenRequest r = MediaGenRequest.builder().model("m").attachments(oneImage()).build();
        Map<String, Object> input = (Map<String, Object>) provider.buildCreateBody(r).get("input");
        assertFalse(input.containsKey("prompt"), "prompt 官方可选，空不传");
    }

    @Test
    void legacyRefImage_fallback_usedAsFirstFrame() {
        Map<String, Object> body = provider.buildCreateBody(MediaGenRequest.builder()
                .model("m").refImageUrl("https://a/legacy.png").refFileId("rf").build());
        List<Map<String, Object>> media = (List<Map<String, Object>>)
                ((Map<String, Object>) body.get("input")).get("media");
        assertEquals("https://a/legacy.png", media.get(0).get("url"));
    }

    // ---------- 纯图生视频 fail-fast（P2 核对：provider 侧二次兜底） ----------

    @Test
    void noImage_failsFast_withClearMessage() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> provider.buildCreateBody(MediaGenRequest.builder().model("m").prompt("p").build()));
        assertTrue(ex.getMessage().contains("首帧图"), ex.getMessage());
    }

    @Test
    void multipleImages_withoutFirstFrameTag_rejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> provider.buildCreateBody(MediaGenRequest.builder().model("m")
                        .attachments(List.of(
                                MediaGenRequest.ResolvedAttachment.builder().kind("image").url("https://a/1.png").build(),
                                MediaGenRequest.ResolvedAttachment.builder().kind("image").url("https://a/2.png").build()))
                        .build()));
        assertTrue(ex.getMessage().contains("1 张首帧"), ex.getMessage());
    }

    @Test
    void multipleImages_singleFirstFrameTag_wins() {
        Map<String, Object> body = provider.buildCreateBody(MediaGenRequest.builder().model("m")
                .attachments(List.of(
                        MediaGenRequest.ResolvedAttachment.builder().kind("image").url("https://a/ref.png").build(),
                        MediaGenRequest.ResolvedAttachment.builder().kind("image").url("https://a/first.png")
                                .frameRole(MediaGenRequest.FRAME_FIRST).build()))
                .build());
        List<Map<String, Object>> media = (List<Map<String, Object>>)
                ((Map<String, Object>) body.get("input")).get("media");
        assertEquals("https://a/first.png", media.get(0).get("url"), "多图时认唯一 first_frame 标注者");
    }

    @Test
    void videoOrAudioReference_rejected() {
        IllegalStateException exV = assertThrows(IllegalStateException.class,
                () -> provider.buildCreateBody(MediaGenRequest.builder().model("m")
                        .attachments(List.of(
                                MediaGenRequest.ResolvedAttachment.builder().kind("image").url("https://a/f.png").build(),
                                MediaGenRequest.ResolvedAttachment.builder().kind("video").url("https://a/v.mp4").build()))
                        .build()));
        assertTrue(exV.getMessage().contains("不支持视频"), exV.getMessage());
        IllegalStateException exA = assertThrows(IllegalStateException.class,
                () -> provider.buildCreateBody(MediaGenRequest.builder().model("m")
                        .attachments(List.of(
                                MediaGenRequest.ResolvedAttachment.builder().kind("image").url("https://a/f.png").build(),
                                MediaGenRequest.ResolvedAttachment.builder().kind("audio").url("https://a/a.mp3").build()))
                        .build()));
        assertTrue(exA.getMessage().contains("不支持音频"), exA.getMessage());
    }

    // ---------- createTask（HTTP） ----------

    @Test
    void createTask_returnsTaskId_sendsAsyncHeaderAndOfficialBody() throws Exception {
        stubProvider(9L, server.url("/api/v1/services/aigc/video-generation/video-synthesis").toString(), null);
        server.enqueue(new MockResponse().setBody(
                "{\"output\":{\"task_status\":\"PENDING\",\"task_id\":\"0385dc79-5ff8\"},\"request_id\":\"r1\"}"));
        MediaGenRequest request = MediaGenRequest.builder()
                .model("happyhorse-1.1-i2v").prompt("p").resolution("1080p").duration(8).providerId(9L)
                .attachments(List.of(MediaGenRequest.ResolvedAttachment.builder()
                        .kind("image").url("https://a/f.png").build()))
                .build();
        assertEquals("0385dc79-5ff8", provider.createTask(request));
        RecordedRequest recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/api/v1/services/aigc/video-generation/video-synthesis", recorded.getPath());
        assertEquals("enable", recorded.getHeader("X-DashScope-Async"), "官方必选异步头");
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"));
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"resolution\":\"1080P\""), "出参官方大写枚举: " + body);
        assertTrue(body.contains("\"duration\":8"));
        assertTrue(body.contains("\"type\":\"first_frame\""));
    }

    @Test
    void createTask_httpError_messageCarriesGatewayBody() {
        stubProvider(9L, server.url("/api/v1/services/aigc/video-generation/video-synthesis").toString(), null);
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"code\":\"InvalidApiKey\",\"message\":\"No API-key provided.\",\"request_id\":\"r2\"}"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> provider.createTask(MediaGenRequest.builder()
                        .model("happyhorse-1.1-i2v").providerId(9L)
                        .attachments(oneImage()).build()));
        assertTrue(ex.getMessage().contains("HTTP 401"), "状态码透传: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("InvalidApiKey"), "网关真实原因直带（errorMsg 落库即用户可读）");
    }

    // ---------- queryTask（HTTP） ----------

    @Test
    void queryTask_pendingThenSuccess_viaDerivedQueryPath() throws Exception {
        stubProvider(9L, server.url("/api/v1/services/aigc/video-generation/video-synthesis").toString(), null);
        server.enqueue(new MockResponse().setBody(
                "{\"output\":{\"task_id\":\"t1\",\"task_status\":\"PENDING\"},\"request_id\":\"r\"}"));
        assertEquals(MediaGenResult.STATUS_PENDING, provider.queryTask("t1", 9L).getStatus());
        RecordedRequest q1 = server.takeRequest();
        assertEquals("/api/v1/tasks/t1", q1.getPath(),
                "查询路径由建任务 URL 截 /api/v1/ 前取 host 推导");

        server.enqueue(new MockResponse().setBody(
                "{\"output\":{\"task_id\":\"t1\",\"task_status\":\"SUCCEEDED\","
                        + "\"video_url\":\"https://dashscope-result.oss/x.mp4\"},"
                        + "\"usage\":{\"duration\":5,\"video_count\":1,\"SR\":720}}"));
        MediaGenResult done = provider.queryTask("t1", 9L);
        assertEquals(MediaGenResult.STATUS_SUCCEEDED, done.getStatus());
        assertEquals("https://dashscope-result.oss/x.mp4", done.getResultUrl());
        assertEquals(5L, done.getUsageTokens(), "usage.duration=总秒数（计费口径）");
    }

    @Test
    void queryTask_failed_codeAndMessageRedactedTruncated() {
        String longMsg = "x".repeat(300);
        MediaGenResult r = provider.parseQueryResult(
                "{\"output\":{\"task_status\":\"FAILED\",\"code\":\"InvalidParameter\","
                        + "\"message\":\"" + longMsg + "\"}}");
        assertEquals(MediaGenResult.STATUS_FAILED, r.getStatus());
        assertTrue(r.getErrorMsg().startsWith("InvalidParameter"), "code+message 组合话术");
        assertEquals(256, r.getErrorMsg().length(), "失败话术截断 256，不整段透传上游");
    }

    @Test
    void queryTask_unknownStatus_treatedAsExpiredTerminalFail() {
        // 官方 UNKNOWN = 任务不存在/超 24h → 终态失败，停止轮询
        MediaGenResult r = provider.parseQueryResult(
                "{\"output\":{\"task_id\":\"t\",\"task_status\":\"UNKNOWN\"}}");
        assertEquals(MediaGenResult.STATUS_FAILED, r.getStatus());
        assertTrue(r.getErrorMsg().contains("24 小时"), r.getErrorMsg());
    }

    @Test
    void queryTask_canceledWithoutReason_fallsBackToFixedMessage() {
        MediaGenResult r = provider.parseQueryResult("{\"output\":{\"task_status\":\"CANCELED\"}}");
        assertEquals(MediaGenResult.STATUS_FAILED, r.getStatus());
        assertEquals("Dashscope 任务失败", r.getErrorMsg());
        assertNull(r.getResultUrl());
    }

    @Test
    void queryTask_fieldTolerance() {
        // 无 output 包裹 / 未知 status / SUCCEEDED 缺 usage —— 均不炸、不误判 FAILED
        assertEquals(MediaGenResult.STATUS_RUNNING,
                provider.parseQueryResult("{\"request_id\":\"r\"}").getStatus());
        assertEquals(MediaGenResult.STATUS_RUNNING,
                provider.parseQueryResult("{\"output\":{\"task_status\":\"weird\"}}").getStatus());
        MediaGenResult okNoUsage = provider.parseQueryResult(
                "{\"output\":{\"task_status\":\"SUCCEEDED\",\"video_url\":\"u\"}}");
        assertEquals(MediaGenResult.STATUS_SUCCEEDED, okNoUsage.getStatus());
        assertNull(okNoUsage.getUsageTokens());
    }

    // ---------- 查询 base 推导 ----------

    @Test
    void deriveQueryBase_configOverrideWins() {
        LlmProviderEntity e = providerRow(9L, "https://gw.example.com/custom/create",
                "{\"queryEndpoint\":\"https://gw.example.com/custom/tasks/\"}");
        assertEquals("https://gw.example.com/custom/tasks", provider.deriveQueryBase(e),
                "config 覆盖优先，剥尾随斜杠");
    }

    @Test
    void deriveQueryBase_defaultFromCreateUrl() {
        assertEquals("https://dashscope.aliyuncs.com/api/v1/tasks",
                provider.deriveQueryBase(providerRow(9L,
                        "https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis/", null)),
                "默认：截 /api/v1/ 前取 host 拼 /api/v1/tasks");
        assertEquals("https://w1.cn-beijing.maas.aliyuncs.com/api/v1/tasks",
                provider.deriveQueryBase(providerRow(9L,
                        "https://w1.cn-beijing.maas.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis", null)),
                "业务空间专属域名同理");
        assertEquals("https://gw.example.com/api/v1/tasks",
                provider.deriveQueryBase(providerRow(9L, "https://gw.example.com/custom/video", null)),
                "无 /api/v1/ 的自定义网关：取 scheme://host 按 Dashscope 约定拼");
    }
}
