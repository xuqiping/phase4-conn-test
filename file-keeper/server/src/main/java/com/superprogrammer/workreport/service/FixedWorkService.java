package com.superprogrammer.workreport.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.dto.CreateFixedWorkItemRequest;
import com.superprogrammer.workreport.dto.FixedWorkItemDto;
import com.superprogrammer.workreport.dto.UpdateFixedWorkItemRequest;
import com.superprogrammer.workreport.entity.CompletionSource;
import com.superprogrammer.workreport.entity.FixedWorkCompletion;
import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.repository.FixedWorkCompletionRepository;
import com.superprogrammer.workreport.repository.FixedWorkItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FixedWorkService {

    private final FixedWorkItemRepository itemRepository;
    private final FixedWorkCompletionRepository completionRepository;

    public List<FixedWorkItemDto> listByUserAndType(Long userId, String recurrenceType) {
        List<FixedWorkItem> items = itemRepository.findByUserIdAndType(userId, recurrenceType);
        LocalDate today = LocalDate.now(ZoneId.of(resolveTimeZone(items)));
        return enrichCompletions(items, userId, today);
    }

    public List<FixedWorkItemDto> listByUser(Long userId) {
        List<FixedWorkItem> items = itemRepository.findByUserId(userId);
        LocalDate today = LocalDate.now();
        return enrichCompletions(items, userId, today);
    }

    public List<FixedWorkItemDto> listByUserAndDate(Long userId, LocalDate date) {
        List<FixedWorkItem> items = itemRepository.findByUserId(userId);
        LocalDate targetDate = date == null ? LocalDate.now() : date;
        return enrichCompletions(items, userId, targetDate);
    }

    public FixedWorkItemDto create(Long userId, CreateFixedWorkItemRequest request) {
        FixedWorkItem item = new FixedWorkItem();
        item.setUserId(userId);
        item.setContent(request.content());
        item.setDescription(request.description());
        item.setRecurrenceType(request.recurrenceType());
        item.setReminderTime(request.reminderTime());
        item.setReminderDays(request.reminderDays());
        item.setTimezone(request.timezone() == null ? "Asia/Shanghai" : request.timezone());
        item.setReminderEnabled(request.reminderEnabled() != null && request.reminderEnabled());
        item.setPushTargetId(request.pushTargetId());
        item.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        item.setCreatedBy(userId);
        item.setUpdatedBy(userId);
        FixedWorkItem saved = itemRepository.insert(item);
        return toDto(saved, false);
    }

    public FixedWorkItemDto update(Long userId, Long id, UpdateFixedWorkItemRequest request) {
        FixedWorkItem item = requireOwnedByUser(id, userId);
        item.setContent(request.content());
        item.setDescription(request.description());
        item.setRecurrenceType(request.recurrenceType());
        item.setReminderTime(request.reminderTime());
        item.setReminderDays(request.reminderDays());
        item.setTimezone(request.timezone() == null ? item.getTimezone() : request.timezone());
        item.setReminderEnabled(request.reminderEnabled() != null ? request.reminderEnabled() : item.getReminderEnabled());
        item.setPushTargetId(request.pushTargetId());
        item.setSortOrder(request.sortOrder() == null ? item.getSortOrder() : request.sortOrder());
        item.setUpdatedBy(userId);
        FixedWorkItem saved = itemRepository.update(item);

        LocalDate today = LocalDate.now(ZoneId.of(saved.getTimezone()));
        Boolean completedToday = findCompletionStatus(saved.getId(), userId, today);
        return toDto(saved, Boolean.TRUE.equals(completedToday));
    }

    @Transactional
    public FixedWorkItemDto toggleComplete(Long userId, Long id) {
        return toggleComplete(userId, id, null);
    }

    @Transactional
    public FixedWorkItemDto toggleComplete(Long userId, Long id, LocalDate date) {
        FixedWorkItem item = requireOwnedByUser(id, userId);
        LocalDate targetDate = date == null ? LocalDate.now(ZoneId.of(item.getTimezone())) : date;
        FixedWorkCompletion existing = completionRepository.findByItemIdAndDate(id, targetDate)
                .orElse(null);

        boolean newCompleted = existing == null || !Boolean.TRUE.equals(existing.getCompleted());
        FixedWorkCompletion completion = new FixedWorkCompletion();
        completion.setItemId(id);
        completion.setUserId(userId);
        completion.setCompletionDate(targetDate);
        completion.setCompleted(newCompleted);
        completion.setCompletedAt(newCompleted ? OffsetDateTime.now() : null);
        completion.setCompletionSource(CompletionSource.DESKTOP.name());
        completion.setCreatedBy(userId);
        completion.setUpdatedBy(userId);
        completionRepository.upsert(completion);

        return toDto(item, newCompleted);
    }

    @Transactional
    public FixedWorkItemDto completeByName(Long userId, String taskName, LocalDate date, String source) {
        if (taskName == null || taskName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务名称不能为空");
        }
        LocalDate targetDate = date == null ? LocalDate.now() : date;

        List<FixedWorkItem> candidates = itemRepository.findByUserId(userId).stream()
                .filter(item -> item.getContent() != null && item.getContent().contains(taskName.trim()))
                .toList();

        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到匹配的固定工作：" + taskName);
        }
        if (candidates.size() > 1) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "匹配到多个固定工作，请提供更精确的名称：" + taskName);
        }

        FixedWorkItem item = candidates.get(0);
        FixedWorkCompletion completion = new FixedWorkCompletion();
        completion.setItemId(item.getId());
        completion.setUserId(userId);
        completion.setCompletionDate(targetDate);
        completion.setCompleted(true);
        completion.setCompletedAt(OffsetDateTime.now());
        completion.setCompletionSource(source == null ? CompletionSource.IM.name() : source);
        completion.setCreatedBy(userId);
        completion.setUpdatedBy(userId);
        completionRepository.upsert(completion);

        return toDto(item, true);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        requireOwnedByUser(id, userId);
        itemRepository.softDeleteById(id, userId);
        completionRepository.deleteByItemId(id);
    }

    public FixedWorkItem requireOwnedByUser(Long id, Long userId) {
        FixedWorkItem item = itemRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "固定工作不存在"));
        if (!item.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该固定工作");
        }
        return item;
    }

    private List<FixedWorkItemDto> enrichCompletions(List<FixedWorkItem> items, Long userId, LocalDate today) {
        if (items.isEmpty()) {
            return List.of();
        }
        Set<Long> itemIds = items.stream().map(FixedWorkItem::getId).collect(Collectors.toSet());
        List<FixedWorkCompletion> completions = completionRepository.findByUserIdAndDate(userId, today).stream()
                .filter(c -> itemIds.contains(c.getItemId()))
                .toList();
        Map<Long, Boolean> completionMap = completions.stream()
                .collect(Collectors.toMap(FixedWorkCompletion::getItemId, c -> Boolean.TRUE.equals(c.getCompleted())));
        return items.stream()
                .map(item -> toDto(item, Boolean.TRUE.equals(completionMap.get(item.getId()))))
                .toList();
    }

    private Boolean findCompletionStatus(Long itemId, Long userId, LocalDate today) {
        return completionRepository.findByItemIdAndDate(itemId, today)
                .map(FixedWorkCompletion::getCompleted)
                .orElse(false);
    }

    private String resolveTimeZone(List<FixedWorkItem> items) {
        return items.stream()
                .map(FixedWorkItem::getTimezone)
                .filter(t -> t != null && !t.isBlank())
                .findFirst()
                .orElse("Asia/Shanghai");
    }

    private FixedWorkItemDto toDto(FixedWorkItem item, boolean completedToday) {
        return new FixedWorkItemDto(
                item.getId(),
                item.getContent(),
                item.getDescription(),
                item.getRecurrenceType(),
                item.getReminderTime(),
                item.getReminderDays(),
                item.getTimezone(),
                item.getReminderEnabled(),
                item.getPushTargetId(),
                item.getLegacyPushPlatform(),
                item.getLegacyPushTargetId(),
                item.getLegacyPushCredential() != null && !item.getLegacyPushCredential().isBlank(),
                item.getSortOrder(),
                completedToday,
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }
}
