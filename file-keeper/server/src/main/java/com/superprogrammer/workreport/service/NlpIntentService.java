package com.superprogrammer.workreport.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class NlpIntentService {

    public record IntentResult(String intent, double confidence, Map<String, Object> entities) {
    }

    private static final double RULE_CONFIDENCE_THRESHOLD = 0.6;
    private static final double AUTO_CONFIRM_THRESHOLD = 0.85;

    private static final List<Pattern> COMPLETE_PATTERNS = List.of(
            // 完成日报设计 / finish daily report / completed daily report
            Pattern.compile("(?:完成|做完|搞定|标记完成|completed\\b|finished\\b|finish\\b)(?:了|掉|\\s+)?[：:\\s]*(.+)"),
            // 把日报设计标记为完成 / 将日报设计标记完成
            Pattern.compile("(?:把|将)\\s*(.+?)\\s*标记(?:为)?完成"),
            // 标记日报设计为完成
            Pattern.compile("标记\\s*(.+?)\\s*为完成"),
            // 日报设计标记为完成 / 日报设计标记完成
            Pattern.compile("(.+?)\\s*标记(?:为)?完成"),
            // 日报设计完成了 / 日报设计做完了 / 日报设计搞定了
            Pattern.compile("(.+?)(?:完成了|做完了|搞定了)"),
            // done with daily report
            Pattern.compile("done\\s+with\\s+(.+)")
    );
    private static final Pattern WORK_LOG_PATTERN = Pattern.compile("(?:今天做了|记录了|工作记录|log)(?:：|\\s+)?(.+?)(?:\\s+|$)");
    private static final Pattern INSPIRATION_PATTERN = Pattern.compile("(?:灵感|想法|idea|随记)(?:：|\\s+)?(.+)$");
    private static final Pattern HELP_PATTERN = Pattern.compile("^\\s*/?\\s*(?:帮助|help|指令|怎么用|菜单|menu|\\?)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Set<String> INVALID_TASK_NAMES = Set.of("我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "它们", "了", "掉", "过");

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
        for (Pattern pattern : COMPLETE_PATTERNS) {
            Matcher matcher = pattern.matcher(normalized);
            if (matcher.find()) {
                String taskName = matcher.group(1).trim();
                if (!taskName.isEmpty() && !INVALID_TASK_NAMES.contains(taskName)) {
                    Map<String, Object> entities = new HashMap<>();
                    entities.put("task_name", taskName);
                    entities.put("date", "today");
                    return new IntentResult("complete_fixed_work", 0.9, entities);
                }
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
