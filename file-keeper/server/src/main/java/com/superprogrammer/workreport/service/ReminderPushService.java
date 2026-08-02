package com.superprogrammer.workreport.service;

import com.superprogrammer.workreport.entity.FixedWorkItem;
import com.superprogrammer.workreport.entity.FuturePlan;
import com.superprogrammer.workreport.entity.PushCredential;
import com.superprogrammer.workreport.entity.PushTarget;
import com.superprogrammer.workreport.entity.ReminderDelivery;
import com.superprogrammer.workreport.repository.PushCredentialRepository;
import com.superprogrammer.workreport.repository.PushTargetRepository;
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
    private final PushTargetRepository pushTargetRepository;
    private final PushCredentialRepository pushCredentialRepository;
    private final CredentialEncryptor credentialEncryptor;
    private final ReminderDeliveryRepository reminderDeliveryRepository;

    @Transactional
    public void pushFuturePlanReminder(FuturePlan plan) {
        if (!Boolean.TRUE.equals(plan.getReminderEnabled()) || plan.getPushTargetId() == null) {
            return;
        }
        doPush("FUTURE_PLAN", plan.getId(), plan.getUserId(), plan.getPushTargetId(), "未来计划提醒", plan.getContent());
    }

    @Transactional
    public void pushFixedWorkReminder(FixedWorkItem item) {
        if (!Boolean.TRUE.equals(item.getReminderEnabled()) || item.getPushTargetId() == null) {
            return;
        }
        doPush("FIXED_WORK", item.getId(), item.getUserId(), item.getPushTargetId(), "固定工作提醒", item.getContent());
    }

    @Transactional
    public void retryDelivery(ReminderDelivery delivery) {
        if (delivery.getTriedCount() != null && delivery.getTriedCount() >= 3) {
            return;
        }

        // 兼容旧提醒记录：旧记录只有 platform/targetId/credential，没有 pushTargetId，按原逻辑推送
        if (delivery.getPushTargetId() == null) {
            retryLegacyDelivery(delivery);
            return;
        }

        String title = "FIXED_WORK".equals(delivery.getSourceType()) ? "固定工作提醒重试" : "未来计划提醒重试";
        String content = "提醒内容未持久化，请检查原始" + ("FIXED_WORK".equals(delivery.getSourceType()) ? "固定工作" : "未来计划") + "配置";
        doPush(delivery.getSourceType(), delivery.getSourceId(), delivery.getUserId(), delivery.getPushTargetId(), title, content);

        delivery.setTriedCount(delivery.getTriedCount() == null ? 1 : delivery.getTriedCount() + 1);
        delivery.setUpdatedBy(delivery.getUserId());
        reminderDeliveryRepository.update(delivery);
    }

    private void retryLegacyDelivery(ReminderDelivery delivery) {
        String title = "FIXED_WORK".equals(delivery.getSourceType()) ? "固定工作提醒重试" : "未来计划提醒重试";
        String content = "提醒内容未持久化，请检查原始" + ("FIXED_WORK".equals(delivery.getSourceType()) ? "固定工作" : "未来计划") + "配置";
        doLegacyPush(delivery.getSourceType(), delivery.getSourceId(), delivery.getUserId(),
                delivery.getPlatform(), delivery.getTargetId(), delivery.getCredential(), title, content);

        delivery.setTriedCount(delivery.getTriedCount() == null ? 1 : delivery.getTriedCount() + 1);
        delivery.setUpdatedBy(delivery.getUserId());
        reminderDeliveryRepository.update(delivery);
    }

    private void doPush(String sourceType, Long sourceId, Long userId, Long pushTargetId, String title, String content) {
        PushTarget target = pushTargetRepository.findById(pushTargetId).orElse(null);
        if (target == null) {
            log.warn("提醒推送目标不存在，跳过 sourceType={} sourceId={} targetId={}", sourceType, sourceId, pushTargetId);
            recordDelivery(sourceType, sourceId, userId, null, null, null, pushTargetId, false, "推送目标不存在", 1);
            return;
        }

        String platformStr = target.getPlatform();
        String targetId = target.getTargetId();
        String decryptedCredential = decryptCredential(target.getCredentialId());

        Platform platform;
        try {
            platform = Platform.valueOf(platformStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            recordDelivery(sourceType, sourceId, userId, platformStr, targetId, decryptedCredential, pushTargetId, false, "不支持的推送平台: " + platformStr, 1);
            return;
        }

        PushService pusher = pushServices.stream()
                .filter(s -> s.supports(platform))
                .findFirst()
                .orElse(null);
        if (pusher == null) {
            recordDelivery(sourceType, sourceId, userId, platformStr, targetId, decryptedCredential, pushTargetId, false, "未找到平台实现: " + platformStr, 1);
            return;
        }

        PushPayload payload = new PushPayload(title, content);
        PushResult result = pusher.push(payload, target, decryptedCredential);
        recordDelivery(sourceType, sourceId, userId, platformStr, targetId, decryptedCredential, pushTargetId, result.success(), result.message(), 1);
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

    private void doLegacyPush(String sourceType, Long sourceId, Long userId, String platformStr, String targetId, String credential,
                              String title, String content) {
        if (platformStr == null || platformStr.isBlank() || targetId == null || targetId.isBlank()) {
            log.warn("提醒推送配置不完整，跳过 sourceType={} sourceId={}", sourceType, sourceId);
            recordDelivery(sourceType, sourceId, userId, platformStr, targetId, credential, null, false, "推送配置不完整", 1);
            return;
        }

        Platform platform;
        try {
            platform = Platform.valueOf(platformStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            recordDelivery(sourceType, sourceId, userId, platformStr, targetId, credential, null, false, "不支持的推送平台: " + platformStr, 1);
            return;
        }

        PushService pusher = pushServices.stream()
                .filter(s -> s.supports(platform))
                .findFirst()
                .orElse(null);
        if (pusher == null) {
            recordDelivery(sourceType, sourceId, userId, platformStr, targetId, credential, null, false, "未找到平台实现: " + platformStr, 1);
            return;
        }

        PushTarget target = new PushTarget();
        target.setPlatform(platformStr);
        target.setTargetType("GROUP");
        target.setTargetId(targetId);

        PushPayload payload = new PushPayload(title, content);
        PushResult result = pusher.push(payload, target, credential);
        recordDelivery(sourceType, sourceId, userId, platformStr, targetId, credential, null, result.success(), result.message(), 1);
    }

    private void recordDelivery(String sourceType, Long sourceId, Long userId, String platform, String targetId, String credential,
                                Long pushTargetId, boolean success, String message, int triedCount) {
        ReminderDelivery delivery = new ReminderDelivery();
        delivery.setSourceType(sourceType);
        delivery.setSourceId(sourceId);
        delivery.setUserId(userId);
        delivery.setPlatform(platform);
        delivery.setTargetId(targetId);
        delivery.setCredential(credential);
        delivery.setPushTargetId(pushTargetId);
        delivery.setStatus(success ? "SUCCESS" : "FAILED");
        delivery.setResponse(message);
        delivery.setTriedCount(triedCount);
        delivery.setCreatedBy(userId);
        delivery.setUpdatedBy(userId);
        reminderDeliveryRepository.insert(delivery);
    }
}
