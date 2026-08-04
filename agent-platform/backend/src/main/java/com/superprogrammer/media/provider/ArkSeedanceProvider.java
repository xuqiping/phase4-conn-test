package com.superprogrammer.media.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.media.config.MediaGenProperties;
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
 *   <li>{@code POST {base}/contents/generations/tasks} 建任务，返 {@code id}（{@code cct-xxx}）。</li>
 *   <li>{@code GET  {base}/contents/generations/tasks/{id}} 查态，{@code status} ∈ queued/running/succeeded/failed。</li>
 * </ul>
 *
 * <p><b>base URL 可配</b>：直接取视频 provider 的 {@code apiEndpoint} 作 baseUrl（含版本段），
 * 不再硬编 {@code /api/v3}。官方 Ark 填 {@code https://ark.cn-beijing.volces.com/api/v3}，
 * 第三方网关（如 ctaigw）填 {@code https://ai.ctaigw.cn/v1}——两者只是 base 不同，路径统一
 * {@code /contents/generations/tasks}，其余参数与官方完全一致。
 *
 * <p><b>provider 独立</b>：视频用专门 provider（默认 name={@code seedance}，由 {@code media.provider-name} 配），
 * 与 chat 的 doubao 解耦——各自 endpoint/key/model，互不影响。每次调用前解析
 * {@code llm_providers.name=<provider-name>} 的 endpoint + AES 解密 key；WebClient 按
 * (endpoint, apiKeyEnc) 指纹缓存，key 轮换后自动重建（照抄 OpenAICompatibleProvider 超时设置）。
 *
 * <p>安全：密钥只进 Authorization header，不落日志；失败原因走固定脱敏话术（errorMsg 截断）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArkSeedanceProvider implements MediaGenProvider {

    public static final String ID = "ark-seedance";

    /** 任务端点相对路径（拼在可配 base 之后；不再硬编 /api/v3，兼容 ctaigw /v1 等网关）。 */
    private static final String TASKS_PATH = "/contents/generations/tasks";

    /** 连接/响应超时（与 OpenAICompatibleProvider 一致，杜绝无超时 .block() 钉死线程）。 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final LlmProviderService llmProviderService;
    private final ObjectMapper objectMapper;
    private final MediaGenProperties properties;

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
        // 官方契约：顶层平铺（无 parameters 包裹）。ratio/duration/watermark 必传，
        // resolution/generate_audio 可选（官方默认 720p / false）。无 fps（统一 24）。
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("content", content);
        body.put("ratio", request.getRatio() == null ? "16:9" : request.getRatio());
        body.put("watermark", Boolean.TRUE.equals(request.getWatermark()));
        if (request.getDuration() != null) body.put("duration", request.getDuration());
        if (request.getResolution() != null && !request.getResolution().isBlank()) {
            body.put("resolution", request.getResolution());
        }
        if (Boolean.TRUE.equals(request.getGenerateAudio())) {
            body.put("generate_audio", true);
        }
        return body;
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
                // usage 字段兼容：byteplus/网关返 completion_tokens，火山方舟返 total_tokens，取到即用。
                JsonNode usage = root.path("usage");
                JsonNode tokens = usage.path("completion_tokens");
                if (!tokens.isNumber()) {
                    tokens = usage.path("total_tokens");
                }
                if (tokens.isNumber()) {
                    b.usageTokens(tokens.asLong());
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

    // ---------- 视频 provider 解析 + WebClient 缓存 ----------

    /** 解析视频 provider（endpoint + 解密 key），按指纹复用/重建 WebClient。 */
    private ResolvedArk resolveArk() {
        String providerName = properties.getProviderName();
        LlmProviderEntity provider = llmProviderService.getByName(providerName);
        if (provider == null) {
            throw new IllegalStateException("未找到视频 provider(name=" + providerName
                    + ")，无法生成视频（请先在「全局模型供应商」建一条 name=" + providerName
                    + " 的 provider，配 endpoint/key/视频模型）");
        }
        if (provider.getApiEndpoint() == null || provider.getApiEndpoint().isBlank()) {
            throw new IllegalStateException("视频 provider(name=" + providerName + ") 未配置 API 端点");
        }
        String apiKey = llmProviderService.getDecryptedApiKey(provider.getId());
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("视频 provider(name=" + providerName + ") 未配置 API Key");
        }
        String fingerprint = provider.getApiEndpoint() + "|" + provider.getApiKeyEnc();
        WebClient client = cachedClient;
        if (client == null || !fingerprint.equals(cachedFingerprint)) {
            client = buildClient(provider.getApiEndpoint(), apiKey);
            cachedClient = client;
            cachedFingerprint = fingerprint;
        }
        return new ResolvedArk(client);
    }

    private WebClient buildClient(String rawEndpoint, String apiKey) {
        // baseUrl 直接用视频 provider 配的 endpoint（含版本段，如 /api/v3 或 /v1），
        // 只剥尾随斜杠。调用处拼相对路径 /contents/generations/tasks，兼容官方/网关。
        String base = rawEndpoint.replaceAll("/+$", "");
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
