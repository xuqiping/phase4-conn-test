package com.superprogrammer.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.media.config.MediaGenProperties;
import com.superprogrammer.media.dto.MediaGenResult;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import com.superprogrammer.media.provider.ArkSeedanceProvider;
import com.superprogrammer.media.service.internal.MediaGenTaskTxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    @Mock private MediaStorageService mediaStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MediaGenProperties properties = new MediaGenProperties();
    /** 同步 executor：poll 提交的 process 立即在同线程跑（终态首轮返回，不 sleep）。 */
    private final Executor directExecutor = Runnable::run;

    private MediaGenTaskWorker worker;

    @BeforeEach
    void setUp() {
        worker = new MediaGenTaskWorker(txService, taskMapper, arkProvider,
                mediaStorageService, properties, objectMapper, directExecutor);
    }

    @Test
    void succeeded_withUsageTokens_marksSucceededWithRealValue() {
        MediaGenTask task = pendingTask(1L, 100L, null);
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(arkProvider.createTask(any())).thenReturn("cct-1");
        when(arkProvider.queryTask("cct-1")).thenReturn(result(MediaGenResult.STATUS_SUCCEEDED,
                "https://ark/v.mp4", 200000L, null));
        when(mediaStorageService.downloadAndStore(eq("https://ark/v.mp4"), eq(100L), anyString()))
                .thenReturn("fid-1");

        worker.poll();

        verify(txService).setArkTaskId(1L, "cct-1");
        verify(txService).markSucceeded(eq(1L), eq("fid-1"), eq(200000), eq(MediaGenTask.FLAG_SUCCESS));
        verify(txService, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void succeeded_noUsage_estimatesByRate() {
        // 720p=61760 token/秒 × 5s = 308800（spec 断言常量）
        MediaGenTask task = pendingTask(1L, 100L, null);
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(arkProvider.createTask(any())).thenReturn("cct-1");
        when(arkProvider.queryTask("cct-1")).thenReturn(result(MediaGenResult.STATUS_SUCCEEDED,
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
        when(arkProvider.queryTask("cct-1")).thenReturn(result(MediaGenResult.STATUS_FAILED,
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
        when(arkProvider.createTask(any())).thenThrow(new IllegalStateException("doubao 未配置 key"));

        worker.poll();

        verify(txService).markFailed(eq(1L), contains("key"));
        verify(arkProvider, never()).queryTask(anyString());
    }

    @Test
    void succeededButNoVideoUrl_marksDownloadFailed() {
        MediaGenTask task = pendingTask(1L, 100L, "cct-1");
        when(txService.claimBatch(anyInt(), anyInt())).thenReturn(List.of(task));
        when(taskMapper.selectById(1L)).thenReturn(task);
        when(arkProvider.queryTask("cct-1")).thenReturn(result(MediaGenResult.STATUS_SUCCEEDED,
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

    // ---------- helpers ----------

    /** 造 PENDING 任务，requestConfig 含 720p/5s。arkTaskId=null 表示待建任务。 */
    private MediaGenTask pendingTask(Long id, Long userId, String arkTaskId) {
        MediaGenTask t = new MediaGenTask();
        t.setId(id);
        t.setUserId(userId);
        t.setStatus(MediaGenTask.STATUS_PENDING);
        t.setArkTaskId(arkTaskId);
        t.setTaskType(MediaGenTask.TYPE_TEXT2VIDEO);
        t.setModel("doubao-seedance-1-0");
        t.setRequestConfig("{\"prompt\":\"一只橘猫晒太阳\",\"duration\":5,\"resolution\":\"720p\"}");
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
