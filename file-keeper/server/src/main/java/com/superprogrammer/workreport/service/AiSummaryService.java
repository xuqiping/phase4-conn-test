package com.superprogrammer.workreport.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.entity.WorkLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiSummaryService {

    private final RestTemplate restTemplate;

    @Value("${work-report.ai.provider:qwen}")
    private String aiProvider;

    @Value("${work-report.ai.api-key:}")
    private String apiKey;

    @Value("${work-report.ai.endpoint:}")
    private String endpoint;

    @Value("${work-report.ai.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${work-report.ai.max-tokens:2048}")
    private int maxTokens;

    @Value("${work-report.ai.model:}")
    private String model;

    public String summarize(List<WorkLog> logs, List<FixedWorkItem> fixedWorkItems, String reportType) {
        if (logs.isEmpty() && fixedWorkItems.isEmpty()) {
            return "";
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未配置 AI API Key，使用降级策略");
            return fallbackSummary(logs, fixedWorkItems);
        }

        String prompt = buildPrompt(logs, fixedWorkItems, reportType);

        try {
            return callAiApi(prompt);
        } catch (Exception e) {
            log.error("AI 总结失败，降级为简单拼接", e);
            return fallbackSummary(logs, fixedWorkItems);
        }
    }

    private String buildPrompt(List<WorkLog> logs, List<FixedWorkItem> fixedWorkItems, String reportType) {
        String reportName = "DAILY".equals(reportType) ? "日报" : "周报";
        String todayLabel = "DAILY".equals(reportType) ? "今日" : "本周";

        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下工作记录和固定工作完成情况，生成一份简洁规范的").append(reportName).append("：\n\n");
        sb.append("【工作记录】\n");
        for (WorkLog log : logs) {
            sb.append("- ").append(log.getContent()).append("\n");
        }
        sb.append("\n【固定工作完成情况】\n");
        for (FixedWorkItem item : fixedWorkItems) {
            sb.append("- ").append(item.getContent()).append("（已完成）\n");
        }
        sb.append("\n要求：\n");
        sb.append("1. 用第一人称\n");
        sb.append("2. 分").append(todayLabel).append("工作、遇到的问题、下一步计划三部分\n");
        sb.append("3. 语言简洁专业\n");
        return sb.toString();
    }

    private String callAiApi(String prompt) {
        return switch (aiProvider.toLowerCase()) {
            case "qwen" -> callQwen(prompt);
            case "doubao" -> callDoubao(prompt);
            case "claude" -> callClaude(prompt);
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的 AI 提供商: " + aiProvider);
        };
    }

    private String callQwen(String prompt) {
        String url = endpoint.isBlank() ? "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions" : endpoint;
        String requestModel = model.isBlank() ? "qwen-turbo" : model;
        return callOpenAiCompatibleApi(url, requestModel, prompt);
    }

    private String callDoubao(String prompt) {
        String url = endpoint.isBlank() ? "https://ark.cn-beijing.volces.com/api/v3/chat/completions" : endpoint;
        String requestModel = model.isBlank() ? "doubao-lite-4k" : model;
        return callOpenAiCompatibleApi(url, requestModel, prompt);
    }

    @SuppressWarnings("unchecked")
    private String callOpenAiCompatibleApi(String url, String requestModel, String prompt) {
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
                url, HttpMethod.POST, entity, Map.class
        );

        Map<String, Object> body = response.getBody();
        if (body == null || body.get("choices") == null) {
            throw new RuntimeException("AI 响应为空或格式错误");
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
    private String callClaude(String prompt) {
        String url = endpoint.isBlank() ? "https://api.anthropic.com/v1/messages" : endpoint;
        String requestModel = model.isBlank() ? "claude-3-haiku-20240307" : model;

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
        if (body == null || body.get("content") == null) {
            throw new RuntimeException("AI 响应为空或格式错误");
        }

        List<Map<String, Object>> contents = (List<Map<String, Object>>) body.get("content");
        if (contents.isEmpty()) {
            throw new RuntimeException("AI 响应 content 为空");
        }

        return contents.get(0).get("text").toString().trim();
    }

    private String fallbackSummary(List<WorkLog> logs, List<FixedWorkItem> fixedWorkItems) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 工作记录\n");
        logs.forEach(log -> sb.append("- ").append(log.getContent()).append("\n"));
        sb.append("\n## 固定工作完成情况\n");
        fixedWorkItems.forEach(item -> sb.append("- ").append(item.getContent()).append("\n"));
        return sb.toString();
    }
}
