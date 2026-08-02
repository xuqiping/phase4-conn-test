package com.superprogrammer.workreport.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.PageResult;
import com.superprogrammer.workreport.dto.PushTargetDto;
import com.superprogrammer.workreport.dto.ReportConfigDto;
import com.superprogrammer.workreport.dto.SaveReportConfigRequest;
import com.superprogrammer.workreport.entity.ReportConfig;
import com.superprogrammer.workreport.repository.ReportConfigPushTargetRefRepository;
import com.superprogrammer.workreport.repository.ReportConfigRepository;
import com.superprogrammer.workreport.repository.ReportTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportConfigService {

    private final ReportConfigRepository reportConfigRepository;
    private final ReportConfigPushTargetRefRepository reportConfigPushTargetRefRepository;
    private final ReportTemplateRepository reportTemplateRepository;
    private final PushTargetService pushTargetService;

    public List<ReportConfigDto> listByUser(Long userId) {
        return reportConfigRepository.findByUserId(userId).stream()
                .map(this::toDto)
                .toList();
    }

    public ReportConfigDto getById(Long userId, Long id) {
        ReportConfig config = requireOwnedByUser(id, userId);
        return toDto(config);
    }

    @Transactional
    public ReportConfigDto save(Long userId, SaveReportConfigRequest request) {
        validateCronExpression(request.cronExpression());
        requireTemplateExists(request.templateId());

        ReportConfig config;
        if (request.id() == null) {
            config = create(userId, request);
        } else {
            config = update(userId, request);
        }

        syncPushTargetRefs(userId, config.getId(), request.pushTargetIds());
        return toDto(config);
    }

    private ReportConfig create(Long userId, SaveReportConfigRequest request) {
        ReportConfig config = new ReportConfig();
        config.setUserId(userId);
        config.setName(request.name());
        config.setReportType(request.reportType());
        config.setTemplateId(request.templateId());
        config.setCronExpression(request.cronExpression());
        config.setTimezone(request.timezone() == null ? "Asia/Shanghai" : request.timezone());
        config.setEnabled(request.enabled() == null ? true : request.enabled());
        config.setAiEnabled(request.aiEnabled() == null ? true : request.aiEnabled());
        config.setAiConfigId(request.aiConfigId());
        config.setIncludeInspirationDigest(request.includeInspirationDigest() == null ? true : request.includeInspirationDigest());
        config.setInspirationReviewEnabled(request.inspirationReviewEnabled() != null && request.inspirationReviewEnabled());
        config.setCreatedBy(userId);
        config.setUpdatedBy(userId);
        return reportConfigRepository.insert(config);
    }

    private ReportConfig update(Long userId, SaveReportConfigRequest request) {
        ReportConfig config = requireOwnedByUser(request.id(), userId);
        config.setName(request.name());
        config.setReportType(request.reportType());
        config.setTemplateId(request.templateId());
        config.setCronExpression(request.cronExpression());
        config.setTimezone(request.timezone() == null ? config.getTimezone() : request.timezone());
        config.setEnabled(request.enabled() == null ? config.getEnabled() : request.enabled());
        config.setAiEnabled(request.aiEnabled() == null ? config.getAiEnabled() : request.aiEnabled());
        config.setAiConfigId(request.aiConfigId() == null ? config.getAiConfigId() : request.aiConfigId());
        config.setIncludeInspirationDigest(request.includeInspirationDigest() == null ? config.getIncludeInspirationDigest() : request.includeInspirationDigest());
        config.setInspirationReviewEnabled(request.inspirationReviewEnabled() == null ? config.getInspirationReviewEnabled() : request.inspirationReviewEnabled());
        config.setUpdatedBy(userId);
        return reportConfigRepository.update(config);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        requireOwnedByUser(id, userId);
        reportConfigRepository.softDeleteById(id, userId);
        reportConfigPushTargetRefRepository.softDeleteByConfigId(id, userId);
    }

    private void syncPushTargetRefs(Long userId, Long configId, List<Long> targetIds) {
        if (targetIds == null) {
            reportConfigPushTargetRefRepository.softDeleteByConfigId(configId, userId);
            return;
        }
        List<Long> ownedTargetIds = pushTargetService.listByIds(userId, targetIds).stream()
                .map(t -> t.getId())
                .toList();
        for (Long targetId : ownedTargetIds) {
            reportConfigPushTargetRefRepository.restoreOrInsert(configId, targetId, userId);
        }
        reportConfigPushTargetRefRepository.softDeleteByConfigIdAndTargetIdNotIn(configId, ownedTargetIds, userId);
    }

    private void validateCronExpression(String cronExpression) {
        String validated = normalizeCronExpression(cronExpression);
        if (!CronExpression.isValidExpression(validated)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "cron 表达式格式不正确");
        }
    }

    private String normalizeCronExpression(String cronExpression) {
        String trimmed = cronExpression.trim();
        int fieldCount = trimmed.split("\\s+").length;
        if (fieldCount == 5) {
            return "0 " + trimmed;
        }
        return trimmed;
    }

    private void requireTemplateExists(Long templateId) {
        if (!reportTemplateRepository.findById(templateId).isPresent()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "报告模板不存在");
        }
    }

    private ReportConfig requireOwnedByUser(Long id, Long userId) {
        ReportConfig config = reportConfigRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告配置不存在"));
        if (!config.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该报告配置");
        }
        return config;
    }

    private ReportConfigDto toDto(ReportConfig config) {
        String templateName = reportTemplateRepository.findById(config.getTemplateId())
                .map(t -> t.getName())
                .orElse("未知模板");
        List<PushTargetDto> pushTargets = pushTargetService.listByIds(
                config.getUserId(),
                reportConfigPushTargetRefRepository.findByConfigId(config.getId()).stream()
                        .map(ref -> ref.getTargetId())
                        .toList()
        ).stream()
                .map(t -> new PushTargetDto(t.getId(), t.getName(), t.getPlatform(), t.getTargetType(), t.getTargetId(), t.getCredentialId(), null))
                .toList();
        return new ReportConfigDto(
                config.getId(),
                config.getName(),
                config.getReportType(),
                config.getTemplateId(),
                templateName,
                config.getCronExpression(),
                config.getTimezone(),
                config.getEnabled(),
                config.getAiEnabled(),
                config.getAiConfigId(),
                config.getIncludeInspirationDigest(),
                config.getInspirationReviewEnabled(),
                pushTargets
        );
    }
}
