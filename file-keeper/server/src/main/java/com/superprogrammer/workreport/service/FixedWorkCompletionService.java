package com.superprogrammer.workreport.service;

import com.superprogrammer.workreport.dto.FixedWorkCompletionStats;
import com.superprogrammer.workreport.dto.FixedWorkCompletionStats.ItemCompletionRate;
import com.superprogrammer.workreport.dto.FixedWorkCompletionStats.MissLogEntry;
import com.superprogrammer.workreport.entity.FixedWorkCompletion;
import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.repository.FixedWorkCompletionRepository;
import com.superprogrammer.workreport.repository.FixedWorkItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FixedWorkCompletionService {

    private final FixedWorkItemRepository itemRepository;
    private final FixedWorkCompletionRepository completionRepository;

    public FixedWorkCompletionStats calculateStats(Long userId, LocalDate startDate, LocalDate endDate) {
        List<FixedWorkItem> items = itemRepository.findByUserId(userId);
        List<LocalDate> expectedDates = expandDateRange(startDate, endDate);
        Set<String> completedKeys = completionRepository.findByUserIdAndDateRange(userId, startDate, endDate).stream()
                .filter(c -> Boolean.TRUE.equals(c.getCompleted()))
                .map(c -> c.getItemId() + ":" + c.getCompletionDate())
                .collect(Collectors.toSet());

        List<ItemCompletionRate> itemRates = new ArrayList<>();
        List<MissLogEntry> missLog = new ArrayList<>();
        int totalExpected = 0;
        int totalCompleted = 0;

        for (FixedWorkItem item : items) {
            List<LocalDate> itemExpectedDates = filterExpectedDates(item, expectedDates);
            int expected = itemExpectedDates.size();
            int completed = 0;
            for (LocalDate date : itemExpectedDates) {
                if (completedKeys.contains(item.getId() + ":" + date)) {
                    completed++;
                } else {
                    missLog.add(new MissLogEntry(date, item.getContent()));
                }
            }
            totalExpected += expected;
            totalCompleted += completed;
            if (expected > 0) {
                double rate = (double) completed / expected;
                itemRates.add(new ItemCompletionRate(item.getId(), item.getContent(), rate, expected, completed));
            }
        }

        double overallRate = totalExpected == 0 ? 0.0 : (double) totalCompleted / totalExpected;
        int maxConsecutiveMissDays = calculateMaxConsecutiveMissDays(items, userId, endDate, completedKeys);
        return new FixedWorkCompletionStats(overallRate, itemRates, missLog, maxConsecutiveMissDays);
    }

    public List<MissLogEntry> findMissLog(Long userId, LocalDate startDate, LocalDate endDate) {
        return calculateStats(userId, startDate, endDate).missLog();
    }

    public int findConsecutiveMissDays(Long userId, Long itemId, LocalDate endDate) {
        FixedWorkItem item = itemRepository.findById(itemId).orElse(null);
        if (item == null || !item.getUserId().equals(userId)) {
            return 0;
        }
        LocalDate startDate = endDate.minusDays(31);
        List<LocalDate> expectedDates = expandDateRange(startDate, endDate);
        Set<String> completedKeys = completionRepository.findByUserIdAndDateRangeAllStatuses(userId, startDate, endDate).stream()
                .filter(c -> Boolean.TRUE.equals(c.getCompleted()))
                .map(c -> c.getItemId() + ":" + c.getCompletionDate())
                .collect(Collectors.toSet());
        return countConsecutiveMissDays(item, expectedDates, endDate, completedKeys);
    }

    private int calculateMaxConsecutiveMissDays(List<FixedWorkItem> items, Long userId, LocalDate endDate, Set<String> completedKeys) {
        return items.stream()
                .mapToInt(item -> {
                    LocalDate startDate = endDate.minusDays(31);
                    List<LocalDate> expectedDates = expandDateRange(startDate, endDate);
                    return countConsecutiveMissDays(item, expectedDates, endDate, completedKeys);
                })
                .max()
                .orElse(0);
    }

    private int countConsecutiveMissDays(FixedWorkItem item, List<LocalDate> expectedDates, LocalDate endDate, Set<String> completedKeys) {
        List<LocalDate> itemExpectedDates = filterExpectedDates(item, expectedDates).stream()
                .filter(d -> !d.isAfter(endDate))
                .sorted((a, b) -> -a.compareTo(b))
                .toList();
        int count = 0;
        for (LocalDate date : itemExpectedDates) {
            if (completedKeys.contains(item.getId() + ":" + date)) {
                break;
            }
            count++;
        }
        return count;
    }

    private List<LocalDate> expandDateRange(LocalDate startDate, LocalDate endDate) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            dates.add(current);
            current = current.plusDays(1);
        }
        return dates;
    }

    private List<LocalDate> filterExpectedDates(FixedWorkItem item, List<LocalDate> dates) {
        String recurrenceType = item.getRecurrenceType();
        if ("WEEKLY".equals(recurrenceType)) {
            Set<Integer> days = parseReminderDays(item.getReminderDays());
            return dates.stream()
                    .filter(d -> days.contains(d.getDayOfWeek().getValue()))
                    .toList();
        }
        if ("MONTHLY".equals(recurrenceType)) {
            Set<Integer> days = parseReminderDays(item.getReminderDays());
            return dates.stream()
                    .filter(d -> days.contains(d.getDayOfMonth()))
                    .toList();
        }
        return dates;
    }

    private Set<Integer> parseReminderDays(String reminderDays) {
        if (reminderDays == null || reminderDays.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(reminderDays.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::parseIntOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private Integer parseIntOrNull(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
