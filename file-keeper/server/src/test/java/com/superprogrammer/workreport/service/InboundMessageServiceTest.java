package com.superprogrammer.workreport.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.workreport.dto.InboundMessageDto;
import com.superprogrammer.workreport.entity.InboundMessage;
import com.superprogrammer.workreport.entity.PushTarget;
import com.superprogrammer.workreport.repository.InboundMessageRepository;
import com.superprogrammer.workreport.repository.PushTargetRepository;
import com.superprogrammer.workreport.service.push.Platform;
import com.superprogrammer.workreport.service.push.PushPayload;
import com.superprogrammer.workreport.service.push.PushResult;
import com.superprogrammer.workreport.service.push.PushService;
import com.superprogrammer.workreport.service.webhook.WebhookParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InboundMessageServiceTest {

    @Mock
    private InboundMessageRepository inboundMessageRepository;

    @Mock
    private PushTargetRepository pushTargetRepository;

    @Mock
    private NlpIntentService nlpIntentService;

    @Mock
    private FixedWorkService fixedWorkService;

    @Mock
    private WorkLogService workLogService;

    @Mock
    private InspirationNoteService inspirationNoteService;

    @Mock
    private DateParseService dateParseService;

    @Mock
    private PushCredentialService pushCredentialService;

    @Mock
    private PushService pushService;

    @Mock
    private WorkReportEventPushService eventPushService;

    private InboundMessageService inboundMessageService;

    @BeforeEach
    void setUp() {
        inboundMessageService = new InboundMessageService(
                inboundMessageRepository,
                pushTargetRepository,
                nlpIntentService,
                fixedWorkService,
                workLogService,
                inspirationNoteService,
                dateParseService,
                pushCredentialService,
                List.of(pushService),
                eventPushService,
                new ObjectMapper()
        );
    }

    @Test
    void receiveHelpIntentRepliesMenuAndDoesNotCreateBusinessRecords() {
        PushTarget target = new PushTarget();
        target.setId(1L);
        target.setUserId(100L);
        target.setPlatform("FEISHU");
        target.setTargetId("chat123");
        target.setCredentialId(2L);

        when(pushTargetRepository.findByPlatformAndTargetId("FEISHU", "chat123")).thenReturn(List.of(target));
        when(nlpIntentService.parse(100L, "/help")).thenReturn(new NlpIntentService.IntentResult("help", 0.95, java.util.Map.of()));

        InboundMessage saved = new InboundMessage();
        saved.setId(1L);
        saved.setUserId(100L);
        saved.setPlatform("FEISHU");
        saved.setRawText("/help");
        saved.setIntent("help");
        saved.setStatus("PENDING");
        when(inboundMessageRepository.insert(any())).thenReturn(saved);
        when(inboundMessageRepository.update(any())).thenReturn(saved);
        when(pushService.supports(Platform.FEISHU)).thenReturn(true);
        when(pushService.push(any(PushPayload.class), eq(target), any())).thenReturn(new PushResult(true, "ok", null));

        InboundMessageDto result = inboundMessageService.receive("FEISHU", new WebhookParseResult("msg1", "sender1", "User", "/help", "chat123"));

        assertEquals("help", result.intent());
        assertEquals("CONFIRMED", result.status());

        verify(fixedWorkService, never()).completeByName(any(), any(), any(), any());
        verify(workLogService, never()).create(any(), any());
        verify(inspirationNoteService, never()).createFromIm(any(), any(), any(), any());

        ArgumentCaptor<InboundMessage> messageCaptor = ArgumentCaptor.forClass(InboundMessage.class);
        verify(inboundMessageRepository).update(messageCaptor.capture());
        assertEquals("CONFIRMED", messageCaptor.getValue().getStatus());
        assertEquals("help", messageCaptor.getValue().getTargetModule());

        ArgumentCaptor<PushPayload> payloadCaptor = ArgumentCaptor.forClass(PushPayload.class);
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(pushService).push(payloadCaptor.capture(), eq(target), any());
            assertTrue(payloadCaptor.getValue().content().contains("可用指令"));
            assertTrue(payloadCaptor.getValue().content().contains("完成 [任务名]"));
        });
    }

    @Test
    void highFrequencyIntentAutoConfirmsAtLowerThreshold() {
        PushTarget target = feishuTarget();
        when(pushTargetRepository.findByPlatformAndTargetId("FEISHU", "chat123")).thenReturn(List.of(target));
        when(nlpIntentService.parse(100L, "今天做了需求评审"))
                .thenReturn(new NlpIntentService.IntentResult("add_work_log", 0.82, java.util.Map.of("content", "需求评审", "date", "today")));

        InboundMessage saved = pendingMessage("add_work_log");
        when(inboundMessageRepository.insert(any())).thenReturn(saved);
        when(inboundMessageRepository.update(any())).thenReturn(saved);
        when(dateParseService.parseToIso("today")).thenReturn(LocalDate.now().toString());
        when(workLogService.create(eq(100L), any())).thenReturn(new com.superprogrammer.workreport.dto.WorkLogDto(10L, LocalDate.now(), "需求评审", null, "IM", 0, null, null));

        InboundMessageDto result = inboundMessageService.receive("FEISHU", new WebhookParseResult("msg1", "sender1", "User", "今天做了需求评审", "chat123"));

        assertEquals("CONFIRMED", result.status());
        verify(workLogService).create(eq(100L), any());
    }

    @Test
    void nonHighFrequencyIntentDoesNotAutoConfirmAtLowThreshold() {
        PushTarget target = feishuTarget();
        when(pushTargetRepository.findByPlatformAndTargetId("FEISHU", "chat123")).thenReturn(List.of(target));
        when(nlpIntentService.parse(100L, "其他意图"))
                .thenReturn(new NlpIntentService.IntentResult("some_other", 0.82, java.util.Map.of()));

        InboundMessage saved = pendingMessage("some_other");
        when(inboundMessageRepository.insert(any())).thenReturn(saved);

        InboundMessageDto result = inboundMessageService.receive("FEISHU", new WebhookParseResult("msg1", "sender1", "User", "其他意图", "chat123"));

        assertEquals("PENDING", result.status());
        verify(workLogService, never()).create(any(), any());
    }

    private PushTarget feishuTarget() {
        PushTarget target = new PushTarget();
        target.setId(1L);
        target.setUserId(100L);
        target.setPlatform("FEISHU");
        target.setTargetId("chat123");
        target.setCredentialId(2L);
        return target;
    }

    private InboundMessage pendingMessage(String intent) {
        InboundMessage saved = new InboundMessage();
        saved.setId(1L);
        saved.setUserId(100L);
        saved.setPlatform("FEISHU");
        saved.setRawText("test");
        saved.setIntent(intent);
        saved.setStatus("PENDING");
        return saved;
    }
}
