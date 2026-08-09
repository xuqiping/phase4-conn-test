package com.superprogrammer.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.billing.service.InflightGateService;
import com.superprogrammer.billing.service.MediaBillingService;
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

import java.math.BigDecimal;
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
    private final MediaBillingService mediaBillingService;
    /** 安全体系 S2 · L7 低余额并行闸门（SEC-FR-126）：任务终态释放提交时占的槽位。 */
    private final InflightGateService inflightGate;

    public MediaGenTaskWorker(MediaGenTaskTxService txService,
                              MediaGenTaskMapper taskMapper,
                              ArkSeedanceProvider arkProvider,
                              MediaStorageService mediaStorageService,
                              MediaGenProperties properties,
                              ObjectMapper objectMapper,
                              @Qualifier("mediaTaskExecutor") Executor executor,
                              MediaBillingService mediaBillingService,
                              InflightGateService inflightGate) {
        this.txService = txService;
        this.taskMapper = taskMapper;
        this.arkProvider = arkProvider;
        this.mediaStorageService = mediaStorageService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.mediaBillingService = mediaBillingService;
        this.inflightGate = inflightGate;
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
        } finally {
            // L7：任务离开本 worker（成功/失败/超时/下载失败均视为终态让位）→ 释放提交时占的槽位；
            // 错配场景（submit 未计数）由 release 的负值清零兜底，方向 fail-open
            inflightGate.release(task.getUserId());
        }
    }

    /** 退避轮询 Ark 至终态；超时 → FAILED。 */
    private void pollUntilTerminal(MediaGenTask task, String arkTaskId, MediaGenRequest request) {
        Long taskId = task.getId();
        long start = System.currentTimeMillis();
        long backoff = properties.getBackoffStartMs();
        int queryCount = 0;
        while (true) {
            MediaGenResult result = arkProvider.queryTask(arkTaskId, request.getProviderId());
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
     * SUCCEEDED 处理（Step4 下载 + Step5 usage 记账 + Chunk F 计费扣减）。
     * Ark URL 有时效（OSS 临时链接）→ 即时下载落 stored_files(source=MEDIA) → 写 result_file_id。
     * 下载失败 → DOWNLOAD_FAILED（ark_task_id 保留，可重试，worker 留入口）。
     * usage：Ark 返 usage.total_tokens 用真值（SUCCESS）；不返则像素/费率估算（ESTIMATED）。
     *
     * <p>计费（Chunk F）：markSucceeded <b>前</b>扣积分（视频已生成，真实成本已发生）；
     * 扣减走 {@link MediaBillingService}（吞异常，价表缺/余额耗尽不阻塞落 SUCCEEDED）。
     * 若扣成功但 markSucceeded 落库失败 → 退款（防扣了却没成功态的对账黑洞），再抛交 process() 转 markFailed。
     * 失败/超时/下载失败路径本就没扣 → 不退。
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
        // 计费扣减（返回实扣积分；null=未扣，disabled/系统调用/计费失败均吞不抛）
        BigDecimal chargedPoints = mediaBillingService.chargeMedia(task.getUserId(), task.getProviderId(),
                task.getModel(), LlmUsageLogEntity.KIND_VIDEO, tokensCost,
                request.getDuration(), 0, usageStatus(flag), taskId);
        try {
            txService.markSucceeded(taskId, fileId, tokensCost, flag);
        } catch (RuntimeException e) {
            // 扣了却落库失败：撤销已扣（防对账黑洞），再抛交 process()→markFailed
            mediaBillingService.refundMedia(task.getUserId(), chargedPoints, LlmUsageLogEntity.KIND_VIDEO, taskId);
            throw e;
        }
    }

    /** task status_flag → usage_logs status（估算口径仍计费，仅审计标记不同）。 */
    private static String usageStatus(String flag) {
        return MediaGenTask.FLAG_SUCCESS.equals(flag)
                ? LlmUsageLogEntity.STATUS_SUCCESS
                : LlmUsageLogEntity.STATUS_ESTIMATED;
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
        String frameRole = null;
        List<String[]> attachments = new java.util.ArrayList<>(); // [fileId, kind, frameRole?]
        try {
            JsonNode cfg = objectMapper.readTree(task.getRequestConfig());
            prompt = cfg.path("prompt").asText(null);
            ratio = cfg.path("ratio").asText(null);
            if (cfg.path("duration").isNumber()) duration = cfg.path("duration").asInt();
            resolution = cfg.path("resolution").asText(null);
            watermark = cfg.path("watermark").asBoolean(false);
            generateAudio = cfg.path("generateAudio").asBoolean(false);
            refFileId = cfg.path("refFileId").asText(null);
            frameRole = cfg.path("frameRole").asText(null);
            for (JsonNode a : cfg.path("attachments")) {
                String fileId = a.path("fileId").asText(null);
                String kind = a.path("kind").asText(null);
                if (fileId != null && kind != null) {
                    String role = a.path("frameRole").asText(null);
                    attachments.add(new String[]{fileId, kind, role});
                }
            }
        } catch (Exception e) {
            log.warn("解析 requestConfig 失败 taskId={}: {}", task.getId(), e.getMessage());
        }
        MediaGenRequest.MediaGenRequestBuilder b = MediaGenRequest.builder()
                .model(task.getModel())
                .providerId(task.getProviderId())
                .prompt(prompt)
                .ratio(ratio)
                .duration(duration)
                .resolution(resolution)
                .watermark(watermark)
                .generateAudio(generateAudio)
                .taskType(task.getTaskType());
        // 多模态参考附件：file_id → data URI（按类型限大小，Ark image_url/video_url/audio_url 入参）
        if (!attachments.isEmpty() && task.getUserId() != null) {
            List<MediaGenRequest.ResolvedAttachment> resolved = new java.util.ArrayList<>(attachments.size());
            for (String[] pair : attachments) {
                try {
                    resolved.add(MediaGenRequest.ResolvedAttachment.builder()
                            .kind(pair[1])
                            .dataUri(mediaStorageService.readAsDataUri(pair[0], task.getUserId(), pair[1]))
                            .frameRole(pair.length > 2 ? pair[2] : null)
                            .build());
                } catch (Exception e) {
                    log.warn("参考附件读取失败 taskId={} fileId={} kind={}: {}",
                            task.getId(), pair[0], pair[1], e.getMessage());
                    throw new IllegalArgumentException("参考附件读取失败: " + rootMessage(e));
                }
            }
            b.attachments(resolved);
            return b.build();
        }
        // 旧版 IMAGE2VIDEO：单首帧参考图 file_id → data URI（无 role = 首帧语义）；TEXT2VIDEO 无需。
        if (refFileId != null && !refFileId.isBlank() && task.getUserId() != null) {
            try {
                b.refImageUrl(mediaStorageService.readAsDataUri(refFileId, task.getUserId()));
                // C2：参考帧位置（last=尾帧 role:last_frame；first/默认=首帧裸 image_url）
                b.frameRole(frameRole);
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
