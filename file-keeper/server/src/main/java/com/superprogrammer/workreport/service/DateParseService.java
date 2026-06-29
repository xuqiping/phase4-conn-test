package com.superprogrammer.workreport.service;

import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DateParseService {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final Map<String, Integer> CHINESE_WEEKDAY = Map.of(
            "一", DayOfWeek.MONDAY.getValue(),
            "二", DayOfWeek.TUESDAY.getValue(),
            "三", DayOfWeek.WEDNESDAY.getValue(),
            "四", DayOfWeek.THURSDAY.getValue(),
            "五", DayOfWeek.FRIDAY.getValue(),
            "六", DayOfWeek.SATURDAY.getValue(),
            "日", DayOfWeek.SUNDAY.getValue(),
            "天", DayOfWeek.SUNDAY.getValue()
    );

    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})");
    private static final Pattern MONTH_DAY_PATTERN = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");
    private static final Pattern LAST_WEEK_PATTERN = Pattern.compile("上(?:个)?周([一二三四五六日天])");
    private static final Pattern THIS_WEEK_PATTERN = Pattern.compile("(?:本(?:个)?周|周|星期|礼拜)([一二三四五六日天])");

    public LocalDate parse(String expression) {
        if (expression == null || expression.isBlank()) {
            return LocalDate.now();
        }
        String normalized = expression.trim().toLowerCase();

        switch (normalized) {
            case "今天", "今", "today" -> {
                return LocalDate.now();
            }
            case "昨天", "昨", "yesterday" -> {
                return LocalDate.now().minusDays(1);
            }
            case "前天" -> {
                return LocalDate.now().minusDays(2);
            }
            case "明天" -> {
                return LocalDate.now().plusDays(1);
            }
        }

        Matcher isoMatcher = ISO_DATE_PATTERN.matcher(normalized);
        if (isoMatcher.find()) {
            try {
                return LocalDate.of(
                        Integer.parseInt(isoMatcher.group(1)),
                        Integer.parseInt(isoMatcher.group(2)),
                        Integer.parseInt(isoMatcher.group(3))
                );
            } catch (DateTimeParseException | NumberFormatException ignored) {
            }
        }

        Matcher monthDayMatcher = MONTH_DAY_PATTERN.matcher(normalized);
        if (monthDayMatcher.find()) {
            try {
                int month = Integer.parseInt(monthDayMatcher.group(1));
                int day = Integer.parseInt(monthDayMatcher.group(2));
                LocalDate candidate = LocalDate.of(LocalDate.now().getYear(), month, day);
                if (candidate.isAfter(LocalDate.now().plusMonths(1))) {
                    candidate = candidate.minusYears(1);
                }
                return candidate;
            } catch (DateTimeParseException | NumberFormatException ignored) {
            }
        }

        Matcher lastWeekMatcher = LAST_WEEK_PATTERN.matcher(normalized);
        if (lastWeekMatcher.find()) {
            Integer dayOfWeek = CHINESE_WEEKDAY.get(lastWeekMatcher.group(1));
            if (dayOfWeek != null) {
                return LocalDate.now()
                        .minusWeeks(1)
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.of(dayOfWeek)));
            }
        }

        Matcher thisWeekMatcher = THIS_WEEK_PATTERN.matcher(normalized);
        if (thisWeekMatcher.find()) {
            Integer dayOfWeek = CHINESE_WEEKDAY.get(thisWeekMatcher.group(1));
            if (dayOfWeek != null) {
                LocalDate today = LocalDate.now();
                LocalDate candidate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(dayOfWeek)));
                if (candidate.isAfter(today)) {
                    candidate = candidate.minusWeeks(1);
                }
                return candidate;
            }
        }

        return LocalDate.now();
    }

    public String parseToIso(String expression) {
        return parse(expression).format(ISO_FORMATTER);
    }
}
