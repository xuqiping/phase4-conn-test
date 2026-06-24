package com.superprogrammer.workreport.service;

import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.entity.WorkLog;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ReportTemplateEngine {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)\\}\\}");

    public String render(String templateContent, Map<String, Object> context) {
        String result = templateContent;
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            result = result.replace(placeholder, value);
        }
        // 未匹配到的占位符替换为空字符串
        return PLACEHOLDER_PATTERN.matcher(result).replaceAll("");
    }

    public Map<String, Object> buildContext(
            String aiSummary,
            List<WorkLog> logs,
            List<FixedWorkItem> fixedWorkItems,
            String reportType) {
        Map<String, Object> context = new HashMap<>();
        context.put("ai_summary", aiSummary);
        context.put("logs", formatLogs(logs));
        context.put("fixed_work", formatFixedWork(fixedWorkItems));
        context.put("plans", formatFixedWork(fixedWorkItems));
        context.put("report_type", reportType);
        context.put("generated_at", LocalDateTime.now().toString());
        context.put("issues", "");
        context.put("highlights", "");
        return context;
    }

    private String formatLogs(List<WorkLog> logs) {
        return logs.stream()
                .map(log -> "- " + log.getContent())
                .collect(Collectors.joining("\n"));
    }

    private String formatFixedWork(List<FixedWorkItem> items) {
        return items.stream()
                .map(item -> "- " + item.getContent())
                .collect(Collectors.joining("\n"));
    }
}
