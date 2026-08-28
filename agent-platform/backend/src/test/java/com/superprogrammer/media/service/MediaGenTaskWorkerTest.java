package com.superprogrammer.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.billing.service.MediaBillingService;
import com.superprogrammer.media.config.MediaGenProperties;
import com.superprogrammer.media.dto.MediaGenResult;
import com.superprogrammer.media.dto.PreparedMediaRequest;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import com.superprogrammer.media.provider.ArkImageProvider;
import com.superprogrammer.media.provider.ArkSeedanceProvider;
import com.superprogrammer.media.service.internal.MediaGenTaskTxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MediaGenTaskWorker 状态机全分支 + usage 记账两分支（spec §测试阶段着重 1/3）。
 *
 * <p>注入同步 executor（Runnable::run）使 process() 在 poll() 内联执行；终态首轮命中即返回，不触发退避 sleep。
 * 覆盖：① SUCCEEDED+usage 真值 → markSucceeded(flag=SUCCESS)；② SUCCEEDED 无 usage → 费率估算(720p=61760/s)；
 * ③ FAILED → markFailed；④ createTask 抛异常 → markFailed；⑤ SUCCEEDED 但无 video_url → markDownloadFailed。
 */
@ExtendWith(MockitoExtension.class)
class MediaGenTaskWorkerTest {

    @Mock private MediaGenTaskTxService txService;
    @Mock private MediaGenTaskMapper taskMapper;
    @Mock private ArkSeedanceProvider arkProvider;
    @Mock private com.superprogrammer.llm.service.LlmProviderService llmProviderService;
    @Mock private ArkImageProvider imageProvider;
    @Mock private MediaStorageService mediaStorageService;
    @Mock private MediaReferenceUrlService mediaReferenceUrlService;
    @Mock private MediaBillingService mediaBillingService;
    @Mock private com.superprogrammer.billing.service.InflightGateService inflightGate;
    @Mock private com.superprogrammer.media.service.internal.MediaInflightGateService mediaInflightGate;
    @Mock private com.superprogrammer.common.metrics.BizMetrics bizMetrics;
    @Mock private com.superprogrammer.common.audit.AuditLogService auditLogService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MediaGenProperties properties = new MediaGenProperties();
    /** 同步 executor：poll 提交的 process 立即在同线程跑（终态首轮返回，不 sleep）。 */
    private final Executor directExecutor = Runnable::run;

    private MediaGenTaskWorker worker;

    @BeforeEach
    void setUp() {
        worker = new MediaGenTaskWorker(txService, taskMapper, java.util.List.of(arkProvider),
                llmProviderService, imageProvider,
                mediaStorageService, mediaReferenceUrlService, properties, objectMapper, directExecutor, mediaBillingService,
                inflightGate, mediaInflightGate, bizMetrics, auditLogService);
        // MVR-1：单元测试无 Spring 生命周期，手动初始化协议注册表（mock getId 返 'ark'）
        stub: {
            when(arkProvider.getId()).thenReturn("ark");
        }
        worker.initProviderRegistry();
    }

    @Test
    void succeeded_withUsageTokens_marksSucceededWithRealValue() {
        MediaGenTask task = pendingTask(1L, 100L, "cct-1");
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(arkProvider.queryTask(eq("cct-1"), any())).thenReturn(result(MediaGenResult.STATUS_SUCCEEDED,
                "https://ark/v.mp4", 200000L, null));
        when(mediaStorageService.downloadAndStore(eq("https://ark/v.mp4"), eq(100L), anyString()))
                .thenReturn("fid-1");

        worker.poll();

        verify(txService, never()).setArkTaskId(anyLong(), anyString());
        verify(txService).markSucceeded(eq(1L), eq("fid-1"), eq(200000), eq(MediaGenTask.FLAG_SUCCESS));
        verify(txService, never()).markFailed(anyLong(), anyString());
        // Chunk F：成功路径扣减计费（kind=VIDEO，refId=taskId，视频伪-token=200000）
        // 7x-3：chargeMedia 现为 10 参（带 hasReference）；计划5 Step5 再加 projectGroupId（11 参）
        // 7x-1（V152）：chargeMedia 12 参（+resolution）
        // 7x（V155）：worker 改调 settleMediaSuccess（13 参 +heldPoints；未预扣任务 held=null 走原全量扣）
        verify(mediaBillingService).settleMediaSuccess(eq(100L), any(), anyString(), eq(LlmUsageLogEntity.KIND_VIDEO),
                eq(200000), eq(5), eq(0), eq(LlmUsageLogEntity.STATUS_SUCCESS), eq(1L), anyBoolean(),
                isNull(), any(), isNull());
        verify(mediaBillingService, never()).refundMediaCharged(anyLong(), any(), any(), anyString(), anyLong(), any());
        // 指标：成功终态正好一次（kind=video,result=success）+ 端到端耗时
        verify(bizMetrics).mediaTaskTerminal("video", "success");
        verify(bizMetrics).mediaTaskDuration(eq("video"), any());
        // Chunk3 #1：成功终态二次审计 video_gen_success，detail 带 model+kind（与 video_submit 同 targetId=1 关联）
        verify(auditLogService).recordTask(eq("media"), eq("video_gen_success"), eq("media_gen_task"),
                eq("1"), eq(100L), isNull(), isNull(), contains("doubao-seedance-1-0"),
                eq(com.superprogrammer.common.audit.AuditLogEntity.RESULT_SUCCESS));
    }

