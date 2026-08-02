package com.superprogrammer.workreport.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.dto.CreateWorkLogRequest;
import com.superprogrammer.workreport.dto.FixedWorkItemDto;
import com.superprogrammer.workreport.dto.InboundMessageDto;
import com.superprogrammer.workreport.dto.InspirationNoteDto;
import com.superprogrammer.workreport.dto.WorkLogDto;
import com.superprogrammer.workreport.entity.CompletionSource;
import com.superprogrammer.workreport.entity.InboundMessage;
import com.superprogrammer.workreport.entity.InboundMessageStatus;
import com.superprogrammer.workreport.entity.PushTarget;
import com.superprogrammer.workreport.repository.InboundMessageRepository;
import com.superprogrammer.workreport.repository.PushTargetRepository;
import com.superprogrammer.workreport.service.NlpIntentService.IntentResult;
import com.superprogrammer.workreport.service.push.Platform;
import com.superprogrammer.workreport.service.push.PushPayload;
import com.superprogrammer.workreport.service.push.PushResult;
import com.superprogrammer.workreport.service.push.PushService;
import com.superprogrammer.workreport.service.webhook.WebhookParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class InboundMessageService {

    private static final double DEFAULT_AUTO_CONFIRM_THRESHOLD = 0.85;
    private static final double HIGH_FREQUENCY_AUTO_CONFIRM_THRESHOLD = 0.80;

    private static double autoConfirmThresholdFor(String intent) {
        return switch (intent) {
            case "complete_fixed_work", "add_work_log", "add_inspiration" -> HIGH_FREQUENCY_AUTO_CONFIRM_THRESHOLD;
            default -> DEFAULT_AUTO_CONFIRM_THRESHOLD;
        };
    }

    private final InboundMessageRepository inboundMessageRepository;
    private final PushTargetRepository pushTargetRepository;
    private final NlpIntentService nlpIntentService;
    private final FixedWorkService fixedWorkService;
    private final WorkLogService workLogService;
    private final InspirationNoteService inspirationNoteService;
    private final DateParseService dateParseService;
    private final PushCredentialService pushCredentialService;
    private final List<PushService> pushServices;
    private final WorkReportEventPushService eventPushService;
    private final ObjectMapper objectMapper;

    @Transactional
    public InboundMessageDto receive(String platform, WebhookParseResult parseResult) {
        Long userId = resolveUserId(platform, parseResult.chatId());

        InboundMessage message = new InboundMessage();
        message.setUserId(userId);
        message.setPlatform(platform);
        message.setPlatformMessageId(parseResult.platformMessageId());
        message.setSenderId(parseResult.senderId());
        message.setSenderName(parseResult.senderName());
        message.setRawText(parseResult.rawText());
        message.setCreatedBy(userId);
        message.setUpdatedBy(userId);

        IntentResult intent = nlpIntentService.parse(userId, parseResult.rawText());
        message.setIntent(intent.intent());
        message.setConfidence(BigDecimal.valueOf(intent.confidence()));
        message.setParsedPayload(toJson(intent.entities()));
        message.setStatus(InboundMessageStatus.PENDING.name());

        InboundMessage saved = inboundMessageRepository.insert(message);

        if (intent.confidence() >= autoConfirmThresholdFor(intent.intent())) {
            try {
                executeIntent(saved, intent);
                CompletableFuture.runAsync(() -> sendConfirmation(platform, parseResult.chatId(), saved.getUserId(), intent));
            } catch (Exception e) {
                log.error("[InboundMessageService] 自动执行意图失败 messageId={}", saved.getId(), e);
                saved.setStatus(InboundMessageStatus.FAILED.name());
                inboundMessageRepository.update(saved);
            }
        }

        eventPushService.push(userId, "inbound_message", toDto(saved));
        return toDto(saved);
    }

    @Transactional
    public InboundMessageDto confirm(Long userId, Long messageId, String action, Map<String, Object> correctedPayload) {
        InboundMessage message = inboundMessageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "消息不存在"));
        if (!message.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权处理该消息");
        }

        if ("IGNORE".equalsIgnoreCase(action)) {
            message.setStatus(InboundMessageStatus.IGNORED.name());
            message.setUpdatedBy(userId);
            return toDto(inboundMessageRepository.update(message));
        }

        if (!"CONFIRM".equalsIgnoreCase(action)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的确认动作: " + action);
        }

        Map<String, Object> payload = correctedPayload != null && !correctedPayload.isEmpty()
                ? correctedPayload
                : parseJson(message.getParsedPayload());

        String intent = message.getIntent();
        if (intent == null || "unknown".equals(intent)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "无法确认未知意图的消息");
        }

        executeIntent(message, new IntentResult(intent, 1.0, payload));
        return toDto(inboundMessageRepository.findById(messageId).orElseThrow());
    }

    public List<InboundMessageDto> listPending(Long userId, int limit) {
        return inboundMessageRepository.findPendingByUserId(userId, limit).stream()
                .map(this::toDto)
                .toList();
    }

    private void executeIntent(InboundMessage message, IntentResult intent) {
        switch (intent.intent()) {
            case "complete_fixed_work" -> {
                String taskName = (String) intent.entities().get("task_name");
                String dateExpr = (String) intent.entities().get("date");
                LocalDate date = LocalDate.parse(dateParseService.parseToIso(dateExpr));
                FixedWorkItemDto completed = fixedWorkService.completeByName(message.getUserId(), taskName, date, CompletionSource.IM.name());
                message.setTargetModule("fixed_work");
                message.setTargetId(completed.id());
                message.setStatus(InboundMessageStatus.CONFIRMED.name());
            }
            case "add_work_log" -> {
                String content = (String) intent.entities().get("content");
                String dateExpr = (String) intent.entities().get("date");
                LocalDate logDate = LocalDate.parse(dateParseService.parseToIso(dateExpr));
                WorkLogDto created = workLogService.create(message.getUserId(), new CreateWorkLogRequest(
                        logDate, content, null, "IM", 0
                ));
                message.setTargetModule("work_log");
                message.setTargetId(created.id());
                message.setStatus(InboundMessageStatus.CONFIRMED.name());
            }
            case "add_inspiration" -> {
                String content = (String) intent.entities().get("content");
                @SuppressWarnings("unchecked")
                List<String> tags = (List<String>) intent.entities().getOrDefault("tags", List.of());
                InspirationNoteDto created = inspirationNoteService.createFromIm(
                        message.getUserId(), content, tags, message.getPlatformMessageId()
                );
                message.setTargetModule("inspiration");
                message.setTargetId(created.id());
                message.setStatus(InboundMessageStatus.CONFIRMED.name());
            }
            case "help" -> {
                message.setTargetModule("help");
                message.setStatus(InboundMessageStatus.CONFIRMED.name());
            }
            default -> throw new BusinessException(ErrorCode.UNPROCESSABLE, "暂不支持的意图: " + intent.intent());
        }
        message.setUpdatedBy(message.getUserId());
        inboundMessageRepository.update(message);
    }

    private Long resolveUserId(String platform, String chatId) {
        List<PushTarget> targets = pushTargetRepository.findByPlatformAndTargetId(platform, chatId);
        if (targets.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该 IM 通道绑定的用户");
        }
        return targets.get(0).getUserId();
    }

    private void sendConfirmation(String platform, String chatId, Long userId, IntentResult intent) {
        try {
            List<PushTarget> targets = pushTargetRepository.findByPlatformAndTargetId(platform, chatId);
            if (targets.isEmpty()) {
                log.warn("[InboundMessageService] 未找到回复目标 platform={} chatId={}", platform, chatId);
                return;
            }
            PushTarget target = targets.get(0);
            String credential = pushCredentialService.getDecryptedCredential(userId, target.getCredentialId());
            Platform p = Platform.valueOf(platform);
            Optional<PushService> pusher = pushServices.stream().filter(s -> s.supports(p)).findFirst();
            if (pusher.isEmpty()) {
                log.warn("[InboundMessageService] 未找到推送器 platform={}", platform);
                return;
            }
            String text = buildConfirmationText(intent);
            PushResult result = pusher.get().push(new PushPayload(null, text), target, credential);
            log.info("[InboundMessageService] 确认回复发送结果 platform={} success={} message={}", platform, result.success(), result.message());
        } catch (Exception e) {
            log.error("[InboundMessageService] 发送确认回复失败 platform={} chatId={}", platform, chatId, e);
        }
    }

    private String buildConfirmationText(IntentResult intent) {
        return switch (intent.intent()) {
            case "complete_fixed_work" ->
                    "✅ 已记录完成：" + intent.entities().get("task_name");
            case "add_work_log" ->
                    "📝 已记录工作日志：" + intent.entities().get("content");
            case "add_inspiration" ->
                    "💡 已保存灵感：" + intent.entities().get("content");
            case "help" -> buildHelpMenuText();
            default -> "✅ 已处理";
        };
    }

    private String buildHelpMenuText() {
        return """
                可用指令：
                - 完成 [任务名]
                - 今天做了 [工作内容]
                - 灵感：[内容] #标签
                - /help
                """;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化 payload 失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return json == null ? Map.of() : objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("反序列化 payload 失败", e);
        }
    }

    private InboundMessageDto toDto(InboundMessage message) {
        return new InboundMessageDto(
                message.getId(),
                message.getUserId(),
                message.getPlatform(),
                message.getPlatformMessageId(),
                message.getSenderId(),
                message.getSenderName(),
                message.getRawText(),
                message.getIntent(),
                message.getConfidence(),
                parseJson(message.getParsedPayload()),
                message.getStatus(),
                message.getTargetModule(),
                message.getTargetId(),
                message.getCreatedAt() == null ? null : message.getCreatedAt().toLocalDateTime(),
                message.getUpdatedAt() == null ? null : message.getUpdatedAt().toLocalDateTime()
        );
    }
}
