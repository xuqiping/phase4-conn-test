package com.superprogrammer.workreport.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.ai.dto.AiConfigVO;
import com.superprogrammer.ai.service.AiConfigService;
import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmIntentClient {

    private final RestTemplateBuilder restTemplateBuilder;
    private final AiConfigService aiConfigService;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    public Map<String, Object> parseIntent(Long userId, String text) {
        AiConfigVO config = aiConfigService.getEffectiveConfig(userId, null);
        if (config == null || !Boolean.TRUE.equals(config.enabled())) {
            log.warn("[LlmIntentClient] 未找到有效 AI 配置，跳过 LLM 意图识别");
            return null;
        }
        String apiKey = aiConfigService.getDecryptedApiKey(userId, config.id());
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[LlmIntentClient] AI 配置未设置 API Key，跳过 LLM 意图识别");
            return null;
        }

        String prompt = buildPrompt(text);
        try {
            String response = callLlm(prompt, config, apiKey);
            Map<String, Object> result = objectMapper.readValue(response, Map.class);
            if (isValidIntentResult(result)) {
                return result;
            }
            log.warn("[LlmIntentClient] LLM 返回结果不符合 schema: {}", response);
            return null;
        } catch (JsonProcessingException e) {
            log.warn("[LlmIntentClient] LLM 返回非 JSON，尝试提取", e);
            return extractJsonFromText(e.getOriginalMessage());
        } catch (Exception e) {
            log.error("[LlmIntentClient] LLM 意图识别失败", e);
            return null;
        }
    }

    private boolean isValidIntentResult(Map<String, Object> result) {
        if (result == null) {
            return false;
        }
        Object intent = result.get("intent");
        Object confidence = result.get("confidence");
        Object entities = result.get("entities");
        return intent instanceof String && !((String) intent).isBlank()
                && confidence instanceof Number
                && entities instanceof Map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractJsonFromText(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                Map<String, Object> result = objectMapper.readValue(text.substring(start, end + 1), Map.class);
                if (isValidIntentResult(result)) {
                    return result;
                }
            } catch (JsonProcessingException ignored) {
            }
        }
        return null;
    }

    private String buildPrompt(String text) {
        return """
                你是一个工作汇报助手的意图识别器。请分析下面的用户输入，判断用户意图。
                支持的意图：
                - complete_fixed_work: 标记固定工作完成（如“完成日报设计”）
                - add_work_log: 记录工作日志（如“今天做了需求评审”）
                - add_inspiration: 记录灵感/想法（如“有个想法：优化推送流程 #优化”）
                - help: 请求帮助/指令说明
                - unknown: 其他

                请输出严格的 JSON 格式，不要包含其他内容：
                {
                  "intent": "complete_fixed_work|add_work_log|add_inspiration|help|unknown",
                  "confidence": 0.0-1.0,
                  "entities": {
                    "task_name": "任务名称（complete_fixed_work 时）",
                    "content": "内容（add_work_log/add_inspiration 时）",
                    "date": "日期描述或 YYYY-MM-DD",
                    "tags": ["标签1", "标签2"]
                  }
                }

                用户输入：""" + "\"" + text + "\"";
    }

    private String callLlm(String prompt, AiConfigVO config, String apiKey) {
        int timeoutSeconds = config.timeoutSeconds() != null && config.timeoutSeconds() > 0
                ? config.timeoutSeconds()
                : 30;
        int maxTokens = config.maxTokens() != null && config.maxTokens() > 0
                ? config.maxTokens()
                : 512;

        return switch (config.provider().toLowerCase()) {
            case "qwen" -> callOpenAiCompatible(prompt, config, apiKey, timeoutSeconds, maxTokens,
                    config.endpoint() == null || config.endpoint().isBlank()
                            ? "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
                            : config.endpoint(),
                    config.model() == null || config.model().isBlank() ? "qwen-turbo" : config.model());
            case "doubao" -> callOpenAiCompatible(prompt, config, apiKey, timeoutSeconds, maxTokens,
                    config.endpoint() == null || config.endpoint().isBlank()
                            ? "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
                            : config.endpoint(),
                    config.model() == null || config.model().isBlank() ? "doubao-lite-4k" : config.model());
            case "custom" -> {
                if (config.endpoint() == null || config.endpoint().isBlank()) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "自定义 Provider 必须填写 Endpoint");
                }
                if (config.model() == null || config.model().isBlank()) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "自定义 Provider 必须填写模型名称");
                }
                yield callOpenAiCompatible(prompt, config, apiKey, timeoutSeconds, maxTokens,
                        config.endpoint(), config.model());
            }
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的 AI 提供商: " + config.provider());
        };
    }

    @SuppressWarnings("unchecked")
    private String callOpenAiCompatible(String prompt, AiConfigVO config, String apiKey,
                                         int timeoutSeconds, int maxTokens, String url, String model) {
        String normalizedUrl = normalizeOpenAiEndpoint(url);
        RestTemplate restTemplate = buildRestTemplate(timeoutSeconds);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.set("Content-Type", "application/json");

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "max_tokens", maxTokens
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<Map> response = restTemplate.exchange(
                normalizedUrl, HttpMethod.POST, entity, Map.class
        );

        Map<String, Object> body = response.getBody();
        if (body == null) {
            throw new RuntimeException("AI 响应为空");
        }

        String errorMessage = extractErrorMessage(body);
        if (errorMessage != null) {
            throw new RuntimeException(errorMessage);
        }

        if (body.get("choices") == null) {
            throw new RuntimeException("AI 响应格式错误：缺少 choices");
        }

        List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
        if (choices.isEmpty()) {
            throw new RuntimeException("AI 响应 choices 为空");
        }

        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null || message.get("content") == null) {
            throw new RuntimeException("AI 响应内容为空");
        }

        return message.get("content").toString().trim();
    }

    private String normalizeOpenAiEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return endpoint;
        }
        String url = endpoint.replaceAll("/+$", "");
        if (url.endsWith("/chat/completions")) {
            return url;
        }
        if (url.endsWith("/api/coding")) {
            return url + "/v3/chat/completions";
        }
        return url + "/chat/completions";
    }

    private RestTemplate buildRestTemplate(int timeoutSeconds) {
        return restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                .setReadTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @SuppressWarnings("unchecked")
    private String extractErrorMessage(Map<String, Object> body) {
        Object error = body.get("error");
        if (error instanceof String errorString) {
            return errorString;
        }
        if (error instanceof Map<?, ?> errorMap) {
            Object message = errorMap.get("message");
            if (message != null) {
                return message.toString();
            }
        }
        Object message = body.get("message");
        if (message != null) {
            return message.toString();
        }
        return null;
    }
}