    @Test
    void succeeded_marksSucceededWhenBillingDisabled() {
        // 计费禁用/系统调用：settleMediaSuccess 返 null（未扣），仍正常 markSucceeded，不退款
        MediaGenTask task = pendingTask(1L, 100L, "cct-1");
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(arkProvider.queryTask(eq("cct-1"), any())).thenReturn(result(MediaGenResult.STATUS_SUCCEEDED,
                "https://ark/v.mp4", 200000L, null));
        when(mediaStorageService.downloadAndStore(anyString(), eq(100L), anyString())).thenReturn("fid-1");
        when(mediaBillingService.settleMediaSuccess(anyLong(), any(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyString(), anyLong(), anyBoolean(), any(), any(), any()))
                .thenReturn(null);

        worker.poll();

        verify(txService).markSucceeded(eq(1L), eq("fid-1"), eq(200000), eq(MediaGenTask.FLAG_SUCCESS));
        verify(mediaBillingService, never()).refundMediaCharged(anyLong(), any(), any(), anyString(), anyLong(), any());
    }

    @Test
    void succeeded_settleChargedNothing_marksFailedAndRefundsHold() {
        // 2026-08-25 第二层 fail-closed：计费开 + 用户任务 + 结算返 null（一分没扣到）→
        // 不交付：退预扣腿 + markFailed，绝不落 SUCCEEDED（白嫖封堵）
        MediaGenTask task = pendingTask(1L, 100L, "cct-1");
        task.setHoldApplied(true);
        task.setEstimatedCost(new BigDecimal("30"));
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(arkProvider.queryTask(eq("cct-1"), any())).thenReturn(result(MediaGenResult.STATUS_SUCCEEDED,
                "https://ark/v.mp4", 200000L, null));
        when(mediaStorageService.downloadAndStore(anyString(), eq(100L), anyString())).thenReturn("fid-1");
        when(mediaBillingService.settleMediaSuccess(anyLong(), any(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyString(), anyLong(), anyBoolean(), any(), any(), any()))
                .thenReturn(null);
        when(mediaBillingService.billingEnabled()).thenReturn(true);

        worker.poll();

        verify(txService, never()).markSucceeded(anyLong(), anyString(), anyInt(), anyString());
        verify(mediaBillingService).refundMediaCharged(eq(100L), eq(new BigDecimal("30")),
                eq(new BigDecimal("30")), eq(LlmUsageLogEntity.KIND_VIDEO), eq(1L), isNull());
        verify(txService).markFailed(eq(1L), contains("结算扣费失败"));
    }

    @Test
    void markSucceededThrows_refundsChargedPointsAndFails() {
        // 扣成功但 markSucceeded 落库失败：撤销已扣 + 抛交 process()→markFailed（spec §联动 失败全退边界）
        MediaGenTask task = pendingTask(1L, 100L, "cct-1");
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(arkProvider.queryTask(eq("cct-1"), any())).thenReturn(result(MediaGenResult.STATUS_SUCCEEDED,
                "https://ark/v.mp4", 1000L, null));
        when(mediaStorageService.downloadAndStore(anyString(), eq(100L), anyString())).thenReturn("fid-1");
        when(mediaBillingService.settleMediaSuccess(anyLong(), any(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyString(), anyLong(), anyBoolean(), any(), any(), any()))
                .thenReturn(new BigDecimal("50"));
        doThrow(new IllegalStateException("DB 抖动")).when(txService)
                .markSucceeded(anyLong(), anyString(), anyInt(), anyString());

        worker.poll();

        verify(mediaBillingService).refundMediaCharged(eq(100L), eq(new BigDecimal("50")), isNull(),
                eq(LlmUsageLogEntity.KIND_VIDEO), eq(1L), isNull());
        verify(txService).markFailed(eq(1L), contains("DB"));
    }

    @Test
    void arkFailed_neverCharges() {
        // 失败路径：未到扣减环节，chargeMedia 不应被调
        MediaGenTask task = pendingTask(1L, 100L, "cct-1");
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(arkProvider.queryTask(eq("cct-1"), any())).thenReturn(result(MediaGenResult.STATUS_FAILED,
                null, null, "Ark 500"));

        worker.poll();

        verify(txService).markFailed(eq(1L), contains("500"));
        // 指标：失败终态正好一次（result=fail），不记成功
        verify(bizMetrics).mediaTaskTerminal("video", "fail");
        verify(bizMetrics, never()).mediaTaskTerminal(anyString(), eq("success"));
        verify(mediaBillingService, never()).settleMediaSuccess(anyLong(), any(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyString(), anyLong(), anyBoolean(), any(), any(), any());
        verify(mediaBillingService, never()).refundMediaCharged(anyLong(), any(), any(), anyString(), anyLong(), any());
    }

    @Test
    void succeeded_noUsage_estimatesByRate() {
        // 720p=61760 token/秒 × 5s = 308800（spec 断言常量）
        MediaGenTask task = pendingTask(1L, 100L, "cct-1");
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(arkProvider.queryTask(eq("cct-1"), any())).thenReturn(result(MediaGenResult.STATUS_SUCCEEDED,
                "https://ark/v.mp4", null, null)); // 无 usage → 估算
        when(mediaStorageService.downloadAndStore(anyString(), eq(100L), anyString())).thenReturn("fid-1");

        worker.poll();

        verify(txService).markSucceeded(eq(1L), eq("fid-1"), eq(308800), eq(MediaGenTask.FLAG_ESTIMATED));
    }

    @Test
    void arkFailed_marksFailed() {
        MediaGenTask task = pendingTask(1L, 100L, "cct-1"); // 已有 arkTaskId，跳过 createTask
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(arkProvider.queryTask(eq("cct-1"), any())).thenReturn(result(MediaGenResult.STATUS_FAILED,
                null, null, "Ark 429 限流"));

        worker.poll();

        verify(txService).markFailed(eq(1L), contains("429"));
        verify(txService, never()).markSucceeded(anyLong(), anyString(), anyInt(), anyString());
        verify(mediaStorageService, never()).downloadAndStore(anyString(), any(), anyString());
    }

    @Test
    void createTaskThrows_marksFailed() {
        MediaGenTask task = pendingTask(1L, 100L, null);
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        PreparedMediaRequest prepared = PreparedMediaRequest.builder().body(java.util.Map.of())
                .snapshot(objectMapper.createObjectNode().put("provider", "ark-seedance")).build();
        when(arkProvider.prepareCreateRequest(any())).thenReturn(prepared);
        when(arkProvider.createPreparedTask(any(), eq(prepared))).thenThrow(new IllegalStateException("doubao 未配置 key"));

        worker.poll();

        verify(txService).markFailed(eq(1L), contains("key"));
        org.mockito.InOrder order = inOrder(txService, arkProvider);
        order.verify(txService).saveProviderRequestSnapshot(eq(1L), contains("ark-seedance"));
        order.verify(arkProvider).createPreparedTask(any(), eq(prepared));
        verify(arkProvider, never()).queryTask(anyString(), any());
    }

    @Test
    void createTask_AC_V3_05_schedulesNextClaimWithoutQueryingOrReleasingInflight() {
        MediaGenTask task = pendingTask(1L, 100L, null);
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        PreparedMediaRequest prepared = PreparedMediaRequest.builder().body(java.util.Map.of())
                .snapshot(objectMapper.createObjectNode()).build();
        when(arkProvider.prepareCreateRequest(any())).thenReturn(prepared);
        when(arkProvider.createPreparedTask(any(), eq(prepared))).thenReturn("cct-1");

        worker.poll();

        verify(txService).setArkTaskId(1L, "cct-1");
        org.mockito.InOrder order = inOrder(txService, arkProvider);
        order.verify(txService).saveProviderRequestSnapshot(eq(1L), anyString());
        order.verify(arkProvider).createPreparedTask(any(), eq(prepared));
        verify(txService).scheduleNextQuery(eq(1L), eq(properties.getBackoffStartMs()));
        verify(arkProvider, never()).queryTask(anyString(), any());
        verify(inflightGate, never()).release(anyLong());
        verify(bizMetrics, never()).mediaTaskTerminal(anyString(), anyString());
    }

    @Test
    void running_AC_V3_05_schedulesNextClaimWithoutTerminalSideEffects() {
        MediaGenTask task = pendingTask(1L, 100L, "cct-1");
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(arkProvider.queryTask("cct-1", null)).thenReturn(result(MediaGenResult.STATUS_RUNNING, null, null, null));

        worker.poll();

        verify(txService).scheduleNextQuery(eq(1L), eq(properties.getBackoffStartMs()));
        verify(txService, never()).markFailed(anyLong(), anyString());
        verify(inflightGate, never()).release(anyLong());
        verify(bizMetrics, never()).mediaTaskTerminal(anyString(), anyString());
    }

    @Test
    void queryNetworkError_AC_V3_05_retriesInsteadOfFailing() {
        MediaGenTask task = pendingTask(1L, 100L, "cct-1");
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(arkProvider.queryTask("cct-1", null)).thenThrow(new IllegalStateException("read timed out"));

        worker.poll();

        verify(txService).scheduleNextQuery(eq(1L), eq(properties.getBackoffStartMs()));
        verify(txService, never()).markFailed(anyLong(), anyString());
        verify(inflightGate, never()).release(anyLong());
    }

    @Test
    void succeededButNoVideoUrl_marksDownloadFailed() {
        MediaGenTask task = pendingTask(1L, 100L, "cct-1");
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(arkProvider.queryTask(eq("cct-1"), any())).thenReturn(result(MediaGenResult.STATUS_SUCCEEDED,
                null, 100L, null)); // 成功但无 video_url

        worker.poll();

        verify(txService).markDownloadFailed(eq(1L), anyString());
        verify(txService, never()).markSucceeded(anyLong(), anyString(), anyInt(), anyString());
        verify(mediaStorageService, never()).downloadAndStore(anyString(), any(), anyString());
    }

    @Test
    void poll_emptyClaim_doesNothing() {
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of());

        worker.poll();

        verify(taskMapper, never()).selectById(anyLong());
        verify(arkProvider, never()).createTask(any());
    }

    // ---------- C3 · 并发闸终态释放守卫（迁移真正落库才 release） ----------

    // C3：终态迁移真正落库（markFailed 返回 true）→ 释放对应 kind 槽位
    @Test
    void terminalTransition_releasesMediaInflightByKind() {
        MediaGenTask task = pendingTask(1L, 100L, "cct-1");
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(arkProvider.queryTask(eq("cct-1"), any())).thenReturn(result(MediaGenResult.STATUS_FAILED,
                null, null, "Ark 429"));
        when(txService.markFailed(eq(1L), anyString())).thenReturn(true);

        worker.poll();

        verify(mediaInflightGate).release(100L,
                com.superprogrammer.media.service.internal.MediaInflightGateService.KIND_VIDEO);
    }

    // C3：重复终态回调（markFailed 影响 0 行=false，锁过期重认领双 worker 场景）→ 不再释放，防双 DECR 超卖
    @Test
    void alreadyTerminal_noSecondMediaRelease() {
        MediaGenTask task = pendingTask(1L, 100L, "cct-1");
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(arkProvider.queryTask(eq("cct-1"), any())).thenReturn(result(MediaGenResult.STATUS_FAILED,
                null, null, "Ark 429"));
        when(txService.markFailed(eq(1L), anyString())).thenReturn(false); // 已终态，未迁移

        worker.poll();

        verify(mediaInflightGate, never()).release(anyLong(), anyString());
    }

    // C3：图片任务终态迁移 → 释放 image 槽位（kind 分流）
    @Test
    void imageTerminalTransition_releasesImageKind() {
        MediaGenTask task = pendingTask(1L, 100L, null);
        task.setTaskType(MediaGenTask.TYPE_TEXT2IMAGE);
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(imageProvider.generate(any())).thenThrow(new IllegalStateException("生图超时"));
        when(txService.markFailed(eq(1L), anyString())).thenReturn(true);

        worker.poll();

        verify(mediaInflightGate).release(100L,
                com.superprogrammer.media.service.internal.MediaInflightGateService.KIND_IMAGE);
    }

    // ---------- buildRequest（附件分支，ReflectionTestUtils 直调私有方法） ----------

    @Test
    void buildRequest_imageAndVideoUseSignedHttpsUrl_audioKeepsDataUri() {
        MediaGenTask task = pendingTask(1L, 100L, null);
        task.setProviderId(7L);
        task.setTaskType(MediaGenTask.TYPE_IMAGE2VIDEO);
        task.setRequestConfig("{\"prompt\":\"p\",\"ratio\":\"16:9\",\"duration\":5,\"resolution\":\"720p\","
                + "\"attachments\":[{\"fileId\":\"i1.png\",\"kind\":\"image\"},"
                + "{\"fileId\":\"v1.mp4\",\"kind\":\"video\"},"
                + "{\"fileId\":\"a1.mp3\",\"kind\":\"audio\"}]}");
        // 修复VI 2x#5：图片也走签名 URL（不再 readAsDataUri）
        when(mediaReferenceUrlService.createMediaUrl("i1.png")).thenReturn(
                "https://media.example.com/api/media/reference/i1.png?expires=1&sig=i");
        when(mediaReferenceUrlService.createMediaUrl("v1.mp4")).thenReturn(
                "https://media.example.com/api/media/reference/v1.mp4?expires=1&sig=x");
        when(mediaStorageService.readAsDataUri("a1.mp3", 100L, "audio")).thenReturn("data:audio/mpeg;base64,A");

        com.superprogrammer.media.dto.MediaGenRequest req =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(worker, "buildRequest", task);

        assert req != null;
        org.junit.jupiter.api.Assertions.assertEquals(3, req.getAttachments().size());
        org.junit.jupiter.api.Assertions.assertEquals("image", req.getAttachments().get(0).getKind());
        org.junit.jupiter.api.Assertions.assertEquals(
                "https://media.example.com/api/media/reference/i1.png?expires=1&sig=i",
                req.getAttachments().get(0).getUrl());
        org.junit.jupiter.api.Assertions.assertEquals(
                "https://media.example.com/api/media/reference/v1.mp4?expires=1&sig=x",
                req.getAttachments().get(1).getUrl());
        org.junit.jupiter.api.Assertions.assertEquals("audio", req.getAttachments().get(2).getKind());
        org.junit.jupiter.api.Assertions.assertEquals("data:audio/mpeg;base64,A",
                req.getAttachments().get(2).getUrl());
        // providerId 透传（多 MEDIA provider 路由）；attachments 分支不走旧首帧
        org.junit.jupiter.api.Assertions.assertEquals(7L, req.getProviderId());
        org.junit.jupiter.api.Assertions.assertNull(req.getRefImageUrl());
    }

    @Test
    void buildRequest_legacyRefFileId_stillResolves() {
        MediaGenTask task = pendingTask(1L, 100L, null);
        task.setTaskType(MediaGenTask.TYPE_IMAGE2VIDEO);
        task.setRequestConfig("{\"prompt\":\"p\",\"refFileId\":\"legacy.png\"}");
        // 修复VI 2x#5：旧版首帧参考图同样切签名 URL
        when(mediaReferenceUrlService.createMediaUrl("legacy.png")).thenReturn(
                "https://media.example.com/api/media/reference/legacy.png?expires=1&sig=l");

        com.superprogrammer.media.dto.MediaGenRequest req =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(worker, "buildRequest", task);

        assert req != null;
        org.junit.jupiter.api.Assertions.assertEquals(
                "https://media.example.com/api/media/reference/legacy.png?expires=1&sig=l",
                req.getRefImageUrl());
        org.junit.jupiter.api.Assertions.assertNull(req.getAttachments());
    }

    @Test
    void buildRequest_attachmentReadFailure_throws() {
        MediaGenTask task = pendingTask(1L, 100L, null);
        task.setRequestConfig("{\"prompt\":\"p\",\"attachments\":[{\"fileId\":\"big.png\",\"kind\":\"image\"}]}");
        when(mediaReferenceUrlService.createMediaUrl("big.png"))
                .thenThrow(new IllegalStateException("参考媒体公网地址未配置"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(worker, "buildRequest", task));
    }

    // ---------- helpers ----------

    /** 造 PENDING 任务，requestConfig 含 720p/5s。arkTaskId=null 表示待建任务。 */
    // ---------- MVR-1：provider protocol 路由 ----------

    @Test
    void mvr1_protocolHit_routesQueryToArkAdapter() {
        // provider 行 protocol='ark'（V163 迁移后口径）→ 查态路由到 ark 适配器
        MediaGenTask task = pendingTask(1L, 100L, "cct-9");
        task.setProviderId(77L);
        com.superprogrammer.llm.entity.LlmProviderEntity row =
                new com.superprogrammer.llm.entity.LlmProviderEntity();
        row.setId(77L);
        row.setProtocol("ark");
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(llmProviderService.getById(77L)).thenReturn(row);
        when(arkProvider.queryTask(eq("cct-9"), eq(77L))).thenReturn(result(MediaGenResult.STATUS_SUCCEEDED,
                "https://ark/v.mp4", 1000L, null));
        when(mediaStorageService.downloadAndStore(anyString(), eq(100L), anyString())).thenReturn("fid-9");

        worker.poll();

        verify(arkProvider).queryTask("cct-9", 77L);
        verify(txService).markSucceeded(eq(1L), eq("fid-9"), anyInt(), anyString());
    }

    @Test
    void mvr1_blankProtocol_fallsBackToArk() {
        // 存量行 protocol 为空（V163 未跑/新行漏配）→ 回落 ark 不炸
        MediaGenTask task = pendingTask(1L, 100L, "cct-1");
        task.setProviderId(88L);
        com.superprogrammer.llm.entity.LlmProviderEntity row =
                new com.superprogrammer.llm.entity.LlmProviderEntity();
        row.setId(88L);
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(llmProviderService.getById(88L)).thenReturn(row);
        when(arkProvider.queryTask(eq("cct-1"), eq(88L))).thenReturn(result(MediaGenResult.STATUS_FAILED,
                null, null, "Ark 429"));

        worker.poll();

        verify(txService).markFailed(eq(1L), contains("Ark 429"));
    }

    @Test
    void mvr1_unregisteredProtocol_marksFailedWithReadableMessage() {
        // protocol=minimax 但适配器未注册 → 任务 FAILED 带可读话术（不静默卡 PENDING）
        MediaGenTask task = pendingTask(1L, 100L, null);
        task.setProviderId(99L);
        com.superprogrammer.llm.entity.LlmProviderEntity row =
                new com.superprogrammer.llm.entity.LlmProviderEntity();
        row.setId(99L);
        row.setProtocol("ghost-protocol");
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(llmProviderService.getById(99L)).thenReturn(row);

        worker.poll();

        verify(arkProvider, never()).prepareCreateRequest(any());
        verify(txService).markFailed(eq(1L), contains("视频协议 ghost-protocol 未注册适配器"));
    }

    @Test
    void mvr1_providerRowDeleted_marksFailed() {
        // provider 行被删（getById=null）→ FAILED 可读话术，不静默轮询
        MediaGenTask task = pendingTask(1L, 100L, "cct-1");
        task.setProviderId(404L);
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(llmProviderService.getById(404L)).thenReturn(null);

        worker.poll();

        verify(arkProvider, never()).queryTask(anyString(), any());
        verify(txService).markFailed(eq(1L), contains("视频 provider 已停用或删除"));
    }

    private MediaGenTask pendingTask(Long id, Long userId, String arkTaskId) {
        MediaGenTask t = new MediaGenTask();
        t.setId(id);
        t.setUserId(userId);
        t.setStatus(MediaGenTask.STATUS_PENDING);
        t.setArkTaskId(arkTaskId);
        t.setTaskType(MediaGenTask.TYPE_TEXT2VIDEO);
        t.setModel("doubao-seedance-1-0");
        t.setRequestConfig("{\"prompt\":\"一只橘猫晒太阳\",\"duration\":5,\"resolution\":\"720p\"}");
        t.setCreatedAt(java.time.OffsetDateTime.now().minusSeconds(5));
        return t;
    }

    private MediaGenResult result(String status, String url, Long usageTokens, String errorMsg) {
        return MediaGenResult.builder()
                .status(status)
                .resultUrl(url)
                .usageTokens(usageTokens)
                .errorMsg(errorMsg)
                .build();
    }
}
