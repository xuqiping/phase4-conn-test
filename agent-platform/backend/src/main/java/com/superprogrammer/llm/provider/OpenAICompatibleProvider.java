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

    /** 连接建立超时（ms）。云上 DNS/路由抖动时避免线程长期挂起。 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** 单次响应超时。兜底 .block(Duration)，杜绝无超时 .block() 钉死线程。 */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    public OpenAICompatibleProvider(String name, String endpoint, String apiKey, List<String> models, ObjectMapper objectMapper) {
        this(name, endpoint, apiKey, models, objectMapper, null, "GLOBAL");
    }

    /** 全参构造：含计费用 providerId + providerScope（FR-计费）。 */
    public OpenAICompatibleProvider(String name, String endpoint, String apiKey, List<String> models,
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
                    .block(RESPONSE_TIMEOUT);
            return parseResponse(responseJson, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("LLM调用失败 [provider={}]", name, e);
            throw new RuntimeException("LLM调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<StreamEvent> chatStream(LlmRequest request) {
        Map<String, Object> body = buildRequestBody(request);
        body.put("stream", true);

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
                .map(this::parseStreamChunk)
                .filter(evt -> evt.getContent() != null && !evt.getContent().isEmpty());
    }

    @Override
    public boolean supports(String model) {
        if (model == null) return false;
        if (supportedModels.isEmpty()) return true; // no model list = accept all (fallback)
        return supportedModels.contains(model);
    }

    @Override
    public float[] embed(String text, String model) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", text);
            // 全 URL 直发（FR-001）：EMBEDDING 行的 endpoint 即完整 embed URL（V60 补全 /embeddings）
            String responseJson = webClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(RESPONSE_TIMEOUT);
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode arr = root.at("/data/0/embedding");
            if (arr == null || !arr.isArray() || arr.isEmpty()) {
                // 安全审计 #3：响应体可能含 SSRF 取回的内网/云元数据内容，禁止回显进异常消息（防泄露）。
                // 仅服务端日志记录（截断），抛固定话术。
                log.warn("embedding 响应格式非预期 provider={} bodyLen={} bodyHead={}",
                        name, responseJson.length(),
                        responseJson.length() > 200 ? responseJson.substring(0, 200) : responseJson);
                throw new RuntimeException("embedding 响应格式非预期（provider=" + name + "）");
            }
            float[] vec = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                vec[i] = (float) arr.get(i).asDouble();
            }
            return vec;
        } catch (Exception e) {
            // 安全审计 #3：不把 e.getMessage()（可能含响应片段/内部细节）拼进对外异常，仅服务端日志。
            log.warn("embedding 调用失败 provider={}: {}", name, e.getMessage());
            throw new RuntimeException("embedding 调用失败（provider=" + name + "）", e);
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

        TokenUsage usage = TokenUsage.builder()
                .promptTokens(root.at("/usage/prompt_tokens").asInt(0))
                .completionTokens(root.at("/usage/completion_tokens").asInt(0))
                .totalTokens(root.at("/usage/total_tokens").asInt(0))
                .build();

        return LlmResponse.builder()
                .content(content)
                .usage(usage)
                .model(model)
                .duration(duration)
                .build();
    }

    private StreamEvent parseStreamChunk(String data) {
        try {
            JsonNode node = objectMapper.readTree(data);
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
