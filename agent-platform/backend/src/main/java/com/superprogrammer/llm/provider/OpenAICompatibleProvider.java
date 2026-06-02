package com.superprogrammer.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.llm.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.*;

@Slf4j
public class OpenAICompatibleProvider implements LlmProviderInterface {

    private final String name;
    private final Set<String> supportedModels;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OpenAICompatibleProvider(String name, String baseUrl, String apiKey, List<String> models, ObjectMapper objectMapper) {
        this.name = name;
        this.supportedModels = models != null ? new HashSet<>(models) : Collections.emptySet();
        this.objectMapper = objectMapper;
        // Normalize: strip trailing /v1 to avoid /v1/v1/ duplication
        String normalized = baseUrl.replaceAll("/v1/?$", "");
        this.webClient = WebClient.builder()
                .baseUrl(normalized)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> body = buildRequestBody(request);
            String responseJson = webClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
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
                .uri("/v1/chat/completions")
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

        List<Map<String, String>> messages = new ArrayList<>();
        for (LlmMessage msg : request.getMessages()) {
            messages.add(Map.of("role", msg.getRole().toLowerCase(), "content", msg.getContent()));
        }
        body.put("messages", messages);

        log.debug("LLM请求体 [provider={}]: {}", name, body);
        return body;
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
