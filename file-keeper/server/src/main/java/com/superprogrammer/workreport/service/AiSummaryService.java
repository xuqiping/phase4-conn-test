package com.superprogrammer.workreport.service;

import com.superprogrammer.ai.dto.AiConfigVO;
import com.superprogrammer.ai.service.AiConfigService;
import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.dto.FixedWorkCompletionStats;
import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.entity.InboundMessage;
import com.superprogrammer.workreport.entity.InspirationNote;
import com.superprogrammer.workreport.entity.WorkLog;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class AiSummaryService {

    private final RestTemplateBuilder restTemplateBuilder;
    private final AiConfigService aiConfigService;

    public record AiSummaryContext(
            List<WorkLog> logs,
            List<FixedWorkItem> fixedWorkItems,
            String reportType,
            FixedWorkCompletionStats completionStats,
            List<InboundMessage> inboxWorkLogs,
            List<InspirationNote> inspirationNotes
    ) {
    }

    public String summarize(List<WorkLog> logs, List<FixedWorkItem> fixedWorkItems,
                            String reportType, Long aiConfigId, Long userId) {
        return summarize(new AiSummaryContext(logs, fixedWorkItems, reportType, null, List.of(), List.of()), aiConfigId, userId);
    }

    public String summarize(AiSummaryContext context, Long aiConfigId, Long userId) {
        if (isEmptyContext(context)) {
            return "";
        }

        AiConfigVO config = aiConfigService.getEffectiveConfig(userId, aiConfigId);
        if (config == null || !Boolean.TRUE.equals(config.enabled())) {
            log.warn("未找到有效 AI 配置，使用降级策略");
            return fallbackSummary(context);
        }

        String apiKey = aiConfigService.getDecryptedApiKey(userId, aiConfigId);
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("AI 配置未设置 API Key，使用降级策略");
            return fallbackSummary(context);
        }

        String prompt = buildPrompt(context);

        try {
            return callAiApi(prompt, config, apiKey);
        } catch (Exception e) {
            log.error("AI 总结失败，降级为简单拼接", e);
            return fallbackSummary(context);
        }
    }

    private boolean isEmptyContext(AiSummaryContext context) {
        return context.logs().isEmpty()
                && context.fixedWorkItems().isEmpty()
                && (context.inboxWorkLogs() == null || context.inboxWorkLogs().isEmpty())
                && (context.inspirationNotes() == null || context.inspirationNotes().isEmpty());
    }

    public String testConnection(AiConfigVO config, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "API Key 不能为空");
        }
        return callAiApi("你好，请回复一条简单的测试消息。", config, apiKey);
    }

    private String buildPrompt(AiSummaryContext context) {
        String reportName = "DAILY".equals(context.reportType()) ? "日报" : "周报";
        String periodLabel = "DAILY".equals(context.reportType()) ? "今日" : "本周";
        String nextLabel = "DAILY".equals(context.reportType()) ? "明日" : "下周";

        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下信息生成一份简洁规范的").append(reportName).append("：\n\n");

        sb.append("【工作记录】\n");
        for (WorkLog log : context.logs()) {
            sb.append("- ").append(log.getContent()).append("\n");
        }
        if (context.logs().isEmpty()) {
            sb.append("无\n");
        }

        sb.append("\n【固定工作完成情况】\n");
        for (FixedWorkItem item : context.fixedWorkItems()) {
            sb.append("- ").append(item.getContent()).append("（已完成）\n");
        }
        if (context.fixedWorkItems().isEmpty()) {
            sb.append("无\n");
        }

        FixedWorkCompletionStats stats = context.completionStats();
        if (stats != null) {
            sb.append("\n【固定工作完成率】\n");
            int percentage = (int) Math.round(stats.overallCompletionRate() * 100);
            sb.append(percentage).append("%\n");

            if (!stats.missLog().isEmpty()) {
                sb.append("\n【逾期/未完成记录】\n");
                for (var entry : stats.missLog()) {
                    sb.append("- ").append(entry.date()).append(": ").append(entry.itemContent()).append("\n");
                }
            }
        }

        if (context.inboxWorkLogs() != null && !context.inboxWorkLogs().isEmpty()) {
            sb.append("\n【IM 录入工作记录】\n");
            for (InboundMessage message : context.inboxWorkLogs()) {
                sb.append("- ").append(message.getRawText()).append("\n");
            }
        }

        if (context.inspirationNotes() != null && !context.inspirationNotes().isEmpty()) {
            sb.append("\n【灵感随记】\n");
            for (InspirationNote note : context.inspirationNotes()) {
                sb.append("- ").append(note.getContent()).append("\n");
            }
        }

        sb.append("\n要求：\n");
        sb.append("1. 用第一人称\n");
        sb.append("2. 分四部分：").append(periodLabel).append("已完成、").append(periodLabel).append("未完成/逾期、").append(nextLabel).append("计划、灵感速览\n");
        sb.append("3. 语言简洁专业\n");
        return sb.toString();
    }

    private String callAiApi(String prompt, AiConfigVO config, String apiKey) {
        int timeoutSeconds = config.timeoutSeconds() != null && config.timeoutSeconds() > 0
                ? config.timeoutSeconds()
                : 30;
        int maxTokens = config.maxTokens() != null && config.maxTokens() > 0
                ? config.maxTokens()
                : 2048;

        return switch (config.provider().toLowerCase()) {
            case "qwen" -> callQwen(prompt, config, apiKey, timeoutSeconds, maxTokens);
            case "doubao" -> callDoubao(prompt, config, apiKey, timeoutSeconds, maxTokens);
            case "claude" -> callClaude(prompt, config, apiKey, timeoutSeconds, maxTokens);
            case "custom" -> callCustomOpenAiCompatible(prompt, config, apiKey, timeoutSeconds, maxTokens);
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的 AI 提供商: " + config.provider());
        };
    }

    private String callQwen(String prompt, AiConfigVO config, String apiKey, int timeoutSeconds, int maxTokens) {
        String url = config.endpoint() == null || config.endpoint().isBlank()
                ? "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
                : config.endpoint();
        String requestModel = config.model() == null || config.model().isBlank()
                ? "qwen-turbo"
                : config.model();
        return callOpenAiCompatibleApi(url, requestModel, prompt, apiKey, timeoutSeconds, maxTokens);
    }

    private String callDoubao(String prompt, AiConfigVO config, String apiKey, int timeoutSeconds, int maxTokens) {
        String url = config.endpoint() == null || config.endpoint().isBlank()
                ? "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
                : config.endpoint();
        String requestModel = config.model() == null || config.model().isBlank()
                ? "doubao-lite-4k"
                : config.model();
        return callOpenAiCompatibleApi(url, requestModel, prompt, apiKey, timeoutSeconds, maxTokens);
    }

    private String callCustomOpenAiCompatible(String prompt, AiConfigVO config, String apiKey, int timeoutSeconds, int maxTokens) {
        if (config.endpoint() == null || config.endpoint().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "自定义 Provider 必须填写 Endpoint");
        }
        if (config.model() == null || config.model().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "自定义 Provider 必须填写模型名称");
        }
        return callOpenAiCompatibleApi(config.endpoint(), config.model(), prompt, apiKey, timeoutSeconds, maxTokens);
    }

    public String normalizeOpenAiEndpoint(String endpoint) {
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

    @SuppressWarnings("unchecked")
    private String callOpenAiCompatibleApi(String url, String requestModel, String prompt, String apiKey,
                                           int timeoutSeconds, int maxTokens) {
        String normalizedUrl = normalizeOpenAiEndpoint(url);
        RestTemplate restTemplate = buildRestTemplate(timeoutSeconds);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.set("Content-Type", "application/json");

        Map<String, Object> requestBody = Map.of(
                "model", requestModel,
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

    @SuppressWarnings("unchecked")
    private String callClaude(String prompt, AiConfigVO config, String apiKey, int timeoutSeconds, int maxTokens) {
        String url = config.endpoint() == null || config.endpoint().isBlank()
                ? "https://api.anthropic.com/v1/messages"
                : config.endpoint();
        String requestModel = config.model() == null || config.model().isBlank()
                ? "claude-3-haiku-20240307"
                : config.model();

        RestTemplate restTemplate = buildRestTemplate(timeoutSeconds);

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        headers.set("Content-Type", "application/json");
        headers.set("anthropic-version", "2023-06-01");

        Map<String, Object> requestBody = Map.of(
                "model", requestModel,
                "max_tokens", maxTokens,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, Map.class
        );

        Map<String, Object> body = response.getBody();
        if (body == null) {
            throw new RuntimeException("AI 响应为空");
        }

        String errorMessage = extractErrorMessage(body);
        if (errorMessage != null) {
            throw new RuntimeException(errorMessage);
        }

        if (body.get("content") == null) {
            throw new RuntimeException("AI 响应格式错误：缺少 content");
        }

        List<Map<String, Object>> contents = (List<Map<String, Object>>) body.get("content");
        if (contents.isEmpty()) {
            throw new RuntimeException("AI 响应 content 为空");
        }

        return contents.get(0).get("text").toString().trim();
    }

    private RestTemplate buildRestTemplate(int timeoutSeconds) {
        return restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                .setReadTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    private String fallbackSummary(AiSummaryContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 工作记录\n");
        context.logs().forEach(log -> sb.append("- ").append(log.getContent()).append("\n"));
        sb.append("\n## 固定工作完成情况\n");
        context.fixedWorkItems().forEach(item -> sb.append("- ").append(item.getContent()).append("\n"));
        if (context.completionStats() != null && !context.completionStats().missLog().isEmpty()) {
            sb.append("\n## 逾期/未完成记录\n");
            context.completionStats().missLog().forEach(entry ->
                    sb.append("- ").append(entry.date()).append(": ").append(entry.itemContent()).append("\n"));
        }
        if (context.inspirationNotes() != null && !context.inspirationNotes().isEmpty()) {
            sb.append("\n## 灵感速览\n");
            context.inspirationNotes().forEach(note -> sb.append("- ").append(note.getContent()).append("\n"));
        }
        return sb.toString();
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
