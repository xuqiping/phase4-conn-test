package com.superprogrammer.workreport.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.dto.CreateWorkLogRequest;
import com.superprogrammer.workreport.dto.UpdateWorkLogRequest;
import com.superprogrammer.workreport.dto.WorkLogDto;
import com.superprogrammer.workreport.entity.WorkLog;
import com.superprogrammer.workreport.repository.WorkLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkLogService {

    private final WorkLogRepository workLogRepository;

    public List<WorkLogDto> listByUserAndDate(Long userId, LocalDate date) {
        return workLogRepository.findByUserIdAndDate(userId, date).stream()
                .map(this::toDto)
                .toList();
    }

    public List<WorkLogDto> listByUserAndDateRange(Long userId, LocalDate startDate, LocalDate endDate) {
        return workLogRepository.findByUserIdAndDateRange(userId, startDate, endDate).stream()
                .map(this::toDto)
                .toList();
    }

    public WorkLogDto create(Long userId, CreateWorkLogRequest request) {
        WorkLog log = new WorkLog();
        log.setUserId(userId);
        log.setLogDate(request.logDate());
        log.setContent(request.content());
        log.setTags(request.tags());
        log.setSource(request.source() == null ? "MANUAL" : request.source());
        log.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        log.setCreatedBy(userId);
        log.setUpdatedBy(userId);
        WorkLog saved = workLogRepository.insert(log);
        return toDto(saved);
    }

    public WorkLogDto update(Long userId, Long id, UpdateWorkLogRequest request) {
        WorkLog log = requireOwnedByUser(id, userId);
        log.setContent(request.content());
        log.setTags(request.tags());
        log.setSource(request.source() == null ? log.getSource() : request.source());
        log.setSortOrder(request.sortOrder() == null ? log.getSortOrder() : request.sortOrder());
        log.setUpdatedBy(userId);
        WorkLog saved = workLogRepository.update(log);
        return toDto(saved);
    }

    public void delete(Long userId, Long id) {
        requireOwnedByUser(id, userId);
        workLogRepository.softDeleteById(id, userId);
    }

    private WorkLog requireOwnedByUser(Long id, Long userId) {
        WorkLog log = workLogRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "工作记录不存在"));
        if (!log.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该工作记录");
        }
        return log;
    }

    private WorkLogDto toDto(WorkLog log) {
        return new WorkLogDto(
                log.getId(),
                log.getLogDate(),
                log.getContent(),
                log.getTags(),
                log.getSource(),
                log.getSortOrder(),
                toLocalDateTime(log.getCreatedAt()),
                toLocalDateTime(log.getUpdatedAt())
        );
    }

    private LocalDateTime toLocalDateTime(java.time.OffsetDateTime value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
