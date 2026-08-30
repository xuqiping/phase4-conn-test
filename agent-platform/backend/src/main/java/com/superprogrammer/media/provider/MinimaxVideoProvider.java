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
 * MiniMax 视频生成 provider（Hailuo 系，v2 任务端点）。视频模型接入扩展 RE/MVR-5。
 *
 * <p>协议（官方 platform.minimax.io 文档，v2）：异步任务型——建任务→轮询→取 {@code video_url}。
 * <ul>
 *   <li>建任务 {@code POST {endpoint}}（endpoint=provider 行 apiEndpoint，填完整建任务 URL，如
 *       {@code https://api.minimax.io/v2/video_generation} / 国际站 {@code https://api.minimaxi.com/v2/video_generation}），
 *       返 {@code task_id}。</li>
 *   <li>查态 {@code GET {base}/query/video_generation/{task_id}}（7 天内可查；结果在
 *       {@code task.content.url}）。查询地址由建任务 URL 推导（剥尾段 {@code /video_generation} 拼
 *       {@code /query/video_generation}）；网关查询路径不同可配 provider config JSON
 *       {@code {"queryEndpoint": "https://.../query/video_generation"}} 精确覆盖。</li>
 *   <li>状态：{@code queued/preparing→PENDING}，{@code running/processing→RUNNING}，
 *       {@code succeeded/success→SUCCEEDED}，{@code failed/fail/cancelled/expired→FAILED}（大小写容忍）。</li>
 * </ul>
 *
 * <p>HHX-4 附属端点（H3 三件）：平台模型 id 后缀 {@code -context-ir}（提示词增强，文本出参）/
 * {@code -regeneration}（2K 再生成，输入仅源任务 id）走独立建任务 URL——由生成端点剥
 * {@code /video_generation} 尾段拼 {@code /h3_context_ir} / {@code /video_regeneration}，
 * provider config JSON {@code contextIrEndpoint} / {@code regenerationEndpoint} 可精确覆盖；
 * 出站 body.model 一律剥后缀传基础名（官方/中转三端点同名）。查询复用生成端点 query 通道。
 * HHX-5：查询侧 Context-IR 文本结果按 {@code task.task_type}（h3_context_ir）+ 字段启发
 * （有 content.prompt 无 content.url）判型，结果落 resultText，token 用量带 in/out 拆分。</p>
 *
 * <p>content[] 与 Ark 同构（text / image_url / video_url / audio_url + role：
 * first_frame/last_frame/reference_image/reference_video/reference_audio），参考上限官方：
 * 首尾帧图各≤1、参考图≤9、参考视频≤3、参考音频≤3（capability 默认同口径）。
 * 顶层参数：{@code resolution} 必填（官方枚举 768P/2K——字典档 768p/2k 入参映射大写出参）、
 * {@code duration} 必填整数 4-15、{@code ratio} 可选（t2v 必填非 adaptive，空默认 16:9）。
 * v2 无 watermark / generate_audio 参数，一律不传。
 *
 * <p>范式照抄 {@link ArkSeedanceProvider}：WebClient 按 (providerId|endpoint|密文) 指纹缓存，
 * 密钥只进 Authorization header 不落日志，失败原因走固定脱敏话术截断，
 * WebClientResponseException 不 chain cause（message 直带响应体，errorMsg 落库即用户可读）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinimaxVideoProvider implements MediaGenProvider {

    public static final String ID = "minimax";  // = llm_providers.protocol 取值（worker 路由键）

    /** 连通探测用的不存在任务 id（GET 查它不会建任务、不计费）。 */
    private static final String PROBE_TASK_ID = "probe-connectivity-nonexistent";

    private static final String CREATE_PATH_SUFFIX = "/video_generation";

    // HHX-4：MiniMax H3 附属端点模型后缀（平台侧模型 id；发请求的 body.model 一律剥后缀用基础名——
    // 中转/官方对三个端点都传 MiniMax-H3 同名，后缀仅用于平台能力/价表/端点路由区分）
    private static final String SUFFIX_CONTEXT_IR = "-context-ir";
    private static final String SUFFIX_REGENERATION = "-regeneration";

    static boolean isContextIrModel(String model) {
        return model != null && model.trim().toLowerCase().endsWith(SUFFIX_CONTEXT_IR);
    }

    static boolean isRegenerationModel(String model) {
        return model != null && model.trim().toLowerCase().endsWith(SUFFIX_REGENERATION);
    }

    /** 剥附属端点后缀（-context-ir / -regeneration → 基础模型名；非附属模型原样返回）。 */
    static String stripAuxSuffix(String model) {
        if (model == null) return null;
        String m = model.trim();
        String lower = m.toLowerCase();
        if (lower.endsWith(SUFFIX_CONTEXT_IR)) {
            return m.substring(0, m.length() - SUFFIX_CONTEXT_IR.length());
        }
        if (lower.endsWith(SUFFIX_REGENERATION)) {
            return m.substring(0, m.length() - SUFFIX_REGENERATION.length());
        }
        return m;
    }

    /** 连接/响应超时（与 ArkSeedanceProvider 一致，杜绝无超时 .block() 钉死线程）。 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    /** 官方 resolution 枚举（入参字典档小写 → 出参官方大写）。 */
    private static final Map<String, String> RESOLUTION_OUT = Map.of(
            "768p", "768P", "2k", "2K", "4k", "2K", "1080p", "768P");

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
        ResolvedMiniMax mm = resolveMiniMax(request.getProviderId());
        // HHX-4：附属端点按模型后缀路由（config 覆盖优先，resolveMiniMax 已算好）；生成端点原样
        String createUrl = isContextIrModel(request.getModel()) ? mm.contextIrEndpoint
                : isRegenerationModel(request.getModel()) ? mm.regenerationEndpoint : mm.endpoint;
        try {
            String resp = mm.client.post()
                    .uri(createUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(prepared.getBody())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(RESPONSE_TIMEOUT);
            String taskId = parseTaskId(resp);
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalStateException("MiniMax 建任务未返回 task_id: " + truncate(resp, 200));
            }
            log.info("MiniMax 建任务成功 model={} minimaxTaskId={}", request.getModel(), taskId);
            return taskId;
        } catch (Exception e) {
            // 同 Ark：WCE 不 chain cause，message 直带网关响应体（errorMsg 落库即用户可读）
            if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException wce) {
                String respBody = wce.getResponseBodyAsString();
                log.error("MiniMax 建任务被拒绝 status={} body={}", wce.getStatusCode().value(), truncate(respBody, 800), wce);
                throw new IllegalStateException("MiniMax 建任务失败 (HTTP " + wce.getStatusCode().value() + "): "
                        + truncate(respBody, 500));
            }
            throw new IllegalStateException("MiniMax 建任务失败: " + rootMessage(e), e);
        }
    }

    @Override
    public MediaGenResult queryTask(String providerTaskId, Long providerId) {
        ResolvedMiniMax mm = resolveMiniMax(providerId);
        String resp;
        try {
            // /{taskId} 是 v2 协议级资源路径（一次性任务定位符），唯一保留的拼接
            resp = mm.client.get()
                    .uri(mm.queryBase + "/" + providerTaskId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(RESPONSE_TIMEOUT);
        } catch (Exception e) {
            throw new IllegalStateException("MiniMax 查任务失败: " + rootMessage(e), e);
        }
        return parseQueryResult(resp);
    }

    // ---------- 请求体构建 ----------

    /** 附件类型 → v2 content 项 type / role（与 Ark 同构）。 */
    private static final Map<String, String> KIND_TYPE = Map.of(
            "image", "image_url", "video", "video_url", "audio", "audio_url");
    private static final Map<String, String> KIND_ROLE = Map.of(
            "image", "reference_image", "video", "reference_video", "audio", "reference_audio");

    /** package-private：单测直接验证 content 结构 + resolution/duration 映射。 */
    Map<String, Object> buildCreateBody(MediaGenRequest request) {
        // HHX-5：Context-IR——content[] 多模态同生成端点，但顶层无 resolution（官方参数表）；
        // duration 4-15 / ratio 同生成口径；model 发基础名。
        if (isContextIrModel(request.getModel())) {
            List<Map<String, Object>> content = buildContentItems(request);
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", stripAuxSuffix(request.getModel()));
            body.put("content", content);
            int duration = request.getDuration() == null ? 5 : request.getDuration();
            body.put("duration", Math.max(4, Math.min(15, duration)));
            body.put("ratio", request.getRatio() == null || request.getRatio().isBlank()
                    ? "16:9" : request.getRatio());
            return body;
        }
        // HHX-6：再生成——无 content/duration/ratio，只有 source_task_id + 固定 2K。
        if (isRegenerationModel(request.getModel())) {
            if (request.getSourceTaskId() == null) {
                throw new IllegalStateException("再生成任务缺少 sourceTaskId（提交侧应校验，provider 兜底）");
            }
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", stripAuxSuffix(request.getModel()));
            body.put("source_task_id", String.valueOf(request.getSourceTaskId()));
            body.put("resolution", "2K");
            return body;
        }
        List<Map<String, Object>> content = buildContentItems(request);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("content", content);
        // resolution 官方必填（768P/2K）：空或未列举值回落 768P（capability 默认只放行 768p/2k，此处兜底）
        String resIn = request.getResolution() == null ? "" : request.getResolution().trim().toLowerCase();
        body.put("resolution", RESOLUTION_OUT.getOrDefault(resIn, "768P"));
        // duration 官方必填整数 4-15：空补 5，越界夹取（capability 已限 4-15，此处兜底）
        int duration = request.getDuration() == null ? 5 : request.getDuration();
        body.put("duration", Math.max(4, Math.min(15, duration)));
        // ratio：空默认 16:9（t2v 必填非 adaptive；i2v 官方忽略直传值按首帧自适应）
        body.put("ratio", request.getRatio() == null || request.getRatio().isBlank() ? "16:9" : request.getRatio());
        // v2 官方无 watermark / generate_audio 顶层参数，不传
        return body;
    }

    /** content[] 组装（text + 附件，生成与 Context-IR 共用）。 */
    private List<Map<String, Object>> buildContentItems(MediaGenRequest request) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", request.getPrompt() == null ? "" : request.getPrompt()));
        if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
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
            if ("last".equalsIgnoreCase(request.getFrameRole())) {
                content.add(Map.of(
                        "type", "image_url",
                        "image_url", Map.of("url", request.getRefImageUrl()),
                        "role", "last_frame"));
            } else {
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", request.getRefImageUrl())));
            }
        }
        return content;
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
            JsonNode id = root.path("task_id");
            return id.isTextual() ? id.asText() : null;
        } catch (Exception e) {
            throw new IllegalStateException("MiniMax 建任务响应解析失败: " + truncate(resp, 200), e);
        }
    }

    /** package-private：单测直测字段容错（task 包裹缺失/未知 status/usage 缺失）。 */
    MediaGenResult parseQueryResult(String resp) {
        try {
            JsonNode root = objectMapper.readTree(resp);
            JsonNode task = root.path("task");
            String rawStatus = task.path("status").asText("").toLowerCase();
            String status = mapStatus(rawStatus);
            MediaGenResult.MediaGenResultBuilder b = MediaGenResult.builder().status(status);
            if (MediaGenResult.STATUS_SUCCEEDED.equals(status)) {
                // HHX-5：Context-IR 结果判型——官方/中转查询返 task_type（h3_context_ir），缺失时按
                // 字段启发（有 content.prompt 无 content.url = 文本结果）兼容网关裁剪 task_type 的情形。
                String taskType = task.path("task_type").asText("");
                String url = task.path("content").path("url").asText(null);
                String promptText = task.path("content").path("prompt").asText(null);
                boolean textResult = taskType.toLowerCase().contains("context_ir")
                        || ((url == null || url.isBlank()) && promptText != null && !promptText.isBlank());
                if (textResult) {
                    b.resultText(promptText);
                    JsonNode usage = task.path("usage");
                    if (usage.path("total_tokens").isNumber()) {
                        b.usageTokens(usage.path("total_tokens").asLong());
                    }
                    if (usage.path("prompt_tokens").isNumber()) {
                        b.usageInputTokens(usage.path("prompt_tokens").asLong());
                    }
                    if (usage.path("completion_tokens").isNumber()) {
                        b.usageOutputTokens(usage.path("completion_tokens").asLong());
                    }
                } else {
                    b.resultUrl(url);
                    // usage 兼容：官方返 total_seconds（秒计费口径）；缺失不设
                    JsonNode seconds = task.path("usage").path("total_seconds");
                    if (seconds.isNumber()) {
                        b.usageTokens(seconds.asLong());
                    }
                }
            } else if (MediaGenResult.STATUS_FAILED.equals(status)) {
                String msg = task.path("error").path("message").asText("");
                if (msg.isBlank()) {
                    msg = task.path("fail_msg").asText("");
                }
                b.errorMsg(truncate(msg.isBlank() ? "MiniMax 任务失败" : msg, 256));
            }
            return b.build();
        } catch (Exception e) {
            // 响应解析异常不等于明确失败；抛给 worker 退避重试，避免误写 FAILED
            throw new IllegalStateException("MiniMax 查询响应解析失败", e);
        }
    }

    /** MiniMax 原生 status → 内部状态机（v2 文档 queued/running/succeeded/failed/cancelled + 历史值容忍）。 */
    private String mapStatus(String raw) {
        if (raw == null || raw.isBlank()) return MediaGenResult.STATUS_RUNNING;
        switch (raw) {
            case "succeeded":
            case "success":
                return MediaGenResult.STATUS_SUCCEEDED;
            case "failed":
            case "fail":
            case "cancelled":
            case "expired":
                return MediaGenResult.STATUS_FAILED;
            case "queued":
            case "preparing":
            case "pending":
                return MediaGenResult.STATUS_PENDING;
            case "running":
            case "processing":
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
            // 判定口径与 Ark 完全一致（401/403 Key 问题；2xx/400/404 鉴权通过端点可达），复用静态实现
            return ArkSeedanceProvider.interpretProbe(status, body, duration, firstModel(entity));
        } catch (Exception e) {
            log.warn("MiniMax VIDEO 连通探测失败 [provider={}]: {}", entity.getName(), e.getMessage());
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
     * 解析 MiniMax 视频 provider（多 provider 路由版，同 Ark）：providerId 非空按任务落库行，
     * 空 回退 media.provider-name 默认 provider。同时推导查询 base（config.queryEndpoint 覆盖优先）。
     */
    private ResolvedMiniMax resolveMiniMax(Long providerId) {
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
        return new ResolvedMiniMax(client, endpoint, deriveQueryBase(provider),
                deriveAuxEndpoint(provider, endpoint, "contextIrEndpoint", "/h3_context_ir"),
                deriveAuxEndpoint(provider, endpoint, "regenerationEndpoint", "/video_regeneration"));
    }

    /**
     * HHX-4 附属端点推导：provider config JSON 指定键（contextIrEndpoint / regenerationEndpoint）覆盖优先；
     * 否则由生成端点剥 {@code /video_generation} 尾段后拼附属路径（官方 /v2/* 与中转 /v1/* 同构，
     * base 一致）。生成端点非标准尾段时直接尾拼（与 deriveQueryBase 同口径兜底）。
     */
    private String deriveAuxEndpoint(LlmProviderEntity provider, String endpoint, String configKey, String pathSuffix) {
        String cfg = provider.getConfig();
        if (cfg != null && !cfg.isBlank()) {
            try {
                JsonNode override = objectMapper.readTree(cfg).path(configKey);
                if (override.isTextual() && !override.asText().isBlank()) {
                    return override.asText().trim().replaceAll("/+$", "");
                }
            } catch (Exception e) {
                log.warn("解析 provider config {} 失败（provider={}），回落 URL 推导: {}", configKey, provider.getName(), e.getMessage());
            }
        }
        if (endpoint.endsWith(CREATE_PATH_SUFFIX)) {
            return endpoint.substring(0, endpoint.length() - CREATE_PATH_SUFFIX.length()) + pathSuffix;
        }
        return endpoint + pathSuffix;
    }

    /** 查询 base 推导：config JSON queryEndpoint 覆盖 > 建任务 URL 剥尾段推导。package-private 单测直测。 */
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
        if (endpoint.endsWith(CREATE_PATH_SUFFIX)) {
            return endpoint.substring(0, endpoint.length() - CREATE_PATH_SUFFIX.length()) + "/query" + CREATE_PATH_SUFFIX;
        }
        // 非标准建任务路径（网关自定义）：按 v2 语义尾拼
        return endpoint + "/query" + CREATE_PATH_SUFFIX;
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

    /** 解析后的调用上下文：WebClient + 生成建任务 URL + 查询 base + 两附属端点（/{taskId} 运行时拼）。 */
    private record ResolvedMiniMax(WebClient client, String endpoint, String queryBase,
                                   String contextIrEndpoint, String regenerationEndpoint) {}
}
