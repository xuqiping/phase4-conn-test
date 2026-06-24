package com.superprogrammer.workreport.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.dto.ReportTemplateDto;
import com.superprogrammer.workreport.dto.SaveReportTemplateRequest;
import com.superprogrammer.workreport.entity.ReportTemplate;
import com.superprogrammer.workreport.repository.ReportTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportTemplateService {

    private final ReportTemplateRepository reportTemplateRepository;

    public List<ReportTemplateDto> listAvailable(Long userId) {
        return reportTemplateRepository.findByUserIdOrDefault(userId).stream()
                .map(this::toDto)
                .toList();
    }

    public List<ReportTemplateDto> listSystemTemplates() {
        return reportTemplateRepository.findByUserIdOrDefault(0L).stream()
                .filter(t -> t.getUserId() == null)
                .map(this::toDto)
                .toList();
    }

    public ReportTemplateDto createSystemTemplate(SaveReportTemplateRequest request) {
        ReportTemplate template = new ReportTemplate();
        template.setUserId(null);
        template.setName(request.name());
        template.setType(request.type());
        template.setContent(request.content());
        template.setIsDefault(request.isDefault() == null ? false : request.isDefault());
        template.setCreatedBy(0L);
        template.setUpdatedBy(0L);
        ReportTemplate saved = reportTemplateRepository.insert(template);
        return toDto(saved);
    }

    public ReportTemplateDto updateSystemTemplate(Long id, SaveReportTemplateRequest request) {
        ReportTemplate template = requireSystemTemplate(id);
        template.setName(request.name());
        template.setType(request.type());
        template.setContent(request.content());
        template.setIsDefault(request.isDefault() == null ? template.getIsDefault() : request.isDefault());
        template.setUpdatedBy(0L);
        ReportTemplate saved = reportTemplateRepository.update(template);
        return toDto(saved);
    }

    public void deleteSystemTemplate(Long id) {
        requireSystemTemplate(id);
        reportTemplateRepository.softDeleteById(id, 0L);
    }

    public ReportTemplateDto getById(Long templateId) {
        return reportTemplateRepository.findById(templateId)
                .map(this::toDto)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告模板不存在"));
    }

    private ReportTemplate requireSystemTemplate(Long id) {
        ReportTemplate template = reportTemplateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "报告模板不存在"));
        if (template.getUserId() != null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能管理系统模板");
        }
        return template;
    }

    private ReportTemplateDto toDto(ReportTemplate template) {
        return new ReportTemplateDto(
                template.getId(),
                template.getName(),
                template.getType(),
                template.getContent(),
                template.getIsDefault()
        );
    }
}
