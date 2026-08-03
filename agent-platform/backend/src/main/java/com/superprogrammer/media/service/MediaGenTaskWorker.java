package com.superprogrammer.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.media.config.MediaGenProperties;
import com.superprogrammer.media.dto.MediaGenRequest;
import com.superprogrammer.media.dto.MediaGenResult;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import com.superprogrammer.media.provider.ArkSeedanceProvider;
import com.superprogrammer.media.service.internal.MediaGenTaskTxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 媒体生成任务 worker（异步轮询 Ark 至终态）。
 *
 * <p>纯 poll 模式（照抄 {@code IndexJobWorker}）：{@link #poll} 定时认领 PENDING + 锁过期 RUNNING
 * （FOR UPDATE SKIP LOCKED，多 worker 安全），分发到 {@code mediaTaskExecutor} 异步消费。
 * 服务重启后下次 poll 自动续跑未完任务（崩溃恢复免费，无需 @PostConstruct）。
 *
 * <p>process(task)：① 解析 requestConfig → {@link MediaGenRequest} ② 若无 arkTaskId →
 * {@link ArkSeedanceProvider#createTask} 落 arkTaskId ③ 退避轮询 queryTask（5s→30s 封顶，
 * 单任务 10min 超时 FAILED）至终态 ④ SUCCEEDED→markSucceeded / FAILED→markFailed。
 *
 * <p>Ark 轮询阻塞（秒~分钟级）必须在事务外，故本类无 @Transactional，DB 写经 txService 代理。
 */
@Slf4j
@Component
public class MediaGenTaskWorker {

    private static final int BATCH = 8;
    private static final int MAX_ERROR_LEN = 200;

    private final MediaGenTaskTxService txService;
    private final MediaGenTaskMapper taskMapper;
    private final ArkSeedanceProvider arkProvider;
    private final MediaStorageService mediaStorageService;
    private final MediaGenProperties properties;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public MediaGenTaskWorker(MediaGenTaskTxService txService,
                              MediaGenTaskMapper taskMapper,
                              ArkSeedanceProvider arkProvider,
                              MediaStorageService mediaStorageService,
                              MediaGenProperties properties,
                              ObjectMapper objectMapper,
                              @Qualifier("mediaTaskExecutor") Executor executor) {
        this.txService = txService;
        this.taskMapper = taskMapper;
        this.arkProvider = arkProvider;
        this.mediaStorageService = mediaStorageService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${media.poll-ms:5000}")
    public void poll() {
        try {
            List<MediaGenTask> tasks = txService.claimBatch(BATCH, properties.getLockMinutes());
            if (tasks.isEmpty()) {
                return;
            }
            log.debug("认领媒体任务 {} 条", tasks.size());
            for (MediaGenTask task : tasks) {
                executor.execute(() -> process(task.getId()));
            }
        } catch (Exception e) {
            log.error("媒体任务轮询/认领失败: {}", e.getMessage(), e);
        }
    }

    private void process(Long taskId) {
        MediaGenTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        try {
            MediaGenRequest request = buildRequest(task);
            String arkTaskId = task.getArkTaskId();
            if (arkTaskId == null || arkTaskId.isBlank()) {
                arkTaskId = arkProvider.createTask(request);
                txService.setArkTaskId(taskId, arkTaskId);
            }
            pollUntilTerminal(task, arkTaskId, request);
        } catch (Exception e) {
            log.error("媒体任务处理失败 taskId={}: {}", taskId, e.getMessage(), e);
            txService.markFailed(taskId, rootMessage(e));
        }
    }

    /** 退避轮询 Ark 至终态；超时 → FAILED。 */
    private void pollUntilTerminal(MediaGenTask task, String arkTaskId, MediaGenRequest request) {
        Long taskId = task.getId();
        long start = System.currentTimeMillis();
        long backoff = properties.getBackoffStartMs();
        int queryCount = 0;
        while (true) {
            MediaGenResult result = arkProvider.queryTask(arkTaskId);
            queryCount++;
            String status = result.getStatus();
            if (MediaGenResult.STATUS_SUCCEEDED.equals(status)) {
                handleSucceeded(task, result, request);
                log.info("媒体任务成功 taskId={} arkTaskId={} queries={} usageTokens={}",
                        taskId, arkTaskId, queryCount, result.getUsageTokens());
                return;
            }
            if (MediaGenResult.STATUS_FAILED.equals(status)) {
                txService.markFailed(taskId, result.getErrorMsg() != null ? result.getErrorMsg() : "Ark 任务失败");
                log.warn("媒体任务失败 taskId={} arkTaskId={} reason={}", taskId, arkTaskId, result.getErrorMsg());
                return;
            }
            // PENDING/RUNNING：超时判断 + 续锁 + 退避
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > properties.getTaskTimeoutSeconds() * 1000L) {
                txService.markFailed(taskId, "任务超时（>" + properties.getTaskTimeoutSeconds() + "s）");
                log.warn("媒体任务超时 taskId={} arkTaskId={} elapsedMs={}", taskId, arkTaskId, elapsed);
                return;
            }
            if (queryCount % 3 == 0) {
                // 每 3 次查询续一次锁，防长轮询中被其他 worker 重认领
                txService.renewLock(taskId, properties.getLockMinutes());
            }
            sleep(backoff);
            backoff = Math.min(properties.getBackoffCapMs(), backoff * 2);
        }
    }

    /**
     * SUCCEEDED 处理（Step4 下载 + Step5 usage 记账）。
     * Ark URL 有时效（OSS 临时链接）→ 即时下载落 stored_files(source=MEDIA) → 写 result_file_id。
     * 下载失败 → DOWNLOAD_FAILED（ark_task_id 保留，可重试，worker 留入口）。
     * usage：Ark 返 usage.total_tokens 用真值（SUCCESS）；不返则像素/费率估算（ESTIMATED）。
     */
    private void handleSucceeded(MediaGenTask task, MediaGenResult result, MediaGenRequest request) {
        Long taskId = task.getId();
        String videoUrl = result.getResultUrl();
        if (videoUrl == null || videoUrl.isBlank()) {
            txService.markDownloadFailed(taskId, "Ark 返回成功但无 video_url");
            log.warn("媒体任务无 video_url taskId={}", taskId);
            return;
        }
        String fileId;
        try {
            fileId = mediaStorageService.downloadAndStore(videoUrl, task.getUserId(), "task-" + taskId);
        } catch (Exception e) {
            log.error("视频下载落盘失败 taskId={}: {}", taskId, e.getMessage(), e);
            txService.markDownloadFailed(taskId, "视频下载落盘失败: " + rootMessage(e));
            return;
        }
        Integer tokensCost = resolveUsage(result, request);
        String flag = result.getUsageTokens() != null ? MediaGenTask.FLAG_SUCCESS : MediaGenTask.FLAG_ESTIMATED;
        txService.markSucceeded(taskId, fileId, tokensCost, flag);
    }

    /**
     * usage 记账（Step5）：Ark usage.total_tokens 真值优先；不返则按 Ark 官方 token/秒 费率表估算。
     *
     * <p>口径刻意与 llm_usage_logs(文本分词) 隔离——media token = 视频 pixel×帧×时长换算的"伪 token"，
     * 不可加总；后续 TokenUsage 落地后账单查询层 UNION 两表按 model_type 分列。
     */
    private Integer resolveUsage(MediaGenResult result, MediaGenRequest request) {
        if (result.getUsageTokens() != null) {
            return result.getUsageTokens().intValue();
        }
        int duration = request.getDuration() != null ? request.getDuration() : 5;
        int ratePerSec = estimateRatePerSec(request.getResolution());
        return duration * ratePerSec;
    }

    /** Ark 官方 token/秒 费率（视频生成），与像素公式 720p 等价（5s/720p≈30.88万 token）。4K≈4×1080p 像素面积。 */
    private int estimateRatePerSec(String resolution) {
        if (resolution == null) return 61760;
        switch (resolution) {
            case "480p": return 15440;
            case "1080p": return 138960;
            case "4K": return 555840;
            case "720p":
            default: return 61760;
        }
    }

    private MediaGenRequest buildRequest(MediaGenTask task) {
        String prompt = null;
        String ratio = null;
        Integer duration = null;
        String resolution = null;
        boolean watermark = false;
        boolean generateAudio = false;
        String refFileId = null;
        try {
            JsonNode cfg = objectMapper.readTree(task.getRequestConfig());
            prompt = cfg.path("prompt").asText(null);
            ratio = cfg.path("ratio").asText(null);
            if (cfg.path("duration").isNumber()) duration = cfg.path("duration").asInt();
            resolution = cfg.path("resolution").asText(null);
            watermark = cfg.path("watermark").asBoolean(false);
            generateAudio = cfg.path("generateAudio").asBoolean(false);
            refFileId = cfg.path("refFileId").asText(null);
        } catch (Exception e) {
            log.warn("解析 requestConfig 失败 taskId={}: {}", task.getId(), e.getMessage());
        }
        MediaGenRequest.MediaGenRequestBuilder b = MediaGenRequest.builder()
                .model(task.getModel())
                .prompt(prompt)
                .ratio(ratio)
                .duration(duration)
                .resolution(resolution)
                .watermark(watermark)
                .generateAudio(generateAudio)
                .taskType(task.getTaskType());
        // IMAGE2VIDEO：参考图 file_id → data URI（Ark image_url 入参）；TEXT2VIDEO 无需。
        if (refFileId != null && !refFileId.isBlank() && task.getUserId() != null) {
            try {
                b.refImageUrl(mediaStorageService.readAsDataUri(refFileId, task.getUserId()));
            } catch (Exception e) {
                log.warn("参考图读取失败 taskId={} refFileId={}: {}", task.getId(), refFileId, e.getMessage());
                throw new IllegalArgumentException("参考图读取失败: " + rootMessage(e));
            }
        }
        return b.build();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        if (m == null) m = c.getClass().getSimpleName();
        return m.length() > MAX_ERROR_LEN ? m.substring(0, MAX_ERROR_LEN) : m;
    }
}
