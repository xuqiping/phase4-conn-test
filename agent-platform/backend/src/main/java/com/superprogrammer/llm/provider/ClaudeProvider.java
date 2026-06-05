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
public class ClaudeProvider implements LlmProviderInterface {

    private final String name;
    private final Set<String> supportedModels;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public ClaudeProvider(String baseUrl, String apiKey, List<String> models, ObjectMapper objectMapper) {
        this("claude", baseUrl, apiKey, models, objectMapper);
    }

    public ClaudeProvider(String name, String baseUrl, String apiKey, List<String> models, ObjectMapper objectMapper) {
        this.name = name;
        this.supportedModels = models != null ? new HashSet<>(models) : Collections.emptySet();
        this.objectMapper = objectMapper;
        // Normalize: strip trailing /v1 to avoid duplication
        String normalized = baseUrl.replaceAll("/v1/?$", "");
        this.webClient = WebClient.builder()
                .baseUrl(normalized)
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
    public LlmResponse chat(LlmRequest request) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> body = buildRequestBody(request);
            String responseJson = webClient.post()
                    .uri("/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return parseResponse(responseJson, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Claude调用失败", e);
            throw new RuntimeException("Claude调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<StreamEvent> chatStream(LlmRequest request) {
        Map<String, Object> body = buildRequestBody(request);
        body.put("stream", true);

        return webClient.post()
                .uri("/v1/messages")
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
                .map(this::parseClaudeChunk)
                .filter(evt -> evt.getContent() != null && !evt.getContent().isEmpty());
    }

    @Override
    public boolean supports(String model) {
        if (model == null) return false;
        if (!supportedModels.isEmpty()) return supportedModels.contains(model);
        return model.startsWith("claude-") || model.startsWith("anthropic/");
    }

    private Map<String, Object> buildRequestBody(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("max_tokens", request.getMaxTokens());

        String systemPrompt = null;
        List<Map<String, String>> messages = new ArrayList<>();
        for (LlmMessage msg : request.getMessages()) {
            String role = msg.getRole().toLowerCase();
            if ("system".equals(role)) {
                systemPrompt = msg.getContent();
            } else {
                messages.add(Map.of("role", role, "content", msg.getContent()));
            }
        }
        if (systemPrompt != null) {
            body.put("system", systemPrompt);
        }
        body.put("messages", messages);
        return body;
    }

    private LlmResponse parseResponse(String json, long duration) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        String content = root.at("/content/0/text").asText("");
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

    private StreamEvent parseClaudeChunk(String data) {
        try {
            JsonNode node = objectMapper.readTree(data);
            String type = node.at("/type").asText("");

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
}
