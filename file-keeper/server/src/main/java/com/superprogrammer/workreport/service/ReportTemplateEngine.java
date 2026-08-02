package com.superprogrammer.workreport.service;

import com.superprogrammer.workreport.dto.FixedWorkCompletionStats;
import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.entity.InboundMessage;
import com.superprogrammer.workreport.entity.InspirationNote;
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
        return PLACEHOLDER_PATTERN.matcher(result).replaceAll("");
    }

    public Map<String, Object> buildContext(
            String aiSummary,
            List<WorkLog> logs,
            List<FixedWorkItem> fixedWorkItems,
            String reportType) {
        return buildContext(aiSummary, logs, fixedWorkItems, null, List.of(), List.of(), reportType);
    }

    public Map<String, Object> buildContext(
            String aiSummary,
            List<WorkLog> logs,
            List<FixedWorkItem> fixedWorkItems,
            FixedWorkCompletionStats completionStats,
            List<InboundMessage> inboxWorkLogs,
            List<InspirationNote> inspirationNotes,
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
        context.put("fixed_work_completion_rate", formatCompletionRate(completionStats));
        context.put("fixed_work_miss_log", formatMissLog(completionStats));
        context.put("fixed_work_consecutive_miss_days", formatConsecutiveMissDays(completionStats));
        context.put("inbox_work_logs", formatInboxWorkLogs(inboxWorkLogs));
        context.put("inspiration_digest", formatInspirationDigest(inspirationNotes));
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

    private String formatCompletionRate(FixedWorkCompletionStats stats) {
        if (stats == null || stats.itemRates().isEmpty()) {
            return "暂无固定工作数据";
        }
        int percentage = (int) Math.round(stats.overallCompletionRate() * 100);
        int totalExpected = stats.itemRates().stream().mapToInt(FixedWorkCompletionStats.ItemCompletionRate::expectedCount).sum();
        int totalCompleted = stats.itemRates().stream().mapToInt(FixedWorkCompletionStats.ItemCompletionRate::completedCount).sum();
        return percentage + "% (" + totalCompleted + "/" + totalExpected + ")";
    }

    private String formatMissLog(FixedWorkCompletionStats stats) {
        if (stats == null || stats.missLog().isEmpty()) {
            return "无逾期记录";
        }
        return stats.missLog().stream()
                .map(entry -> "- " + entry.date() + ": " + entry.itemContent())
                .collect(Collectors.joining("\n"));
    }

    private String formatConsecutiveMissDays(FixedWorkCompletionStats stats) {
        if (stats == null || stats.maxConsecutiveMissDays() == 0) {
            return "0 天";
        }
        return stats.maxConsecutiveMissDays() + " 天";
    }

    private String formatInboxWorkLogs(List<InboundMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "无 IM 录入工作记录";
        }
        return messages.stream()
                .map(m -> "- " + m.getRawText())
                .collect(Collectors.joining("\n"));
    }

    private String formatInspirationDigest(List<InspirationNote> notes) {
        if (notes == null || notes.isEmpty()) {
            return "无灵感记录";
        }
        return notes.stream()
                .map(n -> "- " + n.getContent())
                .collect(Collectors.joining("\n"));
    }
}
