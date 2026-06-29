package com.superprogrammer.workreport.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.dto.CreateFuturePlanRequest;
import com.superprogrammer.workreport.dto.FuturePlanDto;
import com.superprogrammer.workreport.dto.UpdateFuturePlanRequest;
import com.superprogrammer.workreport.entity.FuturePlan;
import com.superprogrammer.workreport.repository.FuturePlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FuturePlanService {

    private final FuturePlanRepository futurePlanRepository;

    public List<FuturePlanDto> listByUser(Long userId) {
        return futurePlanRepository.findByUserId(userId).stream()
                .map(this::toDto)
                .toList();
    }

    public FuturePlanDto create(Long userId, CreateFuturePlanRequest request) {
        FuturePlan plan = new FuturePlan();
        plan.setUserId(userId);
        plan.setContent(request.content());
        plan.setDescription(request.description());
        plan.setScheduledAt(request.scheduledAt());
        plan.setTimezone(request.timezone() == null ? "Asia/Shanghai" : request.timezone());
        plan.setReminderEnabled(request.reminderEnabled() != null && request.reminderEnabled());
        plan.setReminderMinutesBefore(request.reminderMinutesBefore() == null ? 0 : request.reminderMinutesBefore());
        plan.setPushTargetId(request.pushTargetId());
        plan.setStatus("PENDING");
        plan.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        plan.setCreatedBy(userId);
        plan.setUpdatedBy(userId);
        FuturePlan saved = futurePlanRepository.insert(plan);
        return toDto(saved);
    }

    public FuturePlanDto update(Long userId, Long id, UpdateFuturePlanRequest request) {
        FuturePlan plan = requireOwnedByUser(id, userId);
        plan.setContent(request.content());
        plan.setDescription(request.description());
        plan.setScheduledAt(request.scheduledAt());
        plan.setTimezone(request.timezone() == null ? plan.getTimezone() : request.timezone());
        plan.setReminderEnabled(request.reminderEnabled() != null ? request.reminderEnabled() : plan.getReminderEnabled());
        plan.setReminderMinutesBefore(request.reminderMinutesBefore() == null ? plan.getReminderMinutesBefore() : request.reminderMinutesBefore());
        plan.setPushTargetId(request.pushTargetId());
        plan.setSortOrder(request.sortOrder() == null ? plan.getSortOrder() : request.sortOrder());
        plan.setUpdatedBy(userId);
        FuturePlan saved = futurePlanRepository.update(plan);
        return toDto(saved);
    }

    @Transactional
    public FuturePlanDto complete(Long userId, Long id) {
        FuturePlan plan = requireOwnedByUser(id, userId);
        futurePlanRepository.updateStatus(id, "COMPLETED", userId);
        plan.setStatus("COMPLETED");
        return toDto(plan);
    }

    @Transactional
    public FuturePlanDto cancel(Long userId, Long id) {
        FuturePlan plan = requireOwnedByUser(id, userId);
        futurePlanRepository.updateStatus(id, "CANCELLED", userId);
        plan.setStatus("CANCELLED");
        return toDto(plan);
    }

    public void delete(Long userId, Long id) {
        requireOwnedByUser(id, userId);
        futurePlanRepository.softDeleteById(id, userId);
    }

    private FuturePlan requireOwnedByUser(Long id, Long userId) {
        FuturePlan plan = futurePlanRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未来计划不存在"));
        if (!plan.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该未来计划");
        }
        return plan;
    }

    private FuturePlanDto toDto(FuturePlan plan) {
        return new FuturePlanDto(
                plan.getId(),
                plan.getContent(),
                plan.getDescription(),
                plan.getScheduledAt(),
                plan.getTimezone(),
                plan.getReminderEnabled(),
                plan.getReminderMinutesBefore(),
                plan.getPushTargetId(),
                plan.getLegacyPushPlatform(),
                plan.getLegacyPushTargetId(),
                plan.getLegacyPushCredential() != null && !plan.getLegacyPushCredential().isBlank(),
                plan.getStatus(),
                plan.getSortOrder(),
                plan.getCreatedAt(),
                plan.getUpdatedAt()
        );
    }
}
