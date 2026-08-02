package com.superprogrammer.workreport.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.dto.CreateWorkPlanRequest;
import com.superprogrammer.workreport.dto.UpdateWorkPlanRequest;
import com.superprogrammer.workreport.dto.WorkPlanDto;
import com.superprogrammer.workreport.entity.WorkPlan;
import com.superprogrammer.workreport.repository.WorkPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkPlanService {

    private final WorkPlanRepository workPlanRepository;

    public List<WorkPlanDto> listByUserAndDate(Long userId, LocalDate date) {
        return workPlanRepository.findByUserIdAndDate(userId, date).stream()
                .map(this::toDto)
                .toList();
    }

    public List<WorkPlanDto> listByUserAndDateRange(Long userId, LocalDate startDate, LocalDate endDate) {
        return workPlanRepository.findByUserIdAndDateRange(userId, startDate, endDate).stream()
                .map(this::toDto)
                .toList();
    }

    public WorkPlanDto create(Long userId, CreateWorkPlanRequest request) {
        WorkPlan plan = new WorkPlan();
        plan.setUserId(userId);
        plan.setPlanDate(request.planDate());
        plan.setContent(request.content());
        plan.setDescription(request.description());
        plan.setPriority(request.priority() == null ? "MEDIUM" : request.priority());
        plan.setPlannedStartTime(request.plannedStartTime());
        plan.setPlannedEndTime(request.plannedEndTime());
        plan.setCompleted(false);
        plan.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        plan.setCreatedBy(userId);
        plan.setUpdatedBy(userId);
        WorkPlan saved = workPlanRepository.insert(plan);
        return toDto(saved);
    }

    public WorkPlanDto update(Long userId, Long id, UpdateWorkPlanRequest request) {
        WorkPlan plan = requireOwnedByUser(id, userId);
        plan.setContent(request.content());
        plan.setDescription(request.description());
        plan.setPriority(request.priority() == null ? plan.getPriority() : request.priority());
        plan.setPlannedStartTime(request.plannedStartTime());
        plan.setPlannedEndTime(request.plannedEndTime());
        plan.setCompleted(request.completed() == null ? plan.getCompleted() : request.completed());
        plan.setSortOrder(request.sortOrder() == null ? plan.getSortOrder() : request.sortOrder());
        plan.setUpdatedBy(userId);
        WorkPlan saved = workPlanRepository.update(plan);
        return toDto(saved);
    }

    public WorkPlanDto toggleComplete(Long userId, Long id) {
        WorkPlan plan = requireOwnedByUser(id, userId);
        workPlanRepository.markCompleted(id, !plan.getCompleted(), userId);
        return workPlanRepository.findById(id).map(this::toDto).orElseThrow();
    }

    public void delete(Long userId, Long id) {
        requireOwnedByUser(id, userId);
        workPlanRepository.softDeleteById(id, userId);
    }

    private WorkPlan requireOwnedByUser(Long id, Long userId) {
        WorkPlan plan = workPlanRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "每日安排不存在"));
        if (!plan.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该每日安排");
        }
        return plan;
    }

    private WorkPlanDto toDto(WorkPlan plan) {
        return new WorkPlanDto(
                plan.getId(),
                plan.getPlanDate(),
                plan.getContent(),
                plan.getDescription(),
                plan.getPriority(),
                plan.getPlannedStartTime(),
                plan.getPlannedEndTime(),
                plan.getCompleted(),
                plan.getSortOrder(),
                plan.getCreatedAt(),
                plan.getUpdatedAt()
        );
    }
}
