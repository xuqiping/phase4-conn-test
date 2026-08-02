package com.superprogrammer.workreport.service;

import com.superprogrammer.workreport.entity.PushCredential;
import com.superprogrammer.workreport.entity.PushDelivery;
import com.superprogrammer.workreport.entity.PushTarget;
import com.superprogrammer.workreport.entity.ReportConfig;
import com.superprogrammer.workreport.entity.ReportConfigPushTargetRef;
import com.superprogrammer.workreport.entity.WorkReport;
import com.superprogrammer.workreport.repository.PushCredentialRepository;
import com.superprogrammer.workreport.repository.PushDeliveryRepository;
import com.superprogrammer.workreport.repository.PushTargetRepository;
import com.superprogrammer.workreport.repository.ReportConfigPushTargetRefRepository;
import com.superprogrammer.workreport.repository.ReportConfigRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportPushServiceImpl implements ReportPushService {

    private final WorkReportService workReportService;
    private final ReportConfigRepository reportConfigRepository;
    private final ReportConfigPushTargetRefRepository reportConfigPushTargetRefRepository;
    private final PushTargetRepository pushTargetRepository;
    private final PushCredentialRepository pushCredentialRepository;
    private final CredentialEncryptor credentialEncryptor;
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

        List<PushTarget> targets = loadTargetsByConfigId(config.getId());
        log.info("[pushReport] reportId={} 找到 {} 个推送目标", reportId, targets.size());
        if (targets.isEmpty()) {
            log.info("报告无推送目标，跳过推送 reportId={}", reportId);
            return;
        }

        updateReportStatus(report, "PUSHING");

        boolean allSuccess = true;
        for (PushTarget target : targets) {
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
        PushTarget target = pushTargetRepository.findById(delivery.getTargetId()).orElse(null);
        if (target == null) {
            log.warn("推送目标不存在，跳过重试 deliveryId={}", deliveryId);
            return;
        }

        PushResult result = pushToTarget(report, target);
        pushDeliveryService.recordRetry(delivery.getId(), result.success(), result.response(),
                delivery.getTriedCount() == null ? 1 : delivery.getTriedCount() + 1);

        refreshReportStatus(report);
    }

    private List<PushTarget> loadTargetsByConfigId(Long configId) {
        List<Long> targetIds = reportConfigPushTargetRefRepository.findByConfigId(configId).stream()
                .map(ReportConfigPushTargetRef::getTargetId)
                .toList();
        if (targetIds.isEmpty()) {
            return List.of();
        }
        return pushTargetRepository.findByIds(targetIds);
    }

    private PushResult pushToTarget(WorkReport report, PushTarget target) {
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

        String decryptedCredential = decryptCredential(target.getCredentialId());
        PushPayload payload = new PushPayload(report.getTitle(), report.getContent());
        return pusher.push(payload, target, decryptedCredential);
    }

    private String decryptCredential(Long credentialId) {
        if (credentialId == null) {
            return null;
        }
        PushCredential credential = pushCredentialRepository.findById(credentialId).orElse(null);
        if (credential == null || credential.getCredentialEnc() == null) {
            return null;
        }
        return credentialEncryptor.decrypt(credential.getCredentialEnc());
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
