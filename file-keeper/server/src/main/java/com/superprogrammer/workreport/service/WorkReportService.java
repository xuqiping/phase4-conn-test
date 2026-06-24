package com.superprogrammer.workreport.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.PageResult;
import com.superprogrammer.workreport.dto.WorkReportDto;
import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.entity.ReportConfig;
import com.superprogrammer.workreport.entity.ReportTemplate;
import com.superprogrammer.workreport.entity.WorkLog;
import com.superprogrammer.workreport.entity.WorkReport;
import com.superprogrammer.workreport.repository.FixedWorkCompletionRepository;
import com.superprogrammer.workreport.repository.FixedWorkItemRepository;
import com.superprogrammer.workreport.repository.ReportConfigRepository;
import com.superprogrammer.workreport.repository.ReportTemplateRepository;
import com.superprogrammer.workreport.repository.WorkLogRepository;
import com.superprogrammer.workreport.repository.WorkReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WorkReportService {

    private final WorkReportRepository workReportRepository;
    private final ReportConfigRepository reportConfigRepository;
    private final ReportTemplateRepository reportTemplateRepository;
    private final WorkLogRepository workLogRepository;
    private final FixedWorkItemRepository fixedWorkItemRepository;
    private final FixedWorkCompletionRepository fixedWorkCompletionRepository;
    private final AiSummaryService aiSummaryService;
    private final ReportTemplateEngine templateEngine;

    public PageResult<WorkReportDto> pageByUser(Long userId, int page, int size) {
        long total = workReportRepository.countByUserId(userId);
        var records = workReportRepository.findByUserId(userId, page, size).stream()
                .map(this::toDto)
                .toList();
        return new PageResult<>(records, total, page, size);
    }

    public WorkReport getEntityById(Long id) {
        return workReportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告不存在"));
    }

    public WorkReport getEntityByUserAndId(Long userId, Long id) {
        return requireOwnedByUser(id, userId);
    }

    @Transactional
    public void updateStatus(WorkReport report) {
        WorkReport existing = workReportRepository.findById(report.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告不存在"));
        existing.setStatus(report.getStatus());
        existing.setUpdatedBy(report.getUpdatedBy());
        workReportRepository.update(existing);
    }

    public WorkReportDto getById(Long userId, Long id) {
        WorkReport report = requireOwnedByUser(id, userId);
        return toDto(report);
    }

    public void delete(Long userId, Long id) {
        requireOwnedByUser(id, userId);
        workReportRepository.softDeleteById(id, userId);
    }

    @Transactional
    public WorkReportDto generate(Long userId, Long configId) {
        ReportConfig config = reportConfigRepository.findById(configId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告配置不存在"));
        if (!config.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该报告配置");
        }

        ReportTemplate template = reportTemplateRepository.findById(config.getTemplateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告模板不存在"));

        DateRange logRange = calculateDateRange(config.getReportType());
        DateRange planRange = calculatePlanDateRange(config.getReportType());

        List<WorkLog> logs = workLogRepository.findByUserIdAndDateRange(userId, logRange.start(), logRange.end());
        List<FixedWorkItem> completedFixedWork = findCompletedFixedWork(userId, planRange.start(), planRange.end());

        String aiSummary = Boolean.TRUE.equals(config.getAiEnabled())
                ? aiSummaryService.summarize(logs, completedFixedWork, config.getReportType())
                : "";

        Map<String, Object> context = templateEngine.buildContext(aiSummary, logs, completedFixedWork, config.getReportType());
        String content = templateEngine.render(template.getContent(), context);

        String title = generateTitle(config.getReportType(), logRange);

        WorkReport report = new WorkReport();
        report.setUserId(userId);
        report.setConfigId(configId);
        report.setReportType(config.getReportType());
        report.setTitle(title);
        report.setContent(content);
        report.setGeneratedAt(OffsetDateTime.now());
        report.setStatus("GENERATED");
        report.setCreatedBy(userId);
        report.setUpdatedBy(userId);

        WorkReport saved = workReportRepository.insert(report);
        return toDto(saved);
    }

    private DateRange calculateDateRange(String reportType) {
        LocalDate today = LocalDate.now();
        if ("WEEKLY".equals(reportType)) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new DateRange(monday, today);
        }
        return new DateRange(today, today);
    }

    private DateRange calculatePlanDateRange(String reportType) {
        // 固定工作的「计划」维度不再指未来计划，而是报告周期内已完成的固定工作
        return calculateDateRange(reportType);
    }

    private List<FixedWorkItem> findCompletedFixedWork(Long userId, LocalDate startDate, LocalDate endDate) {
        var completions = fixedWorkCompletionRepository.findByUserIdAndDateRange(userId, startDate, endDate);
        if (completions.isEmpty()) {
            return List.of();
        }
        java.util.Set<Long> itemIds = completions.stream()
                .map(com.superprogrammer.workreport.entity.FixedWorkCompletion::getItemId)
                .collect(java.util.stream.Collectors.toSet());
        return fixedWorkItemRepository.findByUserId(userId).stream()
                .filter(item -> itemIds.contains(item.getId()))
                .toList();
    }

    private String generateTitle(String reportType, DateRange range) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        if ("WEEKLY".equals(reportType)) {
            return range.start().format(formatter) + " ~ " + range.end().format(formatter) + " 周报";
        }
        return range.start().format(formatter) + " 日报";
    }

    private WorkReport requireOwnedByUser(Long id, Long userId) {
        WorkReport report = workReportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告不存在"));
        if (!report.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该报告");
        }
        return report;
    }

    private WorkReportDto toDto(WorkReport report) {
        return new WorkReportDto(
                report.getId(),
                report.getReportType(),
                report.getTitle(),
                report.getContent(),
                toLocalDateTime(report.getGeneratedAt()),
                report.getStatus()
        );
    }

    private LocalDateTime toLocalDateTime(java.time.OffsetDateTime value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private record DateRange(LocalDate start, LocalDate end) {
    }
}
