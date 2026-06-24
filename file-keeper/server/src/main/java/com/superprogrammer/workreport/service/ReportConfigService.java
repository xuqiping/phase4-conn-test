package com.superprogrammer.workreport.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.PageResult;
import com.superprogrammer.workreport.dto.ReportConfigDto;
import com.superprogrammer.workreport.dto.ReportPushTargetDto;
import com.superprogrammer.workreport.dto.ReportPushTargetRequest;
import com.superprogrammer.workreport.dto.SaveReportConfigRequest;
import com.superprogrammer.workreport.entity.ReportConfig;
import com.superprogrammer.workreport.entity.ReportPushTarget;
import com.superprogrammer.workreport.repository.ReportConfigRepository;
import com.superprogrammer.workreport.repository.ReportPushTargetRepository;
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
    private final ReportPushTargetRepository reportPushTargetRepository;
    private final ReportTemplateRepository reportTemplateRepository;
    private final CredentialEncryptor credentialEncryptor;

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

        syncPushTargets(userId, config.getId(), request.pushTargets());
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
        config.setUpdatedBy(userId);
        return reportConfigRepository.update(config);
    }

    public List<ReportPushTarget> getPushTargets(Long configId) {
        return reportPushTargetRepository.findByConfigId(configId);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        requireOwnedByUser(id, userId);
        reportConfigRepository.softDeleteById(id, userId);
        reportPushTargetRepository.softDeleteByConfigId(id, userId);
    }

    private void syncPushTargets(Long userId, Long configId, List<ReportPushTargetRequest> targets) {
        if (targets == null) {
            return;
        }

        List<ReportPushTarget> existing = reportPushTargetRepository.findByConfigId(configId);

        for (ReportPushTargetRequest targetRequest : targets) {
            if (targetRequest.id() == null) {
                ReportPushTarget target = new ReportPushTarget();
                target.setConfigId(configId);
                target.setPlatform(targetRequest.platform());
                target.setTargetType(targetRequest.targetType());
                target.setTargetId(targetRequest.targetId());
                target.setCredential(credentialEncryptor.encrypt(targetRequest.credential()));
                target.setCreatedBy(userId);
                target.setUpdatedBy(userId);
                reportPushTargetRepository.insert(target);
            } else {
                ReportPushTarget target = existing.stream()
                        .filter(t -> t.getId().equals(targetRequest.id()))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "推送目标不存在"));
                target.setPlatform(targetRequest.platform());
                target.setTargetType(targetRequest.targetType());
                target.setTargetId(targetRequest.targetId());
                if (targetRequest.credential() != null) {
                    target.setCredential(credentialEncryptor.encrypt(targetRequest.credential()));
                }
                target.setUpdatedBy(userId);
                reportPushTargetRepository.update(target);
            }
        }

        List<Long> keptIds = targets.stream()
                .map(ReportPushTargetRequest::id)
                .filter(id -> id != null)
                .toList();
        for (ReportPushTarget existingTarget : existing) {
            if (!keptIds.contains(existingTarget.getId())) {
                reportPushTargetRepository.softDeleteById(existingTarget.getId(), userId);
            }
        }
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
        List<ReportPushTargetDto> pushTargets = reportPushTargetRepository.findByConfigId(config.getId()).stream()
                .map(this::toTargetDto)
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
                pushTargets
        );
    }

    private ReportPushTargetDto toTargetDto(ReportPushTarget target) {
        return new ReportPushTargetDto(
                target.getId(),
                target.getPlatform(),
                target.getTargetType(),
                target.getTargetId(),
                target.getCredential() != null && !target.getCredential().isBlank()
        );
    }
}
