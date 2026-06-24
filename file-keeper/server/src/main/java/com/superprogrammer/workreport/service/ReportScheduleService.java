package com.superprogrammer.workreport.service;

import com.superprogrammer.workreport.dto.WorkReportDto;
import com.superprogrammer.workreport.entity.ReportConfig;
import com.superprogrammer.workreport.repository.ReportConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportScheduleService {

    private final ReportConfigRepository reportConfigRepository;
    private final WorkReportService workReportService;
    private final ReportPushService reportPushService;

    @Scheduled(cron = "0 * * * * ?")
    public void scheduleReports() {
        log.info("开始扫描报告配置...");
        List<ReportConfig> configs = reportConfigRepository.findEnabled();
        LocalDateTime now = LocalDateTime.now();

        for (ReportConfig config : configs) {
            try {
                if (shouldTrigger(config, now)) {
                    log.info("触发报告生成: configId={}", config.getId());
                    WorkReportDto report = workReportService.generate(config.getUserId(), config.getId());
                    reportPushService.pushReport(report.id());
                }
            } catch (Exception e) {
                log.error("报告生成/推送失败: configId={}", config.getId(), e);
            }
        }
    }

    public boolean shouldTrigger(ReportConfig config, LocalDateTime now) {
        ZoneId timezone = ZoneId.of(config.getTimezone() == null ? "Asia/Shanghai" : config.getTimezone());
        ZonedDateTime zonedNow = now.atZone(ZoneId.systemDefault()).withZoneSameInstant(timezone);
        LocalDateTime localNow = zonedNow.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES);

        CronExpression cron = CronExpression.parse(config.getCronExpression());
        LocalDateTime previousMinute = localNow.minusMinutes(1);
        LocalDateTime nextValid = cron.next(previousMinute);

        return nextValid != null && nextValid.equals(localNow);
    }
}
