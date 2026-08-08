package com.superprogrammer.media.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.media.config.MediaGenProperties;
import com.superprogrammer.media.dto.MediaGenRequest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ArkSeedanceProvider 请求体构建单测（buildCreateBody，package-private 直测）。
 * 覆盖：多模态附件 roles（reference_image/video/audio）、旧首帧图无 role、
 * generate_audio 仅 true 时传、顶层平铺无 parameters 包裹。
 */
class ArkSeedanceProviderTest {

    private final ArkSeedanceProvider provider = new ArkSeedanceProvider(
            mock(LlmProviderService.class), new ObjectMapper(), new MediaGenProperties());

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> contentOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("content");
    }

    @Test
    void text2Video_onlyTextItem() {
        Map<String, Object> body = provider.buildCreateBody(MediaGenRequest.builder()
                .model("m").prompt("一只猫").taskType(MediaGenRequest.TYPE_TEXT2VIDEO).build());
        List<Map<String, Object>> content = contentOf(body);
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).get("type"));
        assertFalse(body.containsKey("parameters"), "官方契约为顶层平铺，不得有 parameters 包裹");
        assertFalse(body.containsKey("generate_audio"), "未开音频时省略 generate_audio");
        assertFalse(body.containsKey("fps"));
    }

    @Test
    void multimodalAttachments_emitTypedItemsWithRoles() {
        Map<String, Object> body = provider.buildCreateBody(MediaGenRequest.builder()
                .model("m").prompt("以图1为产品参考").taskType(MediaGenRequest.TYPE_IMAGE2VIDEO)
                .attachments(List.of(
                        MediaGenRequest.ResolvedAttachment.builder().kind("image").dataUri("data:image/png;base64,A").build(),
                        MediaGenRequest.ResolvedAttachment.builder().kind("video").dataUri("data:video/mp4;base64,B").build(),
                        MediaGenRequest.ResolvedAttachment.builder().kind("audio").dataUri("data:audio/mpeg;base64,C").build()))
                .generateAudio(true)
                .build());

        List<Map<String, Object>> content = contentOf(body);
        assertEquals(4, content.size()); // text + image + video + audio

        Map<String, Object> img = content.get(1);
        assertEquals("image_url", img.get("type"));
        assertEquals("reference_image", img.get("role"));
        assertTrue(img.containsKey("image_url"));

        Map<String, Object> vid = content.get(2);
        assertEquals("video_url", vid.get("type"));
        assertEquals("reference_video", vid.get("role"));
        assertTrue(vid.containsKey("video_url"));

        Map<String, Object> aud = content.get(3);
        assertEquals("audio_url", aud.get("type"));
        assertEquals("reference_audio", aud.get("role"));
        assertTrue(aud.containsKey("audio_url"));

        assertEquals(true, body.get("generate_audio"));
    }

    @Test
    void legacyRefImage_noRole_firstFrameSemantics() {
        Map<String, Object> body = provider.buildCreateBody(MediaGenRequest.builder()
                .model("m").prompt("动起来").taskType(MediaGenRequest.TYPE_IMAGE2VIDEO)
                .refImageUrl("data:image/png;base64,X")
                .build());
        List<Map<String, Object>> content = contentOf(body);
        assertEquals(2, content.size());
        Map<String, Object> img = content.get(1);
        assertEquals("image_url", img.get("type"));
        assertFalse(img.containsKey("role"), "旧首帧路径不带 role（首帧语义）");
    }

    @Test
    void frameRoleLast_emitsLastFrameRole() {
        // C2 尾帧：frameRole=last → content 项带 role:last_frame（SeedDance 2.0 尾帧契约）
        Map<String, Object> body = provider.buildCreateBody(MediaGenRequest.builder()
                .model("m").prompt("结尾镜头").taskType(MediaGenRequest.TYPE_IMAGE2VIDEO)
                .refImageUrl("data:image/png;base64,X")
                .frameRole("last")
                .build());
        List<Map<String, Object>> content = contentOf(body);
        Map<String, Object> img = content.get(1);
        assertEquals("image_url", img.get("type"));
        assertEquals("last_frame", img.get("role"), "尾帧须带 role:last_frame");
        assertTrue(img.containsKey("image_url"));
    }

    @Test
    void frameRoleFirst_keepsBareImage_noRole() {
        // C2 首帧（显式 first）= 默认行为：裸 image_url 不带 role（向后兼容，首帧语义）
        Map<String, Object> body = provider.buildCreateBody(MediaGenRequest.builder()
                .model("m").prompt("p").taskType(MediaGenRequest.TYPE_IMAGE2VIDEO)
                .refImageUrl("data:image/png;base64,X")
                .frameRole("first")
                .build());
        Map<String, Object> img = contentOf(body).get(1);
        assertFalse(img.containsKey("role"), "首帧不带 role（裸 image_url）");
    }

    @Test
    void attachments_winOverLegacyRefImage() {
        // 双通道同时出现时不应发生（提交侧互斥），provider 侧 attachments 优先且不回退首帧
        Map<String, Object> body = provider.buildCreateBody(MediaGenRequest.builder()
                .model("m").prompt("p").taskType(MediaGenRequest.TYPE_IMAGE2VIDEO)
                .refImageUrl("data:image/png;base64,X")
                .attachments(List.of(MediaGenRequest.ResolvedAttachment.builder()
                        .kind("image").dataUri("data:image/png;base64,Y").build()))
                .build());
        List<Map<String, Object>> content = contentOf(body);
        assertEquals(2, content.size());
        assertEquals("reference_image", content.get(1).get("role"));
    }

    @Test
    void attachmentFrameRoles_mixedFirstLastReference() {
        // B1：附件级 frameRole — 首帧+尾帧+参考图同请求，各 image 项按 frameRole 路由 role
        Map<String, Object> body = provider.buildCreateBody(MediaGenRequest.builder()
                .model("m").prompt("首尾帧+参考").taskType(MediaGenRequest.TYPE_IMAGE2VIDEO)
                .attachments(List.of(
                        MediaGenRequest.ResolvedAttachment.builder().kind("image").dataUri("data:image/png;base64,F").frameRole("first_frame").build(),
                        MediaGenRequest.ResolvedAttachment.builder().kind("image").dataUri("data:image/png;base64,L").frameRole("last_frame").build(),
                        MediaGenRequest.ResolvedAttachment.builder().kind("image").dataUri("data:image/png;base64,R").build()))
                .build());
        List<Map<String, Object>> content = contentOf(body);
        // text + 3 images
        assertEquals(4, content.size());
        assertEquals("first_frame", content.get(1).get("role"), "首帧附件→role:first_frame");
        assertEquals("last_frame", content.get(2).get("role"), "尾帧附件→role:last_frame");
        assertEquals("reference_image", content.get(3).get("role"), "无 frameRole 的 image→reference_image");
    }

    @Test
    void attachmentFrameRole_ignoredForNonImage() {
        // frameRole 配在 video 附件上应被忽略（恒 reference_video）
        Map<String, Object> body = provider.buildCreateBody(MediaGenRequest.builder()
                .model("m").prompt("p").taskType(MediaGenRequest.TYPE_IMAGE2VIDEO)
                .attachments(List.of(MediaGenRequest.ResolvedAttachment.builder()
                        .kind("video").dataUri("data:video/mp4;base64,V").frameRole("first_frame").build()))
                .build());
        Map<String, Object> vid = contentOf(body).get(1);
        assertEquals("reference_video", vid.get("role"), "video 附件的 frameRole 必须被忽略");
    }

    @Test
    void defaults_ratioAndWatermark() {
        Map<String, Object> body = provider.buildCreateBody(MediaGenRequest.builder()
                .model("m").prompt("p").taskType(MediaGenRequest.TYPE_TEXT2VIDEO).build());
        assertEquals("16:9", body.get("ratio"));
        assertEquals(false, body.get("watermark"));
    }

    // ---------- interpretProbe：MEDIA 连通探测状态码判定 ----------

    @Test
    void probe_404_meansAuthPassed_success() {
        var r = ArkSeedanceProvider.interpretProbe(404, "{\"error\":{\"code\":\"NotFound\"}}", 120L, "seedance-2.0");
        assertTrue(r.isSuccess());
        assertEquals("seedance-2.0", r.getModel());
        assertEquals(120L, r.getDurationMs());
    }

    @Test
    void probe_400_meansAuthPassed_success() {
        assertTrue(ArkSeedanceProvider.interpretProbe(400, "bad request", 50L, null).isSuccess());
    }

    @Test
    void probe_2xx_success() {
        assertTrue(ArkSeedanceProvider.interpretProbe(200, "{}", 30L, null).isSuccess());
    }

    @Test
    void probe_401and403_keyInvalid_fail() {
        var r401 = ArkSeedanceProvider.interpretProbe(401, "", 10L, null);
        assertFalse(r401.isSuccess());
        assertTrue(r401.getMessage().contains("API Key"));
        assertFalse(ArkSeedanceProvider.interpretProbe(403, "", 10L, null).isSuccess());
    }

    @Test
    void probe_otherStatus_failWithStatusAndBody() {
        var r = ArkSeedanceProvider.interpretProbe(500, "internal error", 10L, null);
        assertFalse(r.isSuccess());
        assertTrue(r.getMessage().contains("500"));
        assertTrue(r.getMessage().contains("internal error"));
    }

    // ---------- FR-001：全 URL 直发（endpoint 原样；/{taskId} 为协议资源路径唯一拼接） ----------

    private ArkSeedanceProvider providerWith(MockWebServer server, String tasksUrl) {
        LlmProviderService svc = mock(LlmProviderService.class);
        LlmProviderEntity entity = new LlmProviderEntity();
        entity.setId(7L);
        entity.setApiEndpoint(server.url(tasksUrl).toString());
        when(svc.getById(7L)).thenReturn(entity);
        when(svc.getDecryptedApiKey(7L)).thenReturn("k");
        return new ArkSeedanceProvider(svc, new ObjectMapper(), new MediaGenProperties());
    }

    @Test
    void createTask_postsToExactEndpoint() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            ArkSeedanceProvider p = providerWith(server, "/v1/contents/generations/tasks");
            server.enqueue(new MockResponse().setBody("{\"id\":\"cct-1\"}")
                    .setHeader("Content-Type", "application/json"));

            String taskId = p.createTask(MediaGenRequest.builder()
                    .providerId(7L).model("m").prompt("p")
                    .taskType(MediaGenRequest.TYPE_TEXT2VIDEO).build());

            assertEquals("cct-1", taskId);
            var recorded = server.takeRequest();
            assertEquals("POST", recorded.getMethod());
            // FR-001：建任务 POST endpoint 原样，不再拼 /contents/generations/tasks
            assertEquals("/v1/contents/generations/tasks", recorded.getPath());
        }
    }

    @Test
    void queryTask_getsEndpointPlusTaskId() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            ArkSeedanceProvider p = providerWith(server, "/api/v3/contents/generations/tasks");
            server.enqueue(new MockResponse().setBody(
                    "{\"status\":\"succeeded\",\"content\":{\"video_url\":\"http://v/x.mp4\"},\"usage\":{\"total_tokens\":9}}")
                    .setHeader("Content-Type", "application/json"));

            var result = p.queryTask("cct-9", 7L);

            assertEquals("http://v/x.mp4", result.getResultUrl());
            var recorded = server.takeRequest();
            assertEquals("GET", recorded.getMethod());
            // /{taskId} 是 Ark 协议级资源路径，唯一保留的拼接
            assertEquals("/api/v3/contents/generations/tasks/cct-9", recorded.getPath());
        }
    }
}
