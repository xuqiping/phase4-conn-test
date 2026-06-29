package com.superprogrammer.workreport;

import com.superprogrammer.workreport.dto.FixedWorkCompletionStats;
import com.superprogrammer.workreport.entity.FixedWorkCompletion;
import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.repository.FixedWorkCompletionRepository;
import com.superprogrammer.workreport.repository.FixedWorkItemRepository;
import com.superprogrammer.workreport.service.FixedWorkCompletionService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FixedWorkCompletionServiceTest {

    private final FixedWorkItemRepository itemRepository = mock(FixedWorkItemRepository.class);
    private final FixedWorkCompletionRepository completionRepository = mock(FixedWorkCompletionRepository.class);
    private final FixedWorkCompletionService service = new FixedWorkCompletionService(itemRepository, completionRepository);

    @Test
    void dailyItemCompletionRateIsFiftyPercent() {
        FixedWorkItem item = dailyItem(1L, "晨会");
        when(itemRepository.findByUserId(1L)).thenReturn(List.of(item));
        when(completionRepository.findByUserIdAndDateRange(1L, LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 23)))
                .thenReturn(List.of(completion(1L, LocalDate.of(2026, 6, 22), true)));

        FixedWorkCompletionStats stats = service.calculateStats(1L, LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 23));

        assertEquals(0.5, stats.overallCompletionRate(), 0.001);
        assertEquals(1, stats.itemRates().get(0).completedCount());
        assertEquals(2, stats.itemRates().get(0).expectedCount());
        assertEquals(1, stats.missLog().size());
    }

    @Test
    void weeklyItemOnlyCountsConfiguredDays() {
        FixedWorkItem item = weeklyItem(2L, "周报", "1,5");
        when(itemRepository.findByUserId(1L)).thenReturn(List.of(item));
        when(completionRepository.findByUserIdAndDateRange(1L, LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 28)))
                .thenReturn(List.of(completion(2L, LocalDate.of(2026, 6, 22), true)));

        FixedWorkCompletionStats stats = service.calculateStats(1L, LocalDate.of(2026, 6, 22), LocalDate.of(2026, 6, 28));

        assertEquals(2, stats.itemRates().get(0).expectedCount());
        assertEquals(1, stats.itemRates().get(0).completedCount());
        assertEquals(1, stats.missLog().size());
    }

    @Test
    void consecutiveMissDaysCalculatedBackwards() {
        FixedWorkItem item = dailyItem(3L, "日报");
        when(itemRepository.findById(3L)).thenReturn(Optional.of(item));
        LocalDate endDate = LocalDate.of(2026, 6, 24);
        when(completionRepository.findByUserIdAndDateRangeAllStatuses(1L, endDate.minusDays(31), endDate))
                .thenReturn(List.of(
                        completion(3L, endDate, false),
                        completion(3L, endDate.minusDays(1), false),
                        completion(3L, endDate.minusDays(2), true)
                ));

        int days = service.findConsecutiveMissDays(1L, 3L, endDate);

        assertEquals(2, days);
    }

    private FixedWorkItem dailyItem(Long id, String content) {
        FixedWorkItem item = new FixedWorkItem();
        item.setId(id);
        item.setUserId(1L);
        item.setContent(content);
        item.setRecurrenceType("DAILY");
        item.setReminderTime(LocalTime.of(9, 0));
        return item;
    }

    private FixedWorkItem weeklyItem(Long id, String content, String reminderDays) {
        FixedWorkItem item = new FixedWorkItem();
        item.setId(id);
        item.setUserId(1L);
        item.setContent(content);
        item.setRecurrenceType("WEEKLY");
        item.setReminderDays(reminderDays);
        item.setReminderTime(LocalTime.of(9, 0));
        return item;
    }

    private FixedWorkCompletion completion(Long itemId, LocalDate date, boolean completed) {
        FixedWorkCompletion c = new FixedWorkCompletion();
        c.setItemId(itemId);
        c.setUserId(1L);
        c.setCompletionDate(date);
        c.setCompleted(completed);
        c.setCompletedAt(completed ? OffsetDateTime.now() : null);
        return c;
    }
}
