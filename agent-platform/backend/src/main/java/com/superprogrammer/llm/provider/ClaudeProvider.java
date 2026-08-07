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
public class ClaudeProvider implements LlmProviderInterface {

    private final String name;
    private final Set<String> supportedModels;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    /** 完整请求 URL（V60 起 endpoint 即全 URL，如 …/v1/messages，运行时零拼接，FR-001）。 */
    private final String endpoint;
    /** 计费用：provider 主键（全局=llm_providers.id / 用户级=user_llm_providers.id）。 */
    private final Long providerId;
    /** 计费用：GLOBAL / USER。 */
    private final String providerScope;

    /** 连接建立超时（ms）。云上 DNS/路由抖动时避免线程长期挂起。 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** 单次响应超时。兜底 .block(Duration)，杜绝无超时 .block() 钉死线程。 */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    public ClaudeProvider(String endpoint, String apiKey, List<String> models, ObjectMapper objectMapper) {
        this("claude", endpoint, apiKey, models, objectMapper, null, "GLOBAL");
    }

    public ClaudeProvider(String name, String endpoint, String apiKey, List<String> models, ObjectMapper objectMapper) {
        this(name, endpoint, apiKey, models, objectMapper, null, "GLOBAL");
    }

    /** 全参构造：含计费用 providerId + providerScope（FR-计费）。 */
    public ClaudeProvider(String name, String endpoint, String apiKey, List<String> models,
                          ObjectMapper objectMapper, Long providerId, String providerScope) {
        this.name = name;
        this.supportedModels = models != null ? new HashSet<>(models) : Collections.emptySet();
        this.objectMapper = objectMapper;
        this.providerId = providerId;
        this.providerScope = providerScope != null ? providerScope : "GLOBAL";
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
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
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
                    .block(RESPONSE_TIMEOUT);
            return parseResponse(responseJson, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Claude调用失败", e);
            throw new RuntimeException("Claude调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<StreamEvent> chatStream(LlmRequest request) {
        // 老入口（非计费调用方）：回落带 sink 实现，传 no-op（usage 不计）。
        return chatStream(request, usage -> {});
    }

    /**
     * 流式 usage side-channel（Step10 计费核心）：
     * <ul>
     *   <li>建 {@link AtomicReference}，在 {@code .map} 阶段把 {@code message_start}（input_tokens）
     *       与 {@code message_delta}（output_tokens）写 ref——Claude 协议 usage 拆在这两个事件里；</li>
     *   <li>{@code doOnComplete} 读 ref 回灌 {@code usageSink}（gateway 计费路径在此采+扣）；</li>
     *   <li><b>不改 StreamEvent、不改发出 Flux 序列</b>。</li>
     * </ul>
     * 流异常（doOnError）不触发 doOnComplete → 不采不扣，由 gateway onFailure 兜。
     */
    @Override
    public Flux<StreamEvent> chatStream(LlmRequest request, Consumer<TokenUsage> usageSink) {
        Map<String, Object> body = buildRequestBody(request);
        body.put("stream", true);

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
                .map(data -> parseClaudeChunk(data, usageRef))   // side-channel：写 ref + 返 StreamEvent
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
        if (!supportedModels.isEmpty()) return supportedModels.contains(model);
        return model.startsWith("claude-") || model.startsWith("anthropic/");
    }

    @Override
    public float[] embed(String text, String model) {
        throw new UnsupportedOperationException("Claude 协议不支持 embedding，请用 OpenAI 兼容 provider（如 Doubao）");
    }

    private Map<String, Object> buildRequestBody(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("max_tokens", request.getMaxTokens());

        String systemPrompt = null;
        List<Map<String, Object>> messages = new ArrayList<>();
        for (LlmMessage msg : request.getMessages()) {
            String role = msg.getRole().toLowerCase();
            if ("system".equals(role)) {
                // Claude system 字段为纯字符串；多模态 parts 仅作用于 user/assistant。
                systemPrompt = msg.getContent();
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", role);
            m.put("content", buildClaudeContent(msg));
            messages.add(m);
        }
        if (systemPrompt != null) {
            body.put("system", systemPrompt);
        }
        body.put("messages", messages);
        if (!messages.isEmpty() && log.isDebugEnabled()) {
            Object c = messages.get(0).get("content");
            if (c instanceof List<?> list) {
                log.debug("Claude多模态请求 model={} blocks={}", request.getModel(),
                        list.stream().map(b -> ((Map<?, ?>) b).get("type")).toList());
            } else {
                log.debug("Claude文本请求 model={} contentLen={}", request.getModel(),
                        c == null ? 0 : String.valueOf(c).length());
            }
        }
        return body;
    }

    /**
     * Claude content 序列化：parts 非空 → content 数组
     * （image={type:image,source:{type:base64,media_type,data}} / text={type:text,text}）；
     * 否则回退老字符串 content（零行为变化）。
     */
    private Object buildClaudeContent(LlmMessage msg) {
        if (msg.getParts() == null || msg.getParts().isEmpty()) {
            return msg.getContent() == null ? "" : msg.getContent();
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (ContentPart p : msg.getParts()) {
            if ("image".equalsIgnoreCase(p.getType())) {
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("type", "base64");
                source.put("media_type", p.getMediaType());
                source.put("data", p.getData());
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "image");
                block.put("source", source);
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
        // content 为 block 数组：thinking 模型会先发 {type:thinking} 再发 {type:text}。
        // 取所有 type=text 的 text 拼接；空时回退 content/0/text（兼容无 thinking 的老响应）。
        StringBuilder textBuf = new StringBuilder();
        JsonNode contentArr = root.at("/content");
        if (contentArr.isArray()) {
            for (JsonNode block : contentArr) {
                if ("text".equals(block.at("/type").asText(""))) {
                    String t = block.at("/text").asText("");
                    if (!t.isEmpty()) {
                        if (textBuf.length() > 0) textBuf.append('\n');
                        textBuf.append(t);
                    }
                }
            }
        }
        String content = textBuf.length() > 0 ? textBuf.toString()
                : root.at("/content/0/text").asText("");
        String model = root.at("/model").asText("");

        TokenUsage usage = TokenUsage.builder()
                .promptTokens(root.at("/usage/input_tokens").asInt(0))
                .completionTokens(root.at("/usage/output_tokens").asInt(0))
                .totalTokens(root.at("/usage/input_tokens").asInt(0) + root.at("/usage/output_tokens").asInt(0))
                .build();

        return LlmResponse.builder()
                .content(content)
                .usage(usage)
                .model(model)
                .duration(duration)
                .build();
    }

    /**
     * 解析单个 Claude SSE chunk，并把 usage 写入 {@code usageRef}（side-channel）。
     * <p>Claude 协议 usage 拆在两个事件：
     * <ul>
     *   <li>{@code message_start} → {@code message.usage.input_tokens}（prompt，一次）；</li>
     *   <li>{@code message_delta} → {@code usage.output_tokens}（completion 累计，流末）。</li>
     * </ul>
     * 二者合并成一条 {@link TokenUsage}。usage 事件无 content → 返空 chunk 被下游过滤，
     * 故须在 {@code .map} 阶段写 ref，{@code doOnComplete} 读取。<b>StreamEvent 序列不变。</b>
     */
    private StreamEvent parseClaudeChunk(String data, AtomicReference<TokenUsage> usageRef) {
        try {
            JsonNode node = objectMapper.readTree(data);
            String type = node.at("/type").asText("");

            // side-channel：合并 message_start(input) + message_delta(output)
            if ("message_start".equals(type)) {
                int input = node.at("/message/usage/input_tokens").asInt(0);
                usageRef.set(TokenUsage.builder()
                        .promptTokens(input)
                        .completionTokens(0)
                        .totalTokens(input)
                        .build());
            } else if ("message_delta".equals(type)) {
                int output = node.at("/usage/output_tokens").asInt(0);
                int input = usageRef.get() != null ? defaultIfNull(usageRef.get().getPromptTokens()) : 0;
                usageRef.set(TokenUsage.builder()
                        .promptTokens(input)
                        .completionTokens(output)
                        .totalTokens(input + output)
                        .build());
            }

            if ("content_block_delta".equals(type)) {
                String deltaType = node.at("/delta/type").asText("");
                if ("thinking_delta".equals(deltaType)) {
                    String text = node.at("/delta/thinking").asText("");
                    if (!text.isEmpty()) return StreamEvent.thinking(text);
                } else if ("text_delta".equals(deltaType)) {
                    String text = node.at("/delta/text").asText("");
                    if (!text.isEmpty()) return StreamEvent.chunk(text);
                }
            }
            return StreamEvent.chunk("");
        } catch (Exception e) {
            return StreamEvent.chunk("");
        }
    }

    private static int defaultIfNull(Integer v) {
        return v == null ? 0 : v;
    }
}
