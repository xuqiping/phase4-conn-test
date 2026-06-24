package com.superprogrammer.workreport.service;

import com.superprogrammer.workreport.entity.PushDelivery;
import com.superprogrammer.workreport.entity.ReportConfig;
import com.superprogrammer.workreport.entity.ReportPushTarget;
import com.superprogrammer.workreport.entity.WorkReport;
import com.superprogrammer.workreport.repository.PushDeliveryRepository;
import com.superprogrammer.workreport.repository.ReportConfigRepository;
import com.superprogrammer.workreport.repository.ReportPushTargetRepository;
import com.superprogrammer.workreport.service.push.Platform;
import com.superprogrammer.workreport.service.push.PushPayload;
import com.superprogrammer.workreport.service.push.PushResult;
import com.superprogrammer.workreport.service.push.PushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportPushServiceImpl implements ReportPushService {

    private final WorkReportService workReportService;
    private final ReportConfigRepository reportConfigRepository;
    private final ReportPushTargetRepository reportPushTargetRepository;
    private final PushDeliveryService pushDeliveryService;
    private final PushDeliveryRepository pushDeliveryRepository;
    private final List<PushService> pushServices;

    @Async
    @Override
    @Transactional
    public void pushReport(Long reportId) {
        log.info("[pushReport] 异步任务开始 reportId={}", reportId);
        WorkReport report = workReportService.getEntityById(reportId);
        ReportConfig config = reportConfigRepository.findById(report.getConfigId()).orElse(null);
        if (config == null) {
            log.warn("报告配置不存在，跳过推送 reportId={}", reportId);
            return;
        }

        List<ReportPushTarget> targets = reportPushTargetRepository.findByConfigId(config.getId());
        log.info("[pushReport] reportId={} 找到 {} 个推送目标", reportId, targets.size());
        if (targets.isEmpty()) {
            log.info("报告无推送目标，跳过推送 reportId={}", reportId);
            return;
        }

        updateReportStatus(report, "PUSHING");

        boolean allSuccess = true;
        for (ReportPushTarget target : targets) {
            log.info("[pushReport] 正在推送 reportId={} platform={} targetType={}", reportId, target.getPlatform(), target.getTargetType());
            PushResult result = pushToTarget(report, target);
            pushDeliveryService.record(reportId, target.getId(), result.success(), result.response(), 1);
            if (!result.success()) {
                allSuccess = false;
            }
        }

        updateReportStatus(report, allSuccess ? "PUSHED" : "FAILED");
    }

    @Async
    @Override
    @Transactional
    public void pushDelivery(Long deliveryId) {
        PushDelivery delivery = pushDeliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null) {
            log.warn("推送记录不存在，跳过重试 deliveryId={}", deliveryId);
            return;
        }

        if (delivery.getTriedCount() != null && delivery.getTriedCount() >= 3) {
            log.info("推送记录已达最大重试次数，跳过 deliveryId={}", deliveryId);
            return;
        }

        WorkReport report = workReportService.getEntityById(delivery.getReportId());
        ReportPushTarget target = reportPushTargetRepository.findById(delivery.getTargetId()).orElse(null);
        if (target == null) {
            log.warn("推送目标不存在，跳过重试 deliveryId={}", deliveryId);
            return;
        }

        PushResult result = pushToTarget(report, target);
        pushDeliveryService.recordRetry(delivery.getId(), result.success(), result.response(),
                delivery.getTriedCount() == null ? 1 : delivery.getTriedCount() + 1);

        refreshReportStatus(report);
    }

    private PushResult pushToTarget(WorkReport report, ReportPushTarget target) {
        Platform platform;
        try {
            platform = Platform.valueOf(target.getPlatform().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return new PushResult(false, "不支持的推送平台: " + target.getPlatform(), null);
        }

        PushService pusher = pushServices.stream()
                .filter(s -> s.supports(platform))
                .findFirst()
                .orElse(null);

        if (pusher == null) {
            return new PushResult(false, "未找到平台实现: " + platform, null);
        }

        PushPayload payload = new PushPayload(report.getTitle(), report.getContent());
        return pusher.push(payload, target);
    }

    private void updateReportStatus(WorkReport report, String status) {
        report.setStatus(status);
        report.setUpdatedBy(report.getUserId());
        workReportService.updateStatus(report);
    }

    private void refreshReportStatus(WorkReport report) {
        List<PushDelivery> deliveries = pushDeliveryRepository.findByReportId(report.getId());
        boolean allSuccess = deliveries.stream().allMatch(d -> "SUCCESS".equals(d.getStatus()));
        updateReportStatus(report, allSuccess ? "PUSHED" : "FAILED");
    }
}
