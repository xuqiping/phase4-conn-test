package com.superprogrammer.workreport.service;

import com.superprogrammer.workreport.entity.InspirationNote;
import com.superprogrammer.workreport.entity.PushCredential;
import com.superprogrammer.workreport.entity.PushTarget;
import com.superprogrammer.workreport.entity.ReportConfig;
import com.superprogrammer.workreport.entity.ReportConfigPushTargetRef;
import com.superprogrammer.workreport.repository.InspirationNoteRepository;
import com.superprogrammer.workreport.repository.PushCredentialRepository;
import com.superprogrammer.workreport.repository.PushTargetRepository;
import com.superprogrammer.workreport.repository.ReportConfigPushTargetRefRepository;
import com.superprogrammer.workreport.repository.ReportConfigRepository;
import com.superprogrammer.workreport.service.push.Platform;
import com.superprogrammer.workreport.service.push.PushPayload;
import com.superprogrammer.workreport.service.push.PushResult;
import com.superprogrammer.workreport.service.push.PushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class InspirationReviewService {

    private static final LocalTime DEFAULT_REVIEW_TIME = LocalTime.of(9, 0);
    private static final int DEFAULT_REVIEW_COUNT = 3;

    private final ReportConfigRepository reportConfigRepository;
    private final ReportConfigPushTargetRefRepository reportConfigPushTargetRefRepository;
    private final InspirationNoteRepository inspirationNoteRepository;
    private final PushTargetRepository pushTargetRepository;
    private final PushCredentialRepository pushCredentialRepository;
    private final CredentialEncryptor credentialEncryptor;
    private final List<PushService> pushServices;

    @Transactional
    public void scanAndPush(OffsetDateTime now) {
        List<ReportConfig> configs = reportConfigRepository.findByInspirationReviewEnabled(true);
        for (ReportConfig config : configs) {
            try {
                pushReviewForConfig(config, now);
            } catch (Exception e) {
                log.error("灵感回顾推送异常 configId={} userId={}", config.getId(), config.getUserId(), e);
            }
        }
    }

    private void pushReviewForConfig(ReportConfig config, OffsetDateTime now) {
        if (!shouldTriggerToday(config, now)) {
            return;
        }

        List<InspirationNote> notes = inspirationNoteRepository.findUnreviewedByUserId(config.getUserId(), DEFAULT_REVIEW_COUNT);
        if (notes.isEmpty()) {
            return;
        }

        List<PushTarget> targets = loadTargetsByConfigId(config.getId());
        if (targets.isEmpty()) {
            log.info("灵感回顾无推送目标，跳过 configId={}", config.getId());
            return;
        }

        String content = formatReviewContent(notes);
        PushPayload payload = new PushPayload("每日灵感回顾", content);

        boolean anySuccess = false;
        for (PushTarget target : targets) {
            PushResult result = pushToTarget(target, payload);
            if (result.success()) {
                anySuccess = true;
            }
            log.info("灵感回顾推送 configId={} targetId={} success={} message={}",
                    config.getId(), target.getId(), result.success(), result.message());
        }

        if (anySuccess) {
            markNotesReviewed(notes, config.getUserId());
        }
    }

    private boolean shouldTriggerToday(ReportConfig config, OffsetDateTime now) {
        ZoneId zone = ZoneId.of(config.getTimezone() == null ? "Asia/Shanghai" : config.getTimezone());
        ZonedDateTime nowInZone = now.atZoneSameInstant(zone);
        return nowInZone.getHour() == DEFAULT_REVIEW_TIME.getHour()
                && nowInZone.getMinute() == DEFAULT_REVIEW_TIME.getMinute();
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

    private String formatReviewContent(List<InspirationNote> notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("今天回顾一下这些灵感：\n\n");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");
        for (int i = 0; i < notes.size(); i++) {
            InspirationNote note = notes.get(i);
            sb.append(i + 1).append(". ").append(note.getContent()).append("\n");
            if (note.getCreatedAt() != null) {
                sb.append("   创建于 ").append(note.getCreatedAt().atZoneSameInstant(ZoneId.of("Asia/Shanghai")).format(formatter)).append("\n");
            }
            if (note.getTags() != null && !note.getTags().isEmpty()) {
                sb.append("   标签：").append(String.join(", ", note.getTags())).append("\n");
            }
            sb.append("\n");
        }
        sb.append("回复「完成 N」可标记第 N 条为已回顾。");
        return sb.toString();
    }

    private PushResult pushToTarget(PushTarget target, PushPayload payload) {
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

    private void markNotesReviewed(List<InspirationNote> notes, Long userId) {
        OffsetDateTime now = OffsetDateTime.now();
        for (InspirationNote note : notes) {
            note.setReviewedAt(now);
            note.setUpdatedBy(userId);
            inspirationNoteRepository.update(note);
        }
    }
}
