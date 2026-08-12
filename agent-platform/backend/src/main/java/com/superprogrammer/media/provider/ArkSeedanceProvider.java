package com.superprogrammer.media.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.media.config.MediaGenProperties;
import com.superprogrammer.media.dto.MediaGenRequest;
import com.superprogrammer.media.dto.MediaGenResult;
import com.superprogrammer.media.dto.PreparedMediaRequest;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 火山方舟 SeedDance 2.0 视频生成 provider（Ark 任务端点）。
 *
 * <p>协议：异步任务型（建任务→轮询→取 video_url），与 chat/embed 同步协议完全不同。
 * <ul>
 *   <li>{@code POST {endpoint}} 建任务，返 {@code id}（{@code cct-xxx}）。</li>
 *   <li>{@code GET  {endpoint}/{id}} 查态，{@code status} ∈ queued/running/succeeded/failed。</li>
 * </ul>
 *
 * <p><b>endpoint 即完整任务 URL</b>（V60 起，FR-001）：直接取视频 provider 的 {@code apiEndpoint}
 * 原样作为建任务 POST 地址，运行时零拼接。官方 Ark 填
 * {@code https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks}，
 * 第三方网关（如 ctaigw）填 {@code https://ai.ctaigw.cn/v1/contents/generations/tasks}。
 * <b>唯一保留的拼接</b>：查询/探测的 {@code /{taskId}}——这是 Ark 协议级资源路径
 * （任务 id 是运行时才有的一次性资源定位符），非 base URL 拼接，不得删除。
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

    /** 连通探测用的不存在任务 id（GET 查它不会建任务、不计费）。 */
    private static final String PROBE_TASK_ID = "probe-connectivity-nonexistent";

    /** 连接/响应超时（与 OpenAICompatibleProvider 一致，杜绝无超时 .block() 钉死线程）。 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final LlmProviderService llmProviderService;
    private final ObjectMapper objectMapper;
    private final MediaGenProperties properties;

    /** 缓存的 WebClient + 其指纹（endpoint + 密文），key 轮换后下次调用重建。 */
    /**
     * WebClient 缓存（F5：单槽→小 map）。key=指纹（providerId|endpoint|密文），
     * 多 VIDEO provider 交替任务不再每轮重建 HttpClient；key/URL 改后指纹变自动换槽。
     * provider 行数十量级，map 无界增长风险忽略。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, WebClient> clientCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String createTask(MediaGenRequest request) {
        return createPreparedTask(request, prepareCreateRequest(request));
    }

    /** 发送前只构建一次实际 body，并由它派生不含 data URI 的审计快照。 */
    public PreparedMediaRequest prepareCreateRequest(MediaGenRequest request) {
        Map<String, Object> body = buildCreateBody(request);
        return PreparedMediaRequest.builder()
                .body(body)
                .snapshot(buildRedactedSnapshot(body, request))
                .build();
    }

    /** 使用已准备的同一个 body 发 POST，避免保存快照后又重新推导请求。 */
    public String createPreparedTask(MediaGenRequest request, PreparedMediaRequest prepared) {
        ResolvedArk ark = resolveArk(request.getProviderId());
        try {
            // 全 URL 直发（FR-001）：endpoint 即任务端点完整 URL，原样 POST
            String resp = ark.client.post()
                    .uri(ark.endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(prepared.getBody())
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
            // 网关 4xx/5xx：WebClientResponseException 携带响应体（具体拒绝原因，如 InvalidParameter）。
            // 不 chain wce 作 cause——worker.markFailed 走 rootMessage(e) 会下钻到 WCE 的泛化
            // "400 Bad Request from POST..."，丢掉网关真正原因。抛无 cause 异常、message 直带 body，
            // errorMsg 落库即用户可读；完整堆栈由本行 log.error(...,wce) 记录。
            if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException wce) {
                String respBody = wce.getResponseBodyAsString();
                log.error("Ark 建任务被网关拒绝 status={} body={}", wce.getStatusCode().value(), truncate(respBody, 800), wce);
                throw new IllegalStateException("Ark 建任务失败 (HTTP " + wce.getStatusCode().value() + "): "
                        + truncate(respBody, 500));
            }
            throw new IllegalStateException("Ark 建任务失败: " + rootMessage(e), e);
        }
    }

    @Override
    public MediaGenResult queryTask(String providerTaskId) {
        return queryTask(providerTaskId, null);
    }

    /**
     * 查任务（多 provider 路由版）：providerId 非空时按任务落库的 provider 查，
     * 否则回退默认 provider（旧行为）。
     */
    public MediaGenResult queryTask(String providerTaskId, Long providerId) {
        ResolvedArk ark = resolveArk(providerId);
        String resp;
        try {
            // /{taskId} 是 Ark 协议级资源路径（一次性任务定位符），为唯一保留的拼接
            resp = ark.client.get()
                    .uri(ark.endpoint + "/" + providerTaskId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(RESPONSE_TIMEOUT);
        } catch (Exception e) {
            throw new IllegalStateException("Ark 查任务失败: " + rootMessage(e), e);
        }
        return parseQueryResult(resp);
    }

    // ---------- 请求体构建 ----------

    /** 附件类型 → Ark content 项 type / role。 */
    private static final Map<String, String> KIND_TYPE = Map.of(
            "image", "image_url", "video", "video_url", "audio", "audio_url");
    private static final Map<String, String> KIND_ROLE = Map.of(
            "image", "reference_image", "video", "reference_video", "audio", "reference_audio");

    /** package-private：单测直接验证 content 结构（roles / generate_audio 省略等）。 */
    Map<String, Object> buildCreateBody(MediaGenRequest request) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", request.getPrompt() == null ? "" : request.getPrompt()));
        if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
            // 多模态参考（SeedDance 2.0）：图/视频/音频按 role 标注，positional 引用（图1/视频1/音频1）
            // image 附件：frameRole=first_frame/last_frame → 对应帧 role；否则 reference_image。
            // 首/尾帧模式与参考媒体模式由 service 前置互斥校验；同一模式内保持附件顺序。
            for (MediaGenRequest.ResolvedAttachment a : request.getAttachments()) {
                String type = KIND_TYPE.getOrDefault(a.getKind(), "image_url");
                String role = KIND_ROLE.getOrDefault(a.getKind(), "reference_image");
                if ("image".equals(a.getKind())
                        && (MediaGenRequest.FRAME_FIRST.equals(a.getFrameRole())
                        || MediaGenRequest.FRAME_LAST.equals(a.getFrameRole()))) {
                    role = a.getFrameRole();
                }
                content.add(Map.of(
                        "type", type,
                        type, Map.of("url", a.getUrl()),
                        "role", role));
            }
        } else if (MediaGenRequest.TYPE_IMAGE2VIDEO.equals(request.getTaskType())
                && request.getRefImageUrl() != null && !request.getRefImageUrl().isBlank()) {
            // C2 参考帧位置：last → role:last_frame（SeedDance 2.0 尾帧）；first/默认 → 裸 image_url（首帧，向后兼容）
            if ("last".equalsIgnoreCase(request.getFrameRole())) {
                content.add(Map.of(
                        "type", "image_url",
                        "image_url", Map.of("url", request.getRefImageUrl()),
                        "role", "last_frame"));
            } else {
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", request.getRefImageUrl())));
            }
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

    private JsonNode buildRedactedSnapshot(Map<String, Object> body, MediaGenRequest request) {
        com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
        root.put("provider", ID);
        root.put("capturedAt", OffsetDateTime.now().toString());
        com.fasterxml.jackson.databind.node.ObjectNode redacted = objectMapper.valueToTree(body);
        JsonNode content = redacted.path("content");
        for (int i = 1; i < content.size(); i++) {
            com.fasterxml.jackson.databind.node.ObjectNode item = (com.fasterxml.jackson.databind.node.ObjectNode) content.get(i);
            String type = item.path("type").asText();
            JsonNode media = item.path(type);
            String mediaUrl = media.path("url").asText(null);
            String fileId = request.getAttachments() != null && i - 1 < request.getAttachments().size()
                    ? request.getAttachments().get(i - 1).getFileId()
                    : request.getRefFileId();
            item.set(type, redactedMediaUrl(mediaUrl, fileId));
        }
        root.set("request", redacted);
        return root;
    }

    private JsonNode redactedMediaUrl(String mediaUrl, String fileId) {
        com.fasterxml.jackson.databind.node.ObjectNode meta = objectMapper.createObjectNode();
        meta.put("redacted", true);
        if (fileId != null) meta.put("fileId", fileId);
        if (mediaUrl != null && mediaUrl.startsWith("https://")) {
            meta.put("transport", "https_url");
            return meta;
        }
        meta.put("transport", "data_uri");
        if (mediaUrl == null || !mediaUrl.startsWith("data:") || !mediaUrl.contains(",")) return meta;
        int comma = mediaUrl.indexOf(',');
        String header = mediaUrl.substring(5, comma);
        String mime = header.split(";", 2)[0];
        if (!mime.isBlank()) meta.put("mime", mime);
        try {
            byte[] bytes = Base64.getDecoder().decode(mediaUrl.substring(comma + 1));
            meta.put("bytes", bytes.length);
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            meta.put("sha256", HexFormat.of().formatHex(hash));
        } catch (Exception ignored) {
            meta.put("invalid", true);
        }
        return meta;
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
            // 响应解析异常不等于 Provider 明确失败；抛给 worker 退避重试，避免误写 FAILED。
            throw new IllegalStateException("Ark 查询响应解析失败", e);
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
            case "cancelled":   // F4：Ark 官方终态，原落 default→RUNNING 死轮询满超时，真实原因被吞
            case "expired":
                return MediaGenResult.STATUS_FAILED;
            case "queued":
                return MediaGenResult.STATUS_PENDING;
            case "running":
            default:
                return MediaGenResult.STATUS_RUNNING;
        }
    }

    // ---------- 连通性探测（VIDEO provider 测试按钮用） ----------

    /**
     * VIDEO provider 连通性探测（零成本，不建任务不计费）。
     *
     * <p>背景：VIDEO（视频）是任务型协议，{@code /chat/completions} 探测必然失败；
     * 又不能为测试真建一个视频任务（计费）。故用 {@code GET 任务端点/不存在id} 探测：
     * <ul>
     *   <li>401/403 → Key 无效或无权限；</li>
     *   <li>2xx/400/404 → 鉴权通过、任务端点可达，判定成功（404/400 是"任务不存在/参数非法"的正常业务响应）；</li>
     *   <li>其余状态/网络异常 → 失败，附状态码与截断 body。</li>
     * </ul>
     * 独立建 WebClient（不走缓存），保证测的是表单里当前保存的 endpoint/key。
     */
    public com.superprogrammer.llm.dto.TestConnectionResult testConnection(Long providerId) {
        LlmProviderEntity entity = llmProviderService.getById(providerId);
        if (entity == null) {
            return com.superprogrammer.llm.dto.TestConnectionResult.fail("供应商不存在或已删除");
        }
        // F6：探测是 Ark 任务型协议专用，非 VIDEO provider（如 CHAT）测了会得到误导性判定
        if (!com.superprogrammer.llm.service.LlmProviderService.CATEGORY_VIDEO.equalsIgnoreCase(entity.getCategory())) {
            return com.superprogrammer.llm.dto.TestConnectionResult.fail(
                    "该供应商不是 VIDEO 类（当前 " + entity.getCategory() + "），请用对应类型的测试入口");
        }
        if (entity.getApiEndpoint() == null || entity.getApiEndpoint().isBlank()) {
            return com.superprogrammer.llm.dto.TestConnectionResult.fail("未配置API端点");
        }
        String apiKey = llmProviderService.getDecryptedApiKey(entity.getId());
        if (apiKey == null || apiKey.isBlank()) {
            return com.superprogrammer.llm.dto.TestConnectionResult.fail("未配置 API Key");
        }
        String endpoint = entity.getApiEndpoint().replaceAll("/+$", "");
        WebClient client = buildClient(apiKey);
        long start = System.currentTimeMillis();
        try {
            // /{taskId} 是 Ark 协议级资源路径，唯一保留的拼接
            ProbeResponse pr = client.get()
                    .uri(endpoint + "/" + PROBE_TASK_ID)
                    .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                            .map(body -> new ProbeResponse(resp.statusCode().value(), body)))
                    .block(RESPONSE_TIMEOUT);
            long duration = System.currentTimeMillis() - start;
            int status = pr != null ? pr.status() : -1;
            String body = pr != null ? pr.body() : "";
            return interpretProbe(status, body, duration, firstModel(entity));
        } catch (Exception e) {
            log.warn("VIDEO 连通探测失败 [provider={}]: {}", entity.getName(), e.getMessage());
            return com.superprogrammer.llm.dto.TestConnectionResult.fail(rootMessage(e));
        }
    }

    /** 探测响应判定（package-private 单测直测）。model 仅用于结果展示，可为 null。 */
    static com.superprogrammer.llm.dto.TestConnectionResult interpretProbe(
            int status, String body, long durationMs, String model) {
        if (status == 401 || status == 403) {
            return com.superprogrammer.llm.dto.TestConnectionResult.fail(
                    "API Key 无效或无权限 (HTTP " + status + ")");
        }
        if ((status >= 200 && status < 300) || status == 400 || status == 404) {
            // 400/404 = 请求带有效鉴权到达后端，仅业务层拒绝（任务不存在/参数非法）→ 连通性 OK
            return com.superprogrammer.llm.dto.TestConnectionResult.builder()
                    .success(true)
                    .message("连接成功（任务端点可达，鉴权通过）")
                    .model(model)
                    .durationMs(durationMs)
                    .build();
        }
        return com.superprogrammer.llm.dto.TestConnectionResult.fail(
                "HTTP " + status + ": " + truncate(body, 200));
    }

    /** 取模型列表第一个（探测结果展示用，解析失败返回 null 不影响判定）。 */
    private String firstModel(LlmProviderEntity entity) {
        if (entity.getModels() == null || entity.getModels().isBlank()) return null;
        try {
            List<String> models = objectMapper.readValue(entity.getModels(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return models.isEmpty() ? null : models.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 探测响应（状态码 + body）。 */
    private record ProbeResponse(int status, String body) {}

    // ---------- 视频 provider 解析 + WebClient 缓存 ----------

    /**
     * 解析视频 provider（多 provider 路由版）。
     * providerId 非空 → 按任务落库的 provider 直连（多 VIDEO provider 并存时各走各的 endpoint/key）；
     * 为空 → 回退 media.provider-name 默认 provider（旧行为）。
     */
    private ResolvedArk resolveArk(Long providerId) {
        LlmProviderEntity provider;
        String label;
        if (providerId != null) {
            provider = llmProviderService.getById(providerId);
            label = "id=" + providerId;
            if (provider == null) {
                throw new IllegalStateException("视频 provider 已停用或删除（id=" + providerId + "），任务无法续跑");
            }
        } else {
            String providerName = properties.getProviderName();
            provider = llmProviderService.getByName(providerName);
            label = "name=" + providerName;
            if (provider == null) {
                throw new IllegalStateException("未找到视频 provider(name=" + providerName
                        + ")，无法生成视频（请先在「全局模型供应商」建一条 name=" + providerName
                        + " 的 provider，配 endpoint/key/视频模型）");
            }
        }
        if (provider.getApiEndpoint() == null || provider.getApiEndpoint().isBlank()) {
            throw new IllegalStateException("视频 provider(" + label + ") 未配置 API 端点");
        }
        String apiKey = llmProviderService.getDecryptedApiKey(provider.getId());
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("视频 provider(" + label + ") 未配置 API Key");
        }
        String fingerprint = provider.getId() + "|" + provider.getApiEndpoint() + "|" + provider.getApiKeyEnc();
        WebClient client = clientCache.computeIfAbsent(fingerprint, k -> buildClient(apiKey));
        // endpoint 剥尾随斜杠后原样作任务端点完整 URL（FR-001）
        return new ResolvedArk(client, provider.getApiEndpoint().replaceAll("/+$", ""));
    }

    private WebClient buildClient(String apiKey) {
        // 不设 baseUrl：endpoint 已是完整任务 URL，每次请求绝对地址直发（FR-001）。
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT);
        return WebClient.builder()
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

    /** 解析后的 Ark 调用上下文（WebClient + 任务端点完整 URL，key 已注入 header）。 */
    private record ResolvedArk(WebClient client, String endpoint) {}
}
