package com.superprogrammer.media.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.media.dto.MediaGenRequest;
import com.superprogrammer.media.dto.MediaGenResult;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 火山方舟 SeedDance 2.0 视频生成 provider（Ark 任务端点）。
 *
 * <p>协议：异步任务型（建任务→轮询→取 video_url），与 chat/embed 同步协议完全不同。
 * <ul>
 *   <li>{@code POST /api/v3/contents/generations/tasks} 建任务，返 {@code id}（{@code cct-xxx}）。</li>
 *   <li>{@code GET  /api/v3/contents/generations/tasks/{id}} 查态，{@code status} ∈ queued/running/succeeded/failed。</li>
 * </ul>
 *
 * <p>Ark key 复用 doubao provider（同账号一把 key 通吃 Ark 所有端点）：每次调用前解析
 * {@code llm_providers.name='doubao'} 的 endpoint + AES 解密 key；WebClient 按
 * (endpoint, apiKeyEnc) 指纹缓存，key 轮换后自动重建（照抄 OpenAICompatibleProvider 超时设置）。
 *
 * <p>安全：密钥只进 Authorization header，不落日志；失败原因走固定脱敏话术（errorMsg 截断）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArkSeedanceProvider implements MediaGenProvider {

    public static final String ID = "ark-seedance";

    /** doubao provider name（Ark 统一 key 复用入口）。 */
    private static final String DOUBAO_PROVIDER_NAME = "doubao";
    private static final String TASKS_PATH = "/api/v3/contents/generations/tasks";

    /** 连接/响应超时（与 OpenAICompatibleProvider 一致，杜绝无超时 .block() 钉死线程）。 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final LlmProviderService llmProviderService;
    private final ObjectMapper objectMapper;

    /** 缓存的 WebClient + 其指纹（endpoint + 密文），key 轮换后下次调用重建。 */
    private volatile WebClient cachedClient;
    private volatile String cachedFingerprint;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String createTask(MediaGenRequest request) {
        ResolvedArk ark = resolveArk();
        Map<String, Object> body = buildCreateBody(request);
        try {
            String resp = ark.client.post()
                    .uri(TASKS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(RESPONSE_TIMEOUT);
            String taskId = parseTaskId(resp);
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalStateException("Ark 建任务未返回 id: " + truncate(resp, 200));
            }
            log.info("Ark 建任务成功 model={} arkTaskId={}", request.getModel(), taskId);
            return taskId;
        } catch (Exception e) {
            throw new IllegalStateException("Ark 建任务失败: " + rootMessage(e), e);
        }
    }

    @Override
    public MediaGenResult queryTask(String providerTaskId) {
        ResolvedArk ark = resolveArk();
        String resp;
        try {
            resp = ark.client.get()
                    .uri(TASKS_PATH + "/" + providerTaskId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(RESPONSE_TIMEOUT);
        } catch (Exception e) {
            throw new IllegalStateException("Ark 查任务失败: " + rootMessage(e), e);
        }
        return parseQueryResult(resp);
    }

    // ---------- 请求体构建 ----------

    private Map<String, Object> buildCreateBody(MediaGenRequest request) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", request.getPrompt() == null ? "" : request.getPrompt()));
        if (MediaGenRequest.TYPE_IMAGE2VIDEO.equals(request.getTaskType()) && request.getRefImageUrl() != null
                && !request.getRefImageUrl().isBlank()) {
            // 首帧参考图（图生视频）
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", request.getRefImageUrl())));
        }
        // parameters：watermark=false（平台生成不加水印）；duration/resolution/fps 受校验后传入
        Map<String, Object> parameters = new java.util.HashMap<>();
        parameters.put("watermark", false);
        if (request.getDuration() != null) parameters.put("duration", request.getDuration());
        if (request.getResolution() != null) parameters.put("resolution", request.getResolution());
        if (request.getFps() != null) parameters.put("fps", request.getFps());
        return Map.of(
                "model", request.getModel(),
                "content", content,
                "parameters", parameters);
    }

    // ---------- 响应解析 ----------

    private String parseTaskId(String resp) {
        try {
            JsonNode root = objectMapper.readTree(resp);
            JsonNode id = root.path("id");
            return id.isTextual() ? id.asText() : null;
        } catch (Exception e) {
            throw new IllegalStateException("Ark 建任务响应解析失败: " + truncate(resp, 200), e);
        }
    }

    private MediaGenResult parseQueryResult(String resp) {
        try {
            JsonNode root = objectMapper.readTree(resp);
            String rawStatus = root.path("status").asText("").toLowerCase();
            String status = mapStatus(rawStatus);
            MediaGenResult.MediaGenResultBuilder b = MediaGenResult.builder().status(status);
            if ("succeeded".equals(rawStatus) || "success".equals(rawStatus)) {
                String videoUrl = root.path("content").path("video_url").asText(null);
                b.resultUrl(videoUrl);
                JsonNode usage = root.path("usage").path("total_tokens");
                if (usage.isNumber()) {
                    b.usageTokens(usage.asLong());
                }
            } else if ("failed".equals(rawStatus)) {
                JsonNode err = root.path("error");
                String msg = err.path("message").asText("");
                b.errorMsg(truncate(msg.isBlank() ? "Ark 任务失败" : msg, 256));
            }
            return b.build();
        } catch (Exception e) {
            // 解析失败按 FAILED 兜底（不卡 RUNNING 死轮询；worker 会把任务置 FAILED + errorMsg）
            return MediaGenResult.builder()
                    .status(MediaGenResult.STATUS_FAILED)
                    .errorMsg("Ark 查询响应解析失败")
                    .build();
        }
    }

    /** Ark 原生 status → 内部状态机。 */
    private String mapStatus(String raw) {
        if (raw == null || raw.isBlank()) return MediaGenResult.STATUS_RUNNING;
        switch (raw) {
            case "succeeded":
            case "success":
                return MediaGenResult.STATUS_SUCCEEDED;
            case "failed":
                return MediaGenResult.STATUS_FAILED;
            case "queued":
                return MediaGenResult.STATUS_PENDING;
            case "running":
            default:
                return MediaGenResult.STATUS_RUNNING;
        }
    }

    // ---------- doubao provider 解析 + WebClient 缓存 ----------

    /** 解析 doubao provider（endpoint + 解密 key），按指纹复用/重建 WebClient。 */
    private ResolvedArk resolveArk() {
        LlmProviderEntity doubao = llmProviderService.getByName(DOUBAO_PROVIDER_NAME);
        if (doubao == null) {
            throw new IllegalStateException("未找到 doubao provider，无法生成视频（请先在 LLM 供应商配置 doubao）");
        }
        if (doubao.getApiEndpoint() == null || doubao.getApiEndpoint().isBlank()) {
            throw new IllegalStateException("doubao provider 未配置 API 端点");
        }
        String apiKey = llmProviderService.getDecryptedApiKey(doubao.getId());
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("doubao provider 未配置 API Key");
        }
        String fingerprint = doubao.getApiEndpoint() + "|" + doubao.getApiKeyEnc();
        WebClient client = cachedClient;
        if (client == null || !fingerprint.equals(cachedFingerprint)) {
            client = buildClient(doubao.getApiEndpoint(), apiKey);
            cachedClient = client;
            cachedFingerprint = fingerprint;
        }
        return new ResolvedArk(client);
    }

    private WebClient buildClient(String rawEndpoint, String apiKey) {
        // 归一化：剥离尾随 /api/v3 /v1，统一在调用处拼 /api/v3/...
        String base = rawEndpoint.replaceAll("/(api/v3|v1)/?$", "");
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT);
        return WebClient.builder()
                .baseUrl(base)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : truncate(m, 200);
    }

    /** 解析后的 Ark 调用上下文（仅 WebClient，key 已注入 header）。 */
    private record ResolvedArk(WebClient client) {}
}
