package com.superprogrammer.workreport;

import com.superprogrammer.workreport.entity.ReportConfig;
import com.superprogrammer.workreport.service.ReportScheduleService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ReportScheduleServiceTest {

    @Test
    void shouldTriggerWhenCronMatchesCurrentMinute() {
        ReportConfig config = new ReportConfig();
        config.setCronExpression("0 0 9 * * ?");
        config.setTimezone("Asia/Shanghai");
        config.setEnabled(true);

        LocalDateTime now = LocalDateTime.of(2026, 6, 21, 9, 0, 30);

        ReportScheduleService service = new ReportScheduleService(null, null, null);
        assertTrue(service.shouldTrigger(config, now));
    }

    @Test
    void shouldNotTriggerWhenCronDoesNotMatch() {
        ReportConfig config = new ReportConfig();
        config.setCronExpression("0 0 9 * * ?");
        config.setTimezone("Asia/Shanghai");
        config.setEnabled(true);

        LocalDateTime now = LocalDateTime.of(2026, 6, 21, 10, 0, 0);

        ReportScheduleService service = new ReportScheduleService(null, null, null);
        assertFalse(service.shouldTrigger(config, now));
    }

    @Test
    void shouldRespectTimezone() {
        ReportConfig config = new ReportConfig();
        config.setCronExpression("0 0 9 * * ?");
        config.setTimezone("America/New_York");
        config.setEnabled(true);

        // 系统时区为 Asia/Shanghai 时，纽约 09:00 对应上海 21:00
        LocalDateTime shanghaiNow = LocalDateTime.of(2026, 6, 21, 21, 0, 0);

        ReportScheduleService service = new ReportScheduleService(null, null, null);
        assertTrue(service.shouldTrigger(config, shanghaiNow));
    }
}
