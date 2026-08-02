package com.superprogrammer.workreport.dto;

import java.time.LocalDate;
import java.util.List;

public record FixedWorkCompletionStats(
        double overallCompletionRate,
        List<ItemCompletionRate> itemRates,
        List<MissLogEntry> missLog,
        int maxConsecutiveMissDays
) {

    public record ItemCompletionRate(
            Long itemId,
            String content,
            double rate,
            int expectedCount,
            int completedCount
    ) {
    }

    public record MissLogEntry(
            LocalDate date,
            String itemContent
    ) {
    }
}
