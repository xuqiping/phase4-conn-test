package com.superprogrammer.workreport.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class NlpIntentService {

    public record IntentResult(String intent, double confidence, Map<String, Object> entities) {
    }

    private static final double RULE_CONFIDENCE_THRESHOLD = 0.6;
    private static final double AUTO_CONFIRM_THRESHOLD = 0.85;

    private static final Pattern COMPLETE_PATTERN = Pattern.compile("(?:完成|做完|搞定|标记完成|done|finish)(?:了|掉|\\s+)?[：:\\s]*(.+?)(?:\\s+|$)");
    private static final Pattern WORK_LOG_PATTERN = Pattern.compile("(?:今天做了|记录了|工作记录|log)(?:：|\\s+)?(.+?)(?:\\s+|$)");
    private static final Pattern INSPIRATION_PATTERN = Pattern.compile("(?:灵感|想法|idea|随记)(?:：|\\s+)?(.+)$");
    private static final Pattern HELP_PATTERN = Pattern.compile("(?:帮助|help|指令|怎么用)");

    private final DateParseService dateParseService;
    private final LlmIntentClient llmIntentClient;

    public IntentResult parse(Long userId, String text) {
        if (text == null || text.isBlank()) {
            return new IntentResult("unknown", 0.0, Map.of());
        }
        String normalized = text.trim();

        IntentResult ruleResult = parseByRule(normalized);
        if (ruleResult.confidence() >= RULE_CONFIDENCE_THRESHOLD) {
            return normalizeEntities(ruleResult);
        }

        Map<String, Object> llmResult = llmIntentClient.parseIntent(userId, normalized);
        if (llmResult != null) {
            String intent = (String) llmResult.get("intent");
            Number confidenceNum = (Number) llmResult.get("confidence");
            double confidence = confidenceNum == null ? 0.0 : confidenceNum.doubleValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> entities = (Map<String, Object>) llmResult.getOrDefault("entities", Map.of());
            return normalizeEntities(new IntentResult(intent, confidence, entities));
        }

        return ruleResult;
    }

    private IntentResult parseByRule(String normalized) {
        Matcher completeMatcher = COMPLETE_PATTERN.matcher(normalized);
        if (completeMatcher.find()) {
            String taskName = completeMatcher.group(1).trim();
            if (!taskName.isEmpty()) {
                Map<String, Object> entities = new HashMap<>();
                entities.put("task_name", taskName);
                entities.put("date", "today");
                return new IntentResult("complete_fixed_work", 0.9, entities);
            }
        }

        Matcher workLogMatcher = WORK_LOG_PATTERN.matcher(normalized);
        if (workLogMatcher.find()) {
            String content = workLogMatcher.group(1).trim();
            if (!content.isEmpty()) {
                Map<String, Object> entities = new HashMap<>();
                entities.put("content", content);
                entities.put("date", "today");
                return new IntentResult("add_work_log", 0.85, entities);
            }
        }

        Matcher inspirationMatcher = INSPIRATION_PATTERN.matcher(normalized);
        if (inspirationMatcher.find()) {
            String content = inspirationMatcher.group(1).trim();
            if (!content.isEmpty()) {
                Map<String, Object> entities = new HashMap<>();
                entities.put("content", content);
                entities.put("tags", extractTags(content));
                return new IntentResult("add_inspiration", 0.85, entities);
            }
        }

        if (HELP_PATTERN.matcher(normalized).find()) {
            return new IntentResult("help", 0.95, Map.of());
        }

        return new IntentResult("unknown", 0.0, Map.of());
    }

    private IntentResult normalizeEntities(IntentResult result) {
        Map<String, Object> entities = new HashMap<>(result.entities());
        Object dateValue = entities.get("date");
        if (dateValue != null) {
            String parsedDate = dateParseService.parseToIso(dateValue.toString());
            entities.put("date", parsedDate);
        } else {
            entities.put("date", dateParseService.parseToIso("today"));
        }

        Object tagsValue = entities.get("tags");
        if (tagsValue instanceof List<?> list) {
            entities.put("tags", list.stream().map(Object::toString).toList());
        } else if (tagsValue instanceof String str && !str.isBlank()) {
            entities.put("tags", List.of(str.trim()));
        }

        return new IntentResult(result.intent(), result.confidence(), Map.copyOf(entities));
    }

    private List<String> extractTags(String content) {
        List<String> tags = new ArrayList<>();
        Pattern tagPattern = Pattern.compile("#([\\w/\\u4e00-\\u9fa5-]+)");
        Matcher matcher = tagPattern.matcher(content);
        while (matcher.find()) {
            tags.add(matcher.group(1));
        }
        return tags;
    }
}
