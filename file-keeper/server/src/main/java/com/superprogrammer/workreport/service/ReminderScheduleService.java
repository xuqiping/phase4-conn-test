package com.superprogrammer.workreport.service;

import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.entity.FuturePlan;
import com.superprogrammer.workreport.repository.FixedWorkItemRepository;
import com.superprogrammer.workreport.repository.FuturePlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReminderScheduleService {

    private final FixedWorkItemRepository fixedWorkItemRepository;
    private final FuturePlanRepository futurePlanRepository;
    private final ReminderPushService reminderPushService;

    @Scheduled(cron = "0 * * * * ?")
    @Transactional
    public void scanReminders() {
        scanFuturePlans();
        scanFixedWork();
    }

    private void scanFuturePlans() {
        OffsetDateTime now = OffsetDateTime.now();
        List<FuturePlan> plans = futurePlanRepository.findPendingReminders(now.plusMinutes(1));
        for (FuturePlan plan : plans) {
            try {
                if (shouldTriggerFuturePlan(plan, now)) {
                    reminderPushService.pushFuturePlanReminder(plan);
                    futurePlanRepository.updateStatus(plan.getId(), "REMINDED", plan.getUserId());
                }
            } catch (Exception e) {
                log.error("未来计划提醒处理异常 planId={}", plan.getId(), e);
            }
        }
    }

    private boolean shouldTriggerFuturePlan(FuturePlan plan, OffsetDateTime now) {
        if (!Boolean.TRUE.equals(plan.getReminderEnabled()) || !"PENDING".equals(plan.getStatus())) {
            return false;
        }
        ZoneId zone = ZoneId.of(plan.getTimezone() == null ? "Asia/Shanghai" : plan.getTimezone());
        ZonedDateTime scheduled = plan.getScheduledAt().atZoneSameInstant(zone);
        int minutesBefore = plan.getReminderMinutesBefore() == null ? 0 : plan.getReminderMinutesBefore();
        ZonedDateTime triggerTime = scheduled.minusMinutes(minutesBefore);
        ZonedDateTime nowInZone = now.atZoneSameInstant(zone);
        // 触发窗口：当前分钟等于或晚于触发时间，且未超过触发时间 1 分钟（避免重复）
        return !nowInZone.isBefore(triggerTime) && nowInZone.isBefore(triggerTime.plusMinutes(1));
    }

    private void scanFixedWork() {
        OffsetDateTime now = OffsetDateTime.now();
        List<FixedWorkItem> items = fixedWorkItemRepository.findEnabledReminders();
        for (FixedWorkItem item : items) {
            try {
                if (shouldTriggerFixedWork(item, now)) {
                    reminderPushService.pushFixedWorkReminder(item);
                }
            } catch (Exception e) {
                log.error("固定工作提醒处理异常 itemId={}", item.getId(), e);
            }
        }
    }

    private boolean shouldTriggerFixedWork(FixedWorkItem item, OffsetDateTime now) {
        ZoneId zone = ZoneId.of(item.getTimezone() == null ? "Asia/Shanghai" : item.getTimezone());
        ZonedDateTime nowInZone = now.atZoneSameInstant(zone);

        if (item.getReminderTime() == null) {
            return false;
        }
        int currentHour = nowInZone.getHour();
        int currentMinute = nowInZone.getMinute();
        int reminderHour = item.getReminderTime().getHour();
        int reminderMinute = item.getReminderTime().getMinute();
        if (currentHour != reminderHour || currentMinute != reminderMinute) {
            return false;
        }

        String recurrenceType = item.getRecurrenceType();
        if ("DAILY".equals(recurrenceType)) {
            return true;
        }

        String reminderDays = item.getReminderDays();
        if (reminderDays == null || reminderDays.isBlank()) {
            return false;
        }
        Set<Integer> days = Arrays.stream(reminderDays.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .collect(Collectors.toSet());

        if ("WEEKLY".equals(recurrenceType)) {
            int dayOfWeek = nowInZone.getDayOfWeek().getValue(); // 1 = Monday
            return days.contains(dayOfWeek);
        }

        if ("MONTHLY".equals(recurrenceType)) {
            int dayOfMonth = nowInZone.getDayOfMonth();
            int lastDayOfMonth = nowInZone.toLocalDate().lengthOfMonth();
            // 如果选了 31 号但当月没有 31 天，则自动落在当月最后一天
            return days.stream()
                    .map(d -> Math.min(d, lastDayOfMonth))
                    .anyMatch(effectiveDay -> effectiveDay == dayOfMonth);
        }

        return false;
    }
}
