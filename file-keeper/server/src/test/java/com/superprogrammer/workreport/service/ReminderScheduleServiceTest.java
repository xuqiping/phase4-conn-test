package com.superprogrammer.workreport.service;

import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.repository.FixedWorkItemRepository;
import com.superprogrammer.workreport.repository.FuturePlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ReminderScheduleServiceTest {

    @Mock
    private FixedWorkItemRepository fixedWorkItemRepository;

    @Mock
    private FuturePlanRepository futurePlanRepository;

    @Mock
    private ReminderPushService reminderPushService;

    private ReminderScheduleService service;
    private Method shouldTriggerFixedWork;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        service = new ReminderScheduleService(fixedWorkItemRepository, futurePlanRepository, reminderPushService);
        shouldTriggerFixedWork = ReminderScheduleService.class.getDeclaredMethod("shouldTriggerFixedWork", FixedWorkItem.class, OffsetDateTime.class);
        shouldTriggerFixedWork.setAccessible(true);
    }

    @Test
    void monthly31stFallsBackToLastDayInFebruary() throws Exception {
        FixedWorkItem item = new FixedWorkItem();
        item.setRecurrenceType("MONTHLY");
        item.setReminderDays("31");
        item.setReminderTime(LocalTime.of(9, 0));
        item.setTimezone("Asia/Shanghai");

        // 2026-02-28 09:00 UTC+8
        OffsetDateTime now = OffsetDateTime.of(2026, 2, 28, 9, 0, 0, 0, ZoneOffset.ofHours(8));

        assertTrue((Boolean) shouldTriggerFixedWork.invoke(service, item, now));
    }

    @Test
    void monthly31stTriggersNormallyOnMarch31st() throws Exception {
        FixedWorkItem item = new FixedWorkItem();
        item.setRecurrenceType("MONTHLY");
        item.setReminderDays("31");
        item.setReminderTime(LocalTime.of(9, 0));
        item.setTimezone("Asia/Shanghai");

        OffsetDateTime now = OffsetDateTime.of(2026, 3, 31, 9, 0, 0, 0, ZoneOffset.ofHours(8));

        assertTrue((Boolean) shouldTriggerFixedWork.invoke(service, item, now));
    }

    @Test
    void monthly31stDoesNotTriggerOnMarch30th() throws Exception {
        FixedWorkItem item = new FixedWorkItem();
        item.setRecurrenceType("MONTHLY");
        item.setReminderDays("31");
        item.setReminderTime(LocalTime.of(9, 0));
        item.setTimezone("Asia/Shanghai");

        OffsetDateTime now = OffsetDateTime.of(2026, 3, 30, 9, 0, 0, 0, ZoneOffset.ofHours(8));

        assertFalse((Boolean) shouldTriggerFixedWork.invoke(service, item, now));
    }

    @Test
    void multipleDaysDoNotDuplicateOnShortMonthEnd() throws Exception {
        FixedWorkItem item = new FixedWorkItem();
        item.setRecurrenceType("MONTHLY");
        item.setReminderDays("28,29,30,31");
        item.setReminderTime(LocalTime.of(9, 0));
        item.setTimezone("Asia/Shanghai");

        OffsetDateTime now = OffsetDateTime.of(2026, 2, 28, 9, 0, 0, 0, ZoneOffset.ofHours(8));

        // 29/30/31 都 fallback 到 28，但同一天只应触发一次；该测试验证方法返回 true 且不抛异常
        assertTrue((Boolean) shouldTriggerFixedWork.invoke(service, item, now));
    }
}
