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
import com.superprogrammer.workreport.service.push.PushResult;
import com.superprogrammer.workreport.service.push.PushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InspirationReviewServiceTest {

    @Mock
    private ReportConfigRepository reportConfigRepository;

    @Mock
    private ReportConfigPushTargetRefRepository reportConfigPushTargetRefRepository;

    @Mock
    private InspirationNoteRepository inspirationNoteRepository;

    @Mock
    private PushTargetRepository pushTargetRepository;

    @Mock
    private PushCredentialRepository pushCredentialRepository;

    @Mock
    private CredentialEncryptor credentialEncryptor;

    @Mock
    private PushService pushService;

    private InspirationReviewService inspirationReviewService;
    private Method shouldTriggerToday;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        inspirationReviewService = new InspirationReviewService(
                reportConfigRepository,
                reportConfigPushTargetRefRepository,
                inspirationNoteRepository,
                pushTargetRepository,
                pushCredentialRepository,
                credentialEncryptor,
                List.of(pushService)
        );
        shouldTriggerToday = InspirationReviewService.class.getDeclaredMethod("shouldTriggerToday", ReportConfig.class, OffsetDateTime.class);
        shouldTriggerToday.setAccessible(true);
    }

    @Test
    void shouldTriggerAtDefaultReviewTimeInConfigTimezone() throws Exception {
        ReportConfig config = new ReportConfig();
        config.setTimezone("Asia/Shanghai");

        OffsetDateTime now = OffsetDateTime.of(2026, 6, 30, 9, 0, 0, 0, ZoneOffset.ofHours(8));
        assertTrue((Boolean) shouldTriggerToday.invoke(inspirationReviewService, config, now));
    }

    @Test
    void shouldNotTriggerAtOtherMinutes() throws Exception {
        ReportConfig config = new ReportConfig();
        config.setTimezone("Asia/Shanghai");

        OffsetDateTime now = OffsetDateTime.of(2026, 6, 30, 9, 1, 0, 0, ZoneOffset.ofHours(8));
        assertFalse((Boolean) shouldTriggerToday.invoke(inspirationReviewService, config, now));
    }

    @Test
    void shouldConvertUtcToConfiguredTimezone() throws Exception {
        ReportConfig config = new ReportConfig();
        config.setTimezone("Asia/Shanghai");

        // UTC 01:00 = Shanghai 09:00
        OffsetDateTime now = OffsetDateTime.of(2026, 6, 30, 1, 0, 0, 0, ZoneOffset.UTC);
        assertTrue((Boolean) shouldTriggerToday.invoke(inspirationReviewService, config, now));
    }

    @Test
    void scanAndPushSkipsWhenNoUnreviewedNotes() {
        ReportConfig config = enabledConfig();
        when(reportConfigRepository.findByInspirationReviewEnabled(true)).thenReturn(List.of(config));
        when(inspirationNoteRepository.findUnreviewedByUserId(config.getUserId(), 3)).thenReturn(List.of());

        inspirationReviewService.scanAndPush(OffsetDateTime.of(2026, 6, 30, 9, 0, 0, 0, ZoneOffset.ofHours(8)));

        verifyNoInteractions(pushTargetRepository);
    }

    @Test
    void scanAndPushUpdatesReviewedAtOnSuccess() {
        ReportConfig config = enabledConfig();
        PushTarget target = feishuTarget();
        InspirationNote note = unreviewedNote(1L, "灵感内容");

        when(reportConfigRepository.findByInspirationReviewEnabled(true)).thenReturn(List.of(config));
        when(inspirationNoteRepository.findUnreviewedByUserId(config.getUserId(), 3)).thenReturn(List.of(note));
        when(reportConfigPushTargetRefRepository.findByConfigId(config.getId())).thenReturn(List.of(ref(config.getId(), target.getId())));
        when(pushTargetRepository.findByIds(List.of(target.getId()))).thenReturn(List.of(target));
        when(pushService.supports(Platform.FEISHU)).thenReturn(true);
        when(pushService.push(any(), eq(target), any())).thenReturn(new PushResult(true, "ok", null));

        inspirationReviewService.scanAndPush(OffsetDateTime.of(2026, 6, 30, 9, 0, 0, 0, ZoneOffset.ofHours(8)));

        ArgumentCaptor<InspirationNote> captor = ArgumentCaptor.forClass(InspirationNote.class);
        verify(inspirationNoteRepository).update(captor.capture());
        assertNotNull(captor.getValue().getReviewedAt());
    }

    @Test
    void scanAndPushDoesNotUpdateReviewedAtOnFailure() {
        ReportConfig config = enabledConfig();
        PushTarget target = feishuTarget();
        InspirationNote note = unreviewedNote(1L, "灵感内容");

        when(reportConfigRepository.findByInspirationReviewEnabled(true)).thenReturn(List.of(config));
        when(inspirationNoteRepository.findUnreviewedByUserId(config.getUserId(), 3)).thenReturn(List.of(note));
        when(reportConfigPushTargetRefRepository.findByConfigId(config.getId())).thenReturn(List.of(ref(config.getId(), target.getId())));
        when(pushTargetRepository.findByIds(List.of(target.getId()))).thenReturn(List.of(target));
        when(pushService.supports(Platform.FEISHU)).thenReturn(true);
        when(pushService.push(any(), eq(target), any())).thenReturn(new PushResult(false, "failed", null));

        inspirationReviewService.scanAndPush(OffsetDateTime.of(2026, 6, 30, 9, 0, 0, 0, ZoneOffset.ofHours(8)));

        verify(inspirationNoteRepository, never()).update(any());
    }

    private ReportConfig enabledConfig() {
        ReportConfig config = new ReportConfig();
        config.setId(100L);
        config.setUserId(1L);
        config.setTimezone("Asia/Shanghai");
        config.setInspirationReviewEnabled(true);
        return config;
    }

    private PushTarget feishuTarget() {
        PushTarget target = new PushTarget();
        target.setId(200L);
        target.setPlatform("FEISHU");
        target.setTargetType("GROUP");
        target.setTargetId("chat123");
        target.setCredentialId(300L);
        return target;
    }

    private ReportConfigPushTargetRef ref(Long configId, Long targetId) {
        ReportConfigPushTargetRef r = new ReportConfigPushTargetRef();
        r.setConfigId(configId);
        r.setTargetId(targetId);
        return r;
    }

    private InspirationNote unreviewedNote(Long id, String content) {
        InspirationNote note = new InspirationNote();
        note.setId(id);
        note.setUserId(1L);
        note.setContent(content);
        note.setTags(List.of("tag1"));
        note.setSource("IM");
        note.setReviewedAt(null);
        return note;
    }
}
