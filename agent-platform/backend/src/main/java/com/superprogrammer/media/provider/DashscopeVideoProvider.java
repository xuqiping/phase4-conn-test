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

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 阿里百炼（DashScope）视频生成 provider（HappyHorse 1.1 图生视频）。视频模型接入扩展 RF/MVR-6。
 *
 * <p>协议（官方 help.aliyun.com 图生视频 API 参考，2026-04 版）：异步任务型——建任务→轮询→取 {@code video_url}。
 * <ul>
 *   <li>建任务 {@code POST {endpoint}}（endpoint=provider 行 apiEndpoint，填完整建任务 URL，如
 *       {@code https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis} 或业务空间专属域名
 *       {@code https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/...}），<b>必须带
 *       {@code X-DashScope-Async: enable} 头</b>（缺头报 "does not support synchronous calls"），
 *       返 {@code output.task_id}（查询有效期 24 小时）。</li>
 *   <li>查态 {@code GET {base}/api/v1/tasks/{task_id}}（结果在 {@code output.video_url}，24 小时有效）。
 *       查询地址由建任务 URL 推导（截 {@code /api/v1} 前取 scheme://host 再拼 {@code /api/v1/tasks}）；
 *       网关路径不同可配 provider config JSON {@code {"queryEndpoint": "https://.../api/v1/tasks"}} 精确覆盖。</li>
 *   <li>状态：{@code PENDING→PENDING}，{@code RUNNING→RUNNING}，{@code SUCCEEDED→SUCCEEDED}，
 *       {@code FAILED/CANCELED→FAILED}，{@code UNKNOWN→FAILED}（任务不存在/超 24h，终态不再轮询）。</li>
 * </ul>
 *
 * <p>请求体（官方 input/parameters 包裹，与 Ark 的扁平 content 不同）：{@code model}（happyhorse-1.1-i2v /
 * happyhorse-1.0-i2v）、{@code input.prompt}（可选，超长自动截断）、{@code input.media[]} 必选且<b>有且仅有
 * 1 张 type=first_frame 首帧图</b>（宽高自动跟随首帧，<b>无 ratio 参数</b>）、{@code parameters.resolution}
 * （480P/720P/1080P，官方默认 1080P——字典档小写入参映射大写出参，平台默认回落 720P）、
 * {@code parameters.duration} 整数 [3,15] 默认 5、{@code parameters.watermark}（官方默认 true 加
 * "Happy Horse" 水印；平台口径与 Ark 一致 null=false 不加水印）。
 *
 * <p>该模型为<b>纯图生视频</b>：无首帧图 / 多张图 / 视频音频参考 → 构造期 fail-fast 明确话术
 * （后端 capability maxVideos:0/maxAudios:0/maxImages:1 已拦截，此处为 provider 侧二次兜底）。
 * 官方无音频生成参数（输出 MP4 无音轨）→ capability {@code supportsGenerateAudio=false}
 * （plan 原写 true 系照抄 RE 模板，P2 核对官方文档后修正，见开发进度）。
 *
 * <p>范式照抄 {@link ArkSeedanceProvider}：WebClient 按 (providerId|endpoint|密文) 指纹缓存，
 * 密钥只进 Authorization header 不落日志，失败原因固定脱敏话术截断，
 * WebClientResponseException 不 chain cause（message 直带响应体，errorMsg 落库即用户可读）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashscopeVideoProvider implements MediaGenProvider {

    public static final String ID = "dashscope";  // = llm_providers.protocol 取值（worker 路由键）

    /** 连通探测用的不存在任务 id（GET 查它返回 UNKNOWN 终态，不会建任务、不计费）。 */
    private static final String PROBE_TASK_ID = "probe-connectivity-nonexistent";

    /** Dashscope 通用任务查询路径前缀（截 /api/v1 前的 scheme://host 再拼此段）。 */
    private static final String TASKS_PATH = "/api/v1/tasks";

    /** 连接/响应超时（与 ArkSeedanceProvider 一致，杜绝无超时 .block() 钉死线程）。 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    /** 官方 resolution 枚举（入参字典档小写 → 出参官方大写；未列举值回落 720P）。 */
    private static final Map<String, String> RESOLUTION_OUT = Map.of(
            "480p", "480P", "720p", "720P", "1080p", "1080P",
            "768p", "720P", "2k", "720P", "4k", "1080P");

    private final LlmProviderService llmProviderService;
    private final ObjectMapper objectMapper;
    private final MediaGenProperties properties;

    /** WebClient 缓存：key=指纹（providerId|endpoint|密文），key/URL 改后自动重建（同 Ark F5）。 */
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

    @Override
    public PreparedMediaRequest prepareCreateRequest(MediaGenRequest request) {
        Map<String, Object> body = buildCreateBody(request);
        return PreparedMediaRequest.builder()
                .body(body)
                .snapshot(buildRedactedSnapshot(body, request))
                .build();
    }

    @Override
    public String createPreparedTask(MediaGenRequest request, PreparedMediaRequest prepared) {
        ResolvedDashscope ds = resolveDashscope(request.getProviderId());
        try {
            String resp = ds.client.post()
                    .uri(ds.endpoint)
                    // 官方必选异步头：HTTP 只支持异步，缺头报同步调用错误
                    .header("X-DashScope-Async", "enable")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(prepared.getBody())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(RESPONSE_TIMEOUT);
            String taskId = parseTaskId(resp);
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalStateException("Dashscope 建任务未返回 task_id: " + truncate(resp, 200));
            }
            log.info("Dashscope 建任务成功 model={} dashscopeTaskId={}", request.getModel(), taskId);
            return taskId;
        } catch (Exception e) {
            // 同 Ark：WCE 不 chain cause，message 直带网关响应体（errorMsg 落库即用户可读）
            if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException wce) {
                String respBody = wce.getResponseBodyAsString();
                log.error("Dashscope 建任务被拒绝 status={} body={}", wce.getStatusCode().value(), truncate(respBody, 800), wce);
                throw new IllegalStateException("Dashscope 建任务失败 (HTTP " + wce.getStatusCode().value() + "): "
                        + truncate(respBody, 500));
            }
            throw new IllegalStateException("Dashscope 建任务失败: " + rootMessage(e), e);
        }
    }

    @Override
    public MediaGenResult queryTask(String providerTaskId, Long providerId) {
        ResolvedDashscope ds = resolveDashscope(providerId);
        String resp;
        try {
            // /{taskId} 是 Dashscope 协议级资源路径（一次性任务定位符），唯一保留的拼接
            resp = ds.client.get()
                    .uri(ds.queryBase + "/" + providerTaskId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(RESPONSE_TIMEOUT);
        } catch (Exception e) {
            throw new IllegalStateException("Dashscope 查任务失败: " + rootMessage(e), e);
        }
        return parseQueryResult(resp);
    }

    // ---------- 请求体构建 ----------

    /**
     * 选首帧图：附件里 image 类恰好 1 张直接用；多张时认唯一 first_frame 标注者；
     * 无图附件回落 legacy refImageUrl；仍无 → fail-fast。视频/音频参考 → fail-fast。
     * package-private：单测直测媒体选择与参数映射。
     */
    Map<String, Object> buildCreateBody(MediaGenRequest request) {
        List<MediaGenRequest.ResolvedAttachment> attachments =
                request.getAttachments() == null ? List.of() : request.getAttachments();
        List<MediaGenRequest.ResolvedAttachment> images = attachments.stream()
                .filter(a -> "image".equals(a.getKind())).toList();
        attachments.stream()
                .filter(a -> "video".equals(a.getKind()) || "audio".equals(a.getKind()))
                .findAny()
                .ifPresent(a -> {
                    throw new IllegalStateException("HappyHorse 不支持"
                            + ("video".equals(a.getKind()) ? "视频" : "音频") + "参考（仅支持 1 张首帧图）");
                });
        String firstFrameUrl = null;
        String firstFrameFileId = null;
        if (images.size() == 1) {
            firstFrameUrl = images.get(0).getUrl();
            firstFrameFileId = images.get(0).getFileId();
        } else if (images.size() > 1) {
            List<MediaGenRequest.ResolvedAttachment> tagged = images.stream()
                    .filter(a -> MediaGenRequest.FRAME_FIRST.equals(a.getFrameRole())).toList();
            if (tagged.size() != 1) {
                throw new IllegalStateException("HappyHorse 仅支持 1 张首帧图（当前 " + images.size() + " 张）");
            }
            firstFrameUrl = tagged.get(0).getUrl();
            firstFrameFileId = tagged.get(0).getFileId();
        } else if (request.getRefImageUrl() != null && !request.getRefImageUrl().isBlank()) {
            firstFrameUrl = request.getRefImageUrl();
            firstFrameFileId = request.getRefFileId();
        }
        if (firstFrameUrl == null || firstFrameUrl.isBlank()) {
            throw new IllegalStateException("HappyHorse 为图生视频模型，必须提供 1 张首帧图");
        }

        Map<String, Object> input = new java.util.LinkedHashMap<>();
        // prompt 官方可选：空不传（避免空串占位）；超长官方自动截断
        if (request.getPrompt() != null && !request.getPrompt().isBlank()) {
            input.put("prompt", request.getPrompt());
        }
        input.put("media", List.of(Map.of("type", "first_frame", "url", firstFrameUrl)));

        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        // 官方枚举 480P/720P/1080P（默认 1080P）；平台口径空/未列举回落 720P（与计价字典档对齐）
        String resIn = request.getResolution() == null ? "" : request.getResolution().trim().toLowerCase();
        parameters.put("resolution", RESOLUTION_OUT.getOrDefault(resIn, "720P"));
        // 官方 [3,15] 默认 5（capability 已限 3-15，此处越界夹取兜底）
        int duration = request.getDuration() == null ? 5 : request.getDuration();
        parameters.put("duration", Math.max(3, Math.min(15, duration)));
        // 官方默认 true 加水印；平台口径与 Ark 一致：null/未勾选 = false 不加
        parameters.put("watermark", Boolean.TRUE.equals(request.getWatermark()));
        // 官方无 ratio（宽高跟随首帧）/ generate_audio（无音轨）参数，不传

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("input", input);
        body.put("parameters", parameters);
        return body;
    }

    private JsonNode buildRedactedSnapshot(Map<String, Object> body, MediaGenRequest request) {
        com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
        root.put("provider", ID);
        root.put("capturedAt", OffsetDateTime.now().toString());
        com.fasterxml.jackson.databind.node.ObjectNode redacted = objectMapper.valueToTree(body);
        JsonNode media = redacted.path("input").path("media");
        if (media.isArray()) {
            for (int i = 0; i < media.size(); i++) {
                com.fasterxml.jackson.databind.node.ObjectNode item =
                        (com.fasterxml.jackson.databind.node.ObjectNode) media.get(i);
                String mediaUrl = item.path("url").asText(null);
                String fileId = request.getAttachments() != null && !request.getAttachments().isEmpty()
                        ? request.getAttachments().get(0).getFileId() : request.getRefFileId();
                item.set("url", redactedMediaUrl(mediaUrl, fileId));
            }
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
            JsonNode id = root.path("output").path("task_id");
            return id.isTextual() ? id.asText() : null;
        } catch (Exception e) {
            throw new IllegalStateException("Dashscope 建任务响应解析失败: " + truncate(resp, 200), e);
        }
    }

    /** package-private：单测直测字段容错（output 包裹缺失/未知 status/usage 缺失/UNKNOWN 过期）。 */
    MediaGenResult parseQueryResult(String resp) {
        try {
            JsonNode root = objectMapper.readTree(resp);
            JsonNode output = root.path("output");
            String rawStatus = output.path("task_status").asText("").toLowerCase();
            String status = mapStatus(rawStatus);
            MediaGenResult.MediaGenResultBuilder b = MediaGenResult.builder().status(status);
            if (MediaGenResult.STATUS_SUCCEEDED.equals(status)) {
                b.resultUrl(output.path("video_url").asText(null));
                // usage.duration=总秒数（官方计费口径）；缺失不设
                JsonNode seconds = root.path("usage").path("duration");
                if (seconds.isNumber()) {
                    b.usageTokens(seconds.asLong());
                }
            } else if (MediaGenResult.STATUS_FAILED.equals(status)) {
                String code = output.path("code").asText("");
                String msg = output.path("message").asText("");
                String reason = code.isBlank() ? msg : code + ": " + msg;
                b.errorMsg(truncate(reason.isBlank()
                        ? ("unknown".equals(rawStatus)
                                ? "任务不存在或已过查询有效期（Dashscope task_id 24 小时内可查）"
                                : "Dashscope 任务失败")
                        : reason, 256));
            }
            return b.build();
        } catch (Exception e) {
            // 响应解析异常不等于明确失败；抛给 worker 退避重试，避免误写 FAILED
            throw new IllegalStateException("Dashscope 查询响应解析失败", e);
        }
    }

    /** Dashscope 原生 task_status → 内部状态机（官方枚举大写，统一 lower 后映射）。 */
    private String mapStatus(String raw) {
        if (raw == null || raw.isBlank()) return MediaGenResult.STATUS_RUNNING;
        switch (raw) {
            case "succeeded":
                return MediaGenResult.STATUS_SUCCEEDED;
            case "failed":
            case "canceled":
            case "cancelled":
            case "unknown":  // 任务不存在/超 24h → 终态失败，停止轮询
                return MediaGenResult.STATUS_FAILED;
            case "pending":
                return MediaGenResult.STATUS_PENDING;
            case "running":
            default:
                return MediaGenResult.STATUS_RUNNING;
        }
    }

    // ---------- 连通性探测（VIDEO provider 测试按钮用，与 worker 同 protocol 路由） ----------

    @Override
    public com.superprogrammer.llm.dto.TestConnectionResult testConnection(Long providerId) {
        LlmProviderEntity entity = llmProviderService.getById(providerId);
        if (entity == null) {
            return com.superprogrammer.llm.dto.TestConnectionResult.fail("供应商不存在或已删除");
        }
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
        WebClient client = buildClient(apiKey);
        long start = System.currentTimeMillis();
        try {
            ProbeResponse pr = client.get()
                    .uri(deriveQueryBase(entity) + "/" + PROBE_TASK_ID)
                    .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                            .map(body -> new ProbeResponse(resp.statusCode().value(), body)))
                    .block(RESPONSE_TIMEOUT);
            long duration = System.currentTimeMillis() - start;
            int status = pr != null ? pr.status() : -1;
            String body = pr != null ? pr.body() : "";
            // 判定口径与 Ark 完全一致（401/403 Key 问题；2xx/400/404 鉴权通过端点可达——
            // Dashscope 查不存在任务返 200+UNKNOWN，天然落在 2xx 成功档），复用静态实现
            return ArkSeedanceProvider.interpretProbe(status, body, duration, firstModel(entity));
        } catch (Exception e) {
            log.warn("Dashscope VIDEO 连通探测失败 [provider={}]: {}", entity.getName(), e.getMessage());
            return com.superprogrammer.llm.dto.TestConnectionResult.fail(rootMessage(e));
        }
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

    // ---------- provider 解析 + WebClient 缓存 ----------

    /**
     * 解析 Dashscope 视频 provider（多 provider 路由版，同 Ark）：providerId 非空按任务落库行，
     * 空 回退 media.provider-name 默认 provider。同时推导查询 base（config.queryEndpoint 覆盖优先）。
     */
    private ResolvedDashscope resolveDashscope(Long providerId) {
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
        String endpoint = provider.getApiEndpoint().replaceAll("/+$", "");
        return new ResolvedDashscope(client, endpoint, deriveQueryBase(provider));
    }

    /**
     * 查询 base 推导：config JSON queryEndpoint 覆盖（填到 /api/v1/tasks） >
     * 建任务 URL 截 {@code /api/v1} 前取 scheme://host 拼 {@code /api/v1/tasks}。
     * package-private 单测直测。
     */
    String deriveQueryBase(LlmProviderEntity provider) {
        String cfg = provider.getConfig();
        if (cfg != null && !cfg.isBlank()) {
            try {
                JsonNode override = objectMapper.readTree(cfg).path("queryEndpoint");
                if (override.isTextual() && !override.asText().isBlank()) {
                    return override.asText().trim().replaceAll("/+$", "");
                }
            } catch (Exception e) {
                log.warn("解析 provider config queryEndpoint 失败（provider={}），回落 URL 推导: {}", provider.getName(), e.getMessage());
            }
        }
        String endpoint = provider.getApiEndpoint().replaceAll("/+$", "");
        int idx = endpoint.indexOf(TASKS_PATH.substring(0, 8));  // "/api/v1/"
        if (idx >= 0) {
            return endpoint.substring(0, idx) + TASKS_PATH;
        }
        // 无 /api/v1/ 的自定义网关：取 scheme://host[:port] 按 Dashscope 约定拼
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getScheme() + "://" + uri.getRawAuthority();
            return host + TASKS_PATH;
        } catch (Exception e) {
            throw new IllegalStateException("无法从 API 端点推导查询地址，请在 provider config 配 queryEndpoint: "
                    + truncate(endpoint, 120));
        }
    }

    private WebClient buildClient(String apiKey) {
        // 不设 baseUrl：endpoint 已是完整建任务 URL，每次请求绝对地址直发
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

    /** 解析后的调用上下文：WebClient + 建任务完整 URL + 查询 base（/{taskId} 运行时拼）。 */
    private record ResolvedDashscope(WebClient client, String endpoint, String queryBase) {}
}
