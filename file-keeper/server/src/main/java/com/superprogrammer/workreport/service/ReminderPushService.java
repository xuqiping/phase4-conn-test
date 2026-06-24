package com.superprogrammer.workreport.service;

import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.entity.FuturePlan;
import com.superprogrammer.workreport.entity.ReminderDelivery;
import com.superprogrammer.workreport.entity.ReportPushTarget;
import com.superprogrammer.workreport.repository.ReminderDeliveryRepository;
import com.superprogrammer.workreport.service.push.Platform;
import com.superprogrammer.workreport.service.push.PushPayload;
import com.superprogrammer.workreport.service.push.PushResult;
import com.superprogrammer.workreport.service.push.PushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReminderPushService {

    private final List<PushService> pushServices;
    private final CredentialEncryptor credentialEncryptor;
    private final ReminderDeliveryRepository reminderDeliveryRepository;

    @Transactional
    public void pushFuturePlanReminder(FuturePlan plan) {
        if (!Boolean.TRUE.equals(plan.getReminderEnabled())) {
            return;
        }
        String title = "未来计划提醒";
        String content = plan.getContent();
        doPush("FUTURE_PLAN", plan.getId(), plan.getUserId(), plan.getPushPlatform(), plan.getPushTargetId(), plan.getPushCredential(), title, content);
        // 更新计划状态为已提醒，无论推送是否成功都标记，避免重复触发
        // 推送失败会由重试服务处理
    }

    @Transactional
    public void pushFixedWorkReminder(FixedWorkItem item) {
        if (!Boolean.TRUE.equals(item.getReminderEnabled())) {
            return;
        }
        String title = "固定工作提醒";
        String content = item.getContent();
        doPush("FIXED_WORK", item.getId(), item.getUserId(), item.getPushPlatform(), item.getPushTargetId(), item.getPushCredential(), title, content);
    }

    @Transactional
    public void retryDelivery(ReminderDelivery delivery) {
        if (delivery.getTriedCount() != null && delivery.getTriedCount() >= 3) {
            return;
        }

        String title = "FIXED_WORK".equals(delivery.getSourceType()) ? "固定工作提醒重试" : "未来计划提醒重试";
        String content = "提醒内容未持久化，请检查原始" + ("FIXED_WORK".equals(delivery.getSourceType()) ? "固定工作" : "未来计划") + "配置";

        doPush(delivery.getSourceType(), delivery.getSourceId(), delivery.getUserId(),
                delivery.getPlatform(), delivery.getTargetId(), delivery.getCredential(),
                title, content);

        delivery.setTriedCount(delivery.getTriedCount() == null ? 1 : delivery.getTriedCount() + 1);
        delivery.setUpdatedBy(delivery.getUserId());
        reminderDeliveryRepository.update(delivery);
    }

    private void doPush(String sourceType, Long sourceId, Long userId, String platformStr, String targetId, String credential,
                        String title, String content) {
        if (platformStr == null || platformStr.isBlank() || targetId == null || targetId.isBlank()) {
            log.warn("提醒推送配置不完整，跳过 sourceType={} sourceId={}", sourceType, sourceId);
            recordDelivery(sourceType, sourceId, userId, platformStr, targetId, credential, false, "推送配置不完整", 1);
            return;
        }

        Platform platform;
        try {
            platform = Platform.valueOf(platformStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            recordDelivery(sourceType, sourceId, userId, platformStr, targetId, credential, false, "不支持的推送平台: " + platformStr, 1);
            return;
        }

        PushService pusher = pushServices.stream()
                .filter(s -> s.supports(platform))
                .findFirst()
                .orElse(null);
        if (pusher == null) {
            recordDelivery(sourceType, sourceId, userId, platformStr, targetId, credential, false, "未找到平台实现: " + platformStr, 1);
            return;
        }

        ReportPushTarget target = new ReportPushTarget();
        target.setPlatform(platformStr);
        target.setTargetType("GROUP");
        target.setTargetId(targetId);
        target.setCredential(credential);

        PushPayload payload = new PushPayload(title, content);
        PushResult result = pusher.push(payload, target);
        recordDelivery(sourceType, sourceId, userId, platformStr, targetId, credential, result.success(), result.message(), 1);
    }

    private void recordDelivery(String sourceType, Long sourceId, Long userId, String platform, String targetId, String credential,
                                boolean success, String message, int triedCount) {
        ReminderDelivery delivery = new ReminderDelivery();
        delivery.setSourceType(sourceType);
        delivery.setSourceId(sourceId);
        delivery.setUserId(userId);
        delivery.setPlatform(platform);
        delivery.setTargetId(targetId);
        delivery.setCredential(credential);
        delivery.setStatus(success ? "SUCCESS" : "FAILED");
        delivery.setResponse(message);
        delivery.setTriedCount(triedCount);
        delivery.setCreatedBy(userId);
        delivery.setUpdatedBy(userId);
        reminderDeliveryRepository.insert(delivery);
    }
}
