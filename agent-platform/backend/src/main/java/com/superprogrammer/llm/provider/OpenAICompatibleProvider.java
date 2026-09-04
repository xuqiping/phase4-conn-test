package com.superprogrammer.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.llm.dto.*;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
public class OpenAICompatibleProvider implements LlmProviderInterface {

    private final String name;
    private final Set<String> supportedModels;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    /** 完整请求 URL（V60 起 endpoint 即全 URL，运行时零拼接，FR-001）。
     *  CHAT 行 → …/chat/completions；EMBEDDING 行 → …/embeddings。 */
    private final String endpoint;
    /** 计费用：provider 主键（全局=llm_providers.id / 用户级=user_llm_providers.id）。 */
    private final Long providerId;
    /** 计费用：GLOBAL / USER。 */
    private final String providerScope;
    /** 思考参数声明（修复IX-1 A3）：null=零思考参数（现状）；来源 llm_providers.config thinking 节。 */
    private final ThinkingSpec thinkingSpec;

    /** 连接建立超时（ms）。云上 DNS/路由抖动时避免线程长期挂起。 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** 单次响应超时。兜底 .block(Duration)，杜绝无超时 .block() 钉死线程。 */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);
    private static final int QWEN_MULTIMODAL_EMBEDDING_DIMENSION = 2048;
    /** C5 多模态熔断窗口（WP5 坑点：探测失败后冷却，防反复打挂索引链路）。 */
    private static final long MULTIMODAL_BREAKER_MS = 10 * 60 * 1000L;
    /** 熔断开断表：key=providerName|model → openUntil 时间戳（毫秒）。进程级，重启即复位。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> MULTIMODAL_OPEN_UNTIL =
            new java.util.concurrent.ConcurrentHashMap<>();

    public OpenAICompatibleProvider(String name, String endpoint, String apiKey, List<String> models, ObjectMapper objectMapper) {
        this(name, endpoint, apiKey, models, objectMapper, null, "GLOBAL");
    }

    /** 全参构造：含计费用 providerId + providerScope（FR-计费）。无思考声明（现状行为）。 */
    public OpenAICompatibleProvider(String name, String endpoint, String apiKey, List<String> models,
                                    ObjectMapper objectMapper, Long providerId, String providerScope) {
        this(name, endpoint, apiKey, models, objectMapper, providerId, providerScope, null);
    }

    /** 全参构造（修复IX-1 A3）：含思考声明（LlmConfig 构造点解析 config jsonb 传入）。 */
    public OpenAICompatibleProvider(String name, String endpoint, String apiKey, List<String> models,
                                    ObjectMapper objectMapper, Long providerId, String providerScope,
                                    ThinkingSpec thinkingSpec) {
        this.name = name;
        this.supportedModels = models != null ? new HashSet<>(models) : Collections.emptySet();
        this.objectMapper = objectMapper;
        this.providerId = providerId;
        this.providerScope = providerScope != null ? providerScope : "GLOBAL";
        this.thinkingSpec = thinkingSpec;
        // 全 URL 直发：仅剥尾随斜杠，不做任何路径拼接/版本段剥离
        this.endpoint = endpoint == null ? "" : endpoint.replaceAll("/+$", "");
        // 底层 HttpClient 显式设 connect/response 超时，否则 WebClient 默认无超时，
        // 云上 LLM 端点 stall 会让 .block() 永久挂起、拖垮 Tomcat 线程池。
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT);
        // 不设 baseUrl：每次请求用 endpoint 绝对地址直发
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Long getId() {
        return providerId;
    }

    @Override
    public String getProviderScope() {
        return providerScope;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> body = buildRequestBody(request);
            String responseJson = webClient.post()
                    .uri(endpoint)  // 全 URL 直发（FR-001）
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(resolveTimeout(request));
            return parseResponse(responseJson, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("LLM调用失败 [provider={}]", name, e);
            throw new RuntimeException("LLM调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<StreamEvent> chatStream(LlmRequest request) {
        // 老入口（非计费调用方）：usage 不计，回落到带 sink 的实现并传 no-op。
        return chatStream(request, usage -> {});
    }

    /**
     * 流式 usage side-channel（Step9 计费核心，最高风险）：
     * <ul>
     *   <li>请求体加 {@code stream_options.include_usage=true}，要求 OpenAI 兼容端点在末 chunk 回 usage；</li>
     *   <li>建 {@link AtomicReference}，在 {@code .map} 阶段把末 chunk 的 usage 写入 ref（usage chunk 的
     *       content 为空，会被下游 {@code .filter} 丢弃——故必须在过滤前写 ref）；</li>
     *   <li>{@code doOnComplete} 读 ref，非空则回灌 {@code usageSink}（gateway 计费路径在此采+扣）；</li>
     *   <li><b>绝不新增/改 StreamEvent 类型、绝不改发出 Flux 的序列</b>——SSE 字节与改造前一致，
     *       否则 13 个调用方回归。</li>
     * </ul>
     * 流异常（doOnError）不会触发 doOnComplete，故不采不扣——由 gateway 的 onError 走 onFailure。
     */
    @Override
    public Flux<StreamEvent> chatStream(LlmRequest request, Consumer<TokenUsage> usageSink) {
        Map<String, Object> body = buildRequestBody(request);
        body.put("stream", true);
        // 要求末 chunk 带 usage（OpenAI/DeepSeek/Doubao/Qwen 等兼容端点均支持）
        body.put("stream_options", Map.of("include_usage", true));

        AtomicReference<TokenUsage> usageRef = new AtomicReference<>();

        return webClient.post()
                .uri(endpoint)  // 全 URL 直发（FR-001）
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .flatMap(chunk -> Flux.fromArray(chunk.split("\\R")))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .map(line -> line.startsWith("data:") ? line.substring(5).trim() : line)
                .filter(data -> data.startsWith("{") || "[DONE]".equals(data))
                .filter(data -> !"[DONE]".equals(data))
                .map(data -> parseStreamChunk(data, usageRef))   // side-channel：写 ref + 返 StreamEvent
                .filter(evt -> evt.getContent() != null && !evt.getContent().isEmpty())
                .doOnComplete(() -> {
                    TokenUsage usage = usageRef.get();
                    if (usage != null) {
                        usageSink.accept(usage);
                    }
                });
    }

    @Override
    public boolean supports(String model) {
        if (model == null) return false;
        return supportedModels.contains(model);
    }

    @Override
    public List<String> getSupportedModels() {
        return List.copyOf(supportedModels);
    }

    private Duration resolveTimeout(LlmRequest request) {
        if (request == null || request.getTimeoutMs() == null || request.getTimeoutMs() <= 0) {
            return RESPONSE_TIMEOUT;
        }
        return Duration.ofMillis(request.getTimeoutMs());
    }

    @Override
    public float[] embed(String text, String model) {
        return embedWithUsage(text, model).getEmbedding();
    }

    /**
     * embedding + usage（Step11 计费用）：解析 embed 响应 {@code /usage}（prompt_tokens）。
     * usage 缺失返 null（gateway 估算兜底）。返回 {@link EmbedResult}。
     */
    @Override
    public EmbedResult embedWithUsage(String text, String model) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            boolean qwenMultimodalProtocol = usesQwenMultimodalEmbeddingProtocol();
            if (qwenMultimodalProtocol) {
                body.put("input", Map.of("contents", List.of(Map.of("text", text))));
                body.put("parameters", Map.of(
                        "enable_fusion", true,
                        "dimension", QWEN_MULTIMODAL_EMBEDDING_DIMENSION));
            } else {
                body.put("input", text);
            }
            // 全 URL 直发（FR-001）：EMBEDDING 行的 endpoint 即完整 embed URL（V60 补全 /embeddings）
            String responseJson = webClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(RESPONSE_TIMEOUT);
            return parseEmbedResult(responseJson, qwenMultimodalProtocol);
        } catch (Exception e) {
            // 安全审计 #3：不把 e.getMessage()（可能含响应片段/内部细节）拼进对外异常，仅服务端日志。
            log.warn("embedding 调用失败 provider={}: {}", name, e.getMessage());
            throw new RuntimeException("embedding 调用失败（provider=" + name + "）", e);
        }
    }

    /**
     * C5 多模态 embed（WP5 Step1）：contents 数组（text/image 段）。
     * <p>协议分派（坑点：DashScope 多模态与 OpenAI 兼容 /embeddings 不同形）——
     * 端点含 {@code /multimodal-embedding/} → contents 数组+fusion/2048 维；否则普通 /embeddings
     * <b>不支持图片段</b>（立即拒，不发请求），纯文本段拼接回退单串走既有路径。
     * <p>熔断：多模态调用失败一次 → 该 provider+model 多模态通道开断 10 分钟（fast-fail 不发请求），
     * 防止不可用模型反复打挂索引链路（自动恢复，无需人工介入）。
     */
    @Override
    public EmbedResult embedWithUsage(List<com.superprogrammer.llm.dto.EmbedContentPart> contents, String model) {
        if (contents == null || contents.isEmpty()) {
            throw new IllegalArgumentException("多模态 embed contents 为空");
        }
        boolean hasImage = contents.stream().anyMatch(p -> p.image() != null && !p.image().isBlank());
        if (!usesQwenMultimodalEmbeddingProtocol()) {
            if (hasImage) {
                throw new IllegalArgumentException(
                        "embedding 端点不支持图片输入（需 /multimodal-embedding/ 协议）: " + name);
            }
            // 纯文本段：拼接回退单串（行为等同既有 embedWithUsage(String,...)）
            return embedWithUsage(contents.stream()
                    .map(p -> p.text() == null ? "" : p.text())
                    .filter(t -> !t.isBlank())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse(""), model);
        }
        String breakerKey = name + "|" + model;
        long openUntil = MULTIMODAL_OPEN_UNTIL.getOrDefault(breakerKey, 0L);
        if (System.currentTimeMillis() < openUntil) {
            throw new IllegalStateException("多模态 embed 熔断中（" + ((openUntil - System.currentTimeMillis()) / 1000)
                    + "s 后自动恢复）provider=" + name);
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            List<Map<String, Object>> parts = new ArrayList<>();
            for (com.superprogrammer.llm.dto.EmbedContentPart p : contents) {
                Map<String, Object> m = new LinkedHashMap<>();
                if (p.text() != null && !p.text().isBlank()) {
                    m.put("text", p.text());
                }
                if (p.image() != null && !p.image().isBlank()) {
                    // Phase4 实测修复（Bug #6）：该网关要求 Base64 带 data:image/xxx;base64, 前缀，
                    // 裸 Base64 直接 400（InternalError.Algo.InvalidParameter: Base64 must start
                    // with 'image/xxx;base64'）。EmbedContentPart 不带 mime，按 Base64 头魔数嗅探补前缀。
                    m.put("image", toImageDataUri(p.image()));
                }
                if (!m.isEmpty()) {
                    parts.add(m);
                }
            }
            body.put("input", Map.of("contents", parts));
            body.put("parameters", Map.of(
                    "enable_fusion", true,
                    "dimension", QWEN_MULTIMODAL_EMBEDDING_DIMENSION));
            String responseJson = webClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(RESPONSE_TIMEOUT);
            return parseEmbedResult(responseJson, true);
        } catch (Exception e) {
            MULTIMODAL_OPEN_UNTIL.put(breakerKey, System.currentTimeMillis() + MULTIMODAL_BREAKER_MS);
            log.warn("多模态 embedding 调用失败（熔断 {}ms）provider={}: {}", MULTIMODAL_BREAKER_MS, name, e.getMessage());
            throw new RuntimeException("多模态 embedding 调用失败（provider=" + name + "）", e);
        }
    }

    /** embed 响应统一解析（文本/多模态两协议共用）：向量提取 + 维度校验 + usage。 */
    private EmbedResult parseEmbedResult(String responseJson, boolean qwenMultimodalProtocol) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode arr = qwenMultimodalProtocol
                ? root.at("/output/embeddings/0/embedding")
                : root.at("/data/0/embedding");
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            // 安全审计 #3：响应体可能含 SSRF 取回的内网/云元数据内容，禁止回显进异常消息（防泄露）。
            // 仅记录响应长度，避免正文、向量或上游细节进入日志。
            log.warn("embedding 响应格式非预期 provider={} bodyLen={}", name, responseJson.length());
            throw new RuntimeException("embedding 响应格式非预期（provider=" + name + "）");
        }
        if (qwenMultimodalProtocol && arr.size() != QWEN_MULTIMODAL_EMBEDDING_DIMENSION) {
            log.warn("embedding 维度不匹配 provider={} expected={} actual={}",
                    name, QWEN_MULTIMODAL_EMBEDDING_DIMENSION, arr.size());
            throw new RuntimeException("embedding 响应维度非预期（provider=" + name + "）");
        }
        float[] vec = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            vec[i] = (float) arr.get(i).asDouble();
        }
        // usage：embed 多只回 prompt_tokens（input），无 completion
        JsonNode usageNode = root.path("usage");
        TokenUsage usage = null;
        if (usageNode.isObject() && !usageNode.isEmpty()) {
            int prompt = qwenMultimodalProtocol
                    ? usageNode.path("input_tokens").asInt(0)
                    : usageNode.path("prompt_tokens").asInt(0);
            usage = TokenUsage.builder()
                    .promptTokens(prompt)
                    .completionTokens(0)
                    .totalTokens(usageNode.path("total_tokens").asInt(prompt))
                    .build();
        }
        return EmbedResult.builder().embedding(vec).usage(usage).build();
    }

    private boolean usesQwenMultimodalEmbeddingProtocol() {
        return endpoint.contains("/multimodal-embedding/");
    }

    /**
     * Phase4 实测修复（Bug #6）：多模态 embed 图片段统一转 data URI。
     * 已是 {@code data:} 开头原样返回；否则按 Base64 首字节魔数嗅探 mime
     * （PNG/JPEG/GIF/WebP/BMP），嗅不出默认 png（网关按实际字节解码，前缀仅作声明）。
     */
    static String toImageDataUri(String image) {
        if (image.startsWith("data:")) {
            return image;
        }
        String mime;
        if (image.startsWith("iVBORw0KGgo")) {
            mime = "image/png";
        } else if (image.startsWith("/9j/")) {
            mime = "image/jpeg";
        } else if (image.startsWith("R0lGOD")) {
            mime = "image/gif";
        } else if (image.startsWith("UklGR")) {
            mime = "image/webp";
        } else if (image.startsWith("Qk")) {
            mime = "image/bmp";
        } else {
            mime = "image/png";
        }
        return "data:" + mime + ";base64," + image;
    }

    @Override
    public RerankResult rerank(RerankRequest request) {
        if (request == null || request.getModel() == null || request.getModel().isBlank()
                || request.getQuery() == null || request.getQuery().isBlank()
                || request.getDocuments() == null || request.getDocuments().isEmpty()) {
            throw new IllegalArgumentException("重排请求缺少必要参数");
        }
        int topN = request.getTopN() == null ? request.getDocuments().size() : request.getTopN();
        if (topN <= 0 || topN > request.getDocuments().size()) {
            throw new IllegalArgumentException("重排 topN 超出候选范围");
        }
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", request.getModel());
            body.put("documents", request.getDocuments());
            body.put("query", request.getQuery());
            body.put("top_n", topN);
            body.put("instruct", request.getInstruct() == null || request.getInstruct().isBlank()
                    ? RerankRequest.DEFAULT_INSTRUCT : request.getInstruct());

            String responseJson = webClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(RESPONSE_TIMEOUT);
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                log.warn("rerank 响应格式非预期 provider={} bodyLen={}", name, responseJson.length());
                throw new RuntimeException("rerank 响应格式非预期（provider=" + name + "）");
            }

            Set<Integer> seen = new HashSet<>();
            List<RerankResult.Item> items = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= request.getDocuments().size() || !seen.add(index)) {
                    throw new RuntimeException("rerank 响应索引非法（provider=" + name + "）");
                }
                JsonNode scoreNode = item.path("relevance_score");
                if (!scoreNode.isNumber()) {
                    throw new RuntimeException("rerank 响应分数非法（provider=" + name + "）");
                }
                items.add(RerankResult.Item.builder()
                        .index(index)
                        .score(scoreNode.asDouble())
                        .build());
            }

            JsonNode usageNode = root.path("usage");
            TokenUsage usage = null;
            if (usageNode.isObject() && !usageNode.isEmpty()) {
                // Phase4 实测：部分网关（ctaigw/qwen）rerank 只返回 total_tokens 不返回 input_tokens，
                // 裸取 input_tokens=0 会让网关的估算兜底失效（usage 非 null 即 SUCCESS），恒 0 计费。
                int inputTokens = usageNode.path("input_tokens").asInt(0);
                if (inputTokens <= 0) {
                    inputTokens = usageNode.path("total_tokens").asInt(0);
                }
                if (inputTokens > 0) {
                    usage = TokenUsage.builder()
                            .promptTokens(inputTokens)
                            .completionTokens(0)
                            .totalTokens(usageNode.path("total_tokens").asInt(inputTokens))
                            .build();
                }
                // 交叉审查加固：usage 对象非空但 token 字段全缺/为 0 时保持 usage=null，
                // 让 LlmGateway 落 TokenEstimator 估算（ESTIMATED）分支——宁估不漏，不恒 0 计 SUCCESS。
            }
            return RerankResult.builder()
                    .items(items)
                    .model(request.getModel())
                    .duration(System.currentTimeMillis() - start)
                    .usage(usage)
                    .build();
        } catch (Exception e) {
            log.warn("rerank 调用失败 provider={} errorType={}", name, e.getClass().getSimpleName());
            throw new RuntimeException("rerank 调用失败（provider=" + name + "）", e);
        }
    }

    private Map<String, Object> buildRequestBody(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        if (request.getStream() != null) {
            body.put("stream", request.getStream());
        }
        // 修复IX-1 A3：声明制思考参数（未声明/档位空/模型不在白名单=零参数，现状）。
        applyThinkingParam(body, request);

        List<Map<String, Object>> messages = new ArrayList<>();
        for (LlmMessage msg : request.getMessages()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", msg.getRole().toLowerCase());
            m.put("content", buildOpenAiContent(msg));
            messages.add(m);
        }
        body.put("messages", messages);

        log.debug("LLM请求体 [provider={}]: {}", name, body);
        return body;
    }

    /**
     * 修复IX-1 A3：按声明映射思考档位（优先级 thinkingLevel > disableThinking > 不发）。
     * toggle 风格无深度态——DEEP 与 STANDARD 同发 enabled（levelsFor 已诚实只下发两档，
     * DEEP 兜底兼容「localStorage 残留档」场景，不 400）。
     */
    private void applyThinkingParam(Map<String, Object> body, LlmRequest request) {
        if (thinkingSpec == null || !thinkingSpec.appliesTo(request.getModel())) {
            return;
        }
        ThinkingLevel level = request.getThinkingLevel() != null ? request.getThinkingLevel()
                : (Boolean.TRUE.equals(request.getDisableThinking()) ? ThinkingLevel.OFF : null);
        if (level == null) {
            return;
        }
        if (thinkingSpec.style() == ThinkingSpec.Style.TOGGLE) {
            body.put("thinking", Map.of("type", level == ThinkingLevel.OFF ? "disabled" : "enabled"));
        } else {
            body.put("reasoning_effort", switch (level) {
                case OFF -> "low";
                case STANDARD -> "medium";
                case DEEP -> "high";
            });
        }
    }

    /**
     * OpenAI content 序列化：parts 非空 → content 数组
     * （text={type:text,text} / image={type:image_url,image_url:{url:"data:<mime>;base64,<b64>"}}）；
     * 否则回退老字符串 content（零行为变化）。
     */
    private Object buildOpenAiContent(LlmMessage msg) {
        if (msg.getParts() == null || msg.getParts().isEmpty()) {
            return msg.getContent() == null ? "" : msg.getContent();
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (ContentPart p : msg.getParts()) {
            if ("image".equalsIgnoreCase(p.getType())) {
                String mime = p.getMediaType() == null ? "image/png" : p.getMediaType();
                String dataUrl = "data:" + mime + ";base64," + p.getData();
                Map<String, Object> imageUrl = new LinkedHashMap<>();
                imageUrl.put("url", dataUrl);
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "image_url");
                block.put("image_url", imageUrl);
                blocks.add(block);
            } else {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "text");
                block.put("text", p.getText() == null ? "" : p.getText());
                blocks.add(block);
            }
        }
        return blocks;
    }

    private LlmResponse parseResponse(String json, long duration) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        String content = root.at("/choices/0/message/content").asText("");
        String model = root.at("/model").asText("");

        TokenUsage usage = buildUsage(root.path("usage"));

        return LlmResponse.builder()
                .content(content)
                .usage(usage)
                .model(model)
                .duration(duration)
                .build();
    }

    /**
     * OpenAI 兼容 usage 归一（V160 D3 / 9x-1）：
     * prompt_tokens_details.cached_tokens → cachedTokens；promptTokens = prompt_tokens − cached
     * （协议的 prompt 含命中部分，计费要的是未命中输入；cached 字段缺失 → cachedTokens=null，
     * promptTokens=原值，与老口径逐分一致）。cached 大于 prompt（异常数据）钳 0 不负数。
     */
    private static TokenUsage buildUsage(JsonNode usageNode) {
        int prompt = usageNode.path("prompt_tokens").asInt(0);
        JsonNode cachedNode = usageNode.path("prompt_tokens_details").path("cached_tokens");
        Long cached = cachedNode.isNumber() ? cachedNode.asLong() : null;
        int netPrompt = cached != null ? Math.max(0, prompt - cached.intValue()) : prompt;
        return TokenUsage.builder()
                .promptTokens(netPrompt)
                .completionTokens(usageNode.path("completion_tokens").asInt(0))
                .totalTokens(usageNode.path("total_tokens").asInt(0))
                .cachedTokens(cached)
                .build();
    }

    /**
     * 解析单个 SSE data chunk，并把末 chunk 的 usage 写入 {@code usageRef}（side-channel）。
     * <p>usage 在流末随 {@code include_usage} 到达，其 {@code choices} 多为空 → content 为空，
     * 会被 {@code chatStream} 的 {@code .filter} 丢弃；此处先写 ref，{@code doOnComplete} 读取，
     * 故 usage 不丢。<b>返回的 StreamEvent 与改造前完全一致（不动 Flux 序列）。</b>
     */
    private StreamEvent parseStreamChunk(String data, AtomicReference<TokenUsage> usageRef) {
        try {
            JsonNode node = objectMapper.readTree(data);

            // side-channel：usage 写 ref（末 chunk，choices 通常空）
            JsonNode usageNode = node.path("usage");
            if (usageNode.isObject() && !usageNode.isEmpty()) {
                usageRef.set(buildUsage(usageNode));
            }

            JsonNode delta = node.at("/choices/0/delta");

            // DeepSeek R1 系列返回 reasoning_content
            if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
                String text = delta.get("reasoning_content").asText("");
                if (!text.isEmpty()) return StreamEvent.thinking(text);
            }

            // 标准 content
            if (delta.has("content") && !delta.get("content").isNull()) {
                String text = delta.get("content").asText("");
                if (!text.isEmpty()) return StreamEvent.chunk(text);
            }

            return StreamEvent.chunk("");
        } catch (Exception e) {
            return StreamEvent.chunk("");
        }
    }
}
