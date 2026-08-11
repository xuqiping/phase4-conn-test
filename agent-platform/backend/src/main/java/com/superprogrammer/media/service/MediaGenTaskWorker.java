package com.superprogrammer.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.billing.service.InflightGateService;
import com.superprogrammer.billing.service.MediaBillingService;
import com.superprogrammer.media.config.MediaGenProperties;
import com.superprogrammer.media.dto.MediaGenRequest;
import com.superprogrammer.media.dto.MediaGenResult;
import com.superprogrammer.media.dto.MediaImageRequest;
import com.superprogrammer.media.dto.MediaImageResult;
import com.superprogrammer.media.dto.PreparedMediaRequest;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import com.superprogrammer.media.provider.ArkImageProvider;
import com.superprogrammer.media.provider.ArkSeedanceProvider;
import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.media.service.internal.MediaGenTaskTxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 媒体生成任务 worker（异步轮询 Ark 至终态）。
 *
 * <p>纯 poll 模式（照抄 {@code IndexJobWorker}）：{@link #poll} 定时认领 PENDING + 锁过期 RUNNING
 * （FOR UPDATE SKIP LOCKED，多 worker 安全），分发到 {@code mediaTaskExecutor} 异步消费。
 * 服务重启后下次 poll 自动续跑未完任务（崩溃恢复免费，无需 @PostConstruct）。
 *
 * <p>process(task)：① 解析 requestConfig → {@link MediaGenRequest} ② 若无 arkTaskId →
 * {@link ArkSeedanceProvider#createTask} 落 arkTaskId ③ 每次认领只调用一次 create/query，
 * 非终态用 locked_until 安排退避后的下一次认领并立即释放线程 ④ 终态才落盘、计费和释放 inflight。
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
    private final ArkImageProvider imageProvider;
    private final MediaStorageService mediaStorageService;
    private final MediaGenProperties properties;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final MediaBillingService mediaBillingService;
    /** 安全体系 S2 · L7 低余额并行闸门（SEC-FR-126）：任务终态释放提交时占的槽位。 */
    private final InflightGateService inflightGate;
    /** 媒体终态/耗时指标（media.task.terminal/duration）。 */
    private final BizMetrics bizMetrics;

    public MediaGenTaskWorker(MediaGenTaskTxService txService,
                              MediaGenTaskMapper taskMapper,
                              ArkSeedanceProvider arkProvider,
                              ArkImageProvider imageProvider,
                              MediaStorageService mediaStorageService,
                              MediaGenProperties properties,
                              ObjectMapper objectMapper,
                              @Qualifier("mediaTaskExecutor") Executor executor,
                              MediaBillingService mediaBillingService,
                              InflightGateService inflightGate,
                              BizMetrics bizMetrics) {
        this.txService = txService;
        this.taskMapper = taskMapper;
        this.arkProvider = arkProvider;
        this.imageProvider = imageProvider;
        this.mediaStorageService = mediaStorageService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.mediaBillingService = mediaBillingService;
        this.inflightGate = inflightGate;
        this.bizMetrics = bizMetrics;
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
        String kind = isImageTask(task.getTaskType()) ? BizMetrics.MEDIA_IMAGE : BizMetrics.MEDIA_VIDEO;
        boolean terminal = false;
        boolean succeeded = false;
        try {
            // 图片任务（Seedream）走同步生图路径，与视频异步轮询分流（video 路径零改动）。
            if (isImageTask(task.getTaskType())) {
                terminal = true;
                succeeded = processImage(task);
                return;
            }
            String arkTaskId = task.getArkTaskId();
            if (arkTaskId == null || arkTaskId.isBlank()) {
                MediaGenRequest request = buildRequest(task, true);
                PreparedMediaRequest prepared = arkProvider.prepareCreateRequest(request);
                txService.saveProviderRequestSnapshot(taskId, objectMapper.writeValueAsString(prepared.getSnapshot()));
                arkTaskId = arkProvider.createPreparedTask(request, prepared);
                txService.setArkTaskId(taskId, arkTaskId);
                long delayMs = nextBackoffMs(task);
                txService.scheduleNextQuery(taskId, delayMs);
                log.info("媒体任务已创建 taskId={} arkTaskId={} nextQueryMs={}", taskId, arkTaskId, delayMs);
                return;
            }
            MediaGenResult result;
            try {
                result = arkProvider.queryTask(arkTaskId, task.getProviderId());
            } catch (Exception queryError) {
                long delayMs = nextBackoffMs(task);
                txService.scheduleNextQuery(taskId, delayMs);
                log.warn("媒体任务查询异常，将重试 taskId={} attempt={} nextQueryMs={} reason={}",
                        taskId, task.getAttempt(), delayMs, rootMessage(queryError));
                return;
            }
            String status = result.getStatus();
            if (MediaGenResult.STATUS_SUCCEEDED.equals(status)) {
                terminal = true;
                succeeded = handleSucceeded(task, result, buildRequest(task, false));
                log.info("媒体任务成功 taskId={} arkTaskId={} usageTokens={}",
                        taskId, arkTaskId, result.getUsageTokens());
            } else if (MediaGenResult.STATUS_FAILED.equals(status)) {
                terminal = true;
                txService.markFailed(taskId, result.getErrorMsg() != null ? result.getErrorMsg() : "Ark 任务失败");
                log.warn("媒体任务失败 taskId={} arkTaskId={} reason={}", taskId, arkTaskId, result.getErrorMsg());
            } else {
                long delayMs = nextBackoffMs(task);
                txService.scheduleNextQuery(taskId, delayMs);
                log.debug("媒体任务未完成 taskId={} arkTaskId={} status={} nextQueryMs={}",
                        taskId, arkTaskId, status, delayMs);
            }
        } catch (Exception e) {
            terminal = true;
            log.error("媒体任务处理失败 taskId={}: {}", taskId, e.getMessage(), e);
            txService.markFailed(taskId, rootMessage(e));
        } finally {
            if (terminal) {
                inflightGate.release(task.getUserId());
                bizMetrics.mediaTaskTerminal(kind, succeeded ? BizMetrics.RESULT_SUCCESS : BizMetrics.RESULT_FAIL);
                if (task.getCreatedAt() != null) {
                    bizMetrics.mediaTaskDuration(kind,
                            java.time.Duration.between(task.getCreatedAt(), java.time.OffsetDateTime.now()));
                }
            }
        }
    }

    private long nextBackoffMs(MediaGenTask task) {
        long delay = Math.max(1L, properties.getBackoffStartMs());
        int attempt = Math.max(1, task.getAttempt() == null ? 1 : task.getAttempt());
        for (int i = 1; i < attempt && delay < properties.getBackoffCapMs(); i++) {
            delay = Math.min(properties.getBackoffCapMs(), delay * 2);
        }
        return delay;
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
    private boolean handleSucceeded(MediaGenTask task, MediaGenResult result, MediaGenRequest request) {
        Long taskId = task.getId();
        String videoUrl = result.getResultUrl();
        if (videoUrl == null || videoUrl.isBlank()) {
            txService.markDownloadFailed(taskId, "Ark 返回成功但无 video_url");
            log.warn("媒体任务无 video_url taskId={}", taskId);
            return false;
        }
        String fileId;
        try {
            fileId = mediaStorageService.downloadAndStore(videoUrl, task.getUserId(), "task-" + taskId);
        } catch (Exception e) {
            log.error("视频下载落盘失败 taskId={}: {}", taskId, e.getMessage(), e);
            txService.markDownloadFailed(taskId, "视频下载落盘失败: " + rootMessage(e));
            return false;
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
        return true;
    }

    // ---------- 图片任务路径（Seedream 同步生图，与视频异步轮询零耦合） ----------

    private static boolean isImageTask(String taskType) {
        return MediaGenTask.TYPE_TEXT2IMAGE.equals(taskType)
                || MediaGenTask.TYPE_IMAGE2IMAGE.equals(taskType);
    }

    /**
     * 图片任务处理：同步生图（一次返全量 url）→ 逐张下载落盘 → 写 result_meta → 按张计费。
     * 同步协议无 arkTaskId/轮询；失败走 markFailed（与视频 catch 同一路径）。
     */
    private boolean processImage(MediaGenTask task) {
        Long taskId = task.getId();
        MediaImageRequest request = buildImageRequest(task);
        MediaImageResult result = imageProvider.generate(request);
        if (!result.isSuccess()) {
            txService.markFailed(taskId, result.getErrorMsg() != null ? result.getErrorMsg() : "生图失败");
            log.warn("生图失败 taskId={} model={} reason={}", taskId, request.getModel(), result.getErrorMsg());
            return false;
        }
        return handleImageSucceeded(task, result, request);
    }

    /**
     * 图片成功处理：逐张下载 Ark 24h 临时 URL → stored_files(source=MEDIA) →
     * 收集 fileIds 写 result_meta → 按 {@code usage.generated_images} 计费扣减。
     *
     * <p>逐张下载隔离：任一张失败即整体 FAILED（部分图不落盘则 result_meta 不完整，前端无法展示，
     * 不留半成功态）。计费与视频同口径：markImageSucceeded 前扣，落库失败退款。
     */
    private boolean handleImageSucceeded(MediaGenTask task, MediaImageResult result, MediaImageRequest request) {
        Long taskId = task.getId();
        List<String> urls = result.getImageUrls();
        List<String> fileIds = new java.util.ArrayList<>(urls.size());
        try {
            for (int i = 0; i < urls.size(); i++) {
                String hint = "img-task-" + taskId + "-" + i;
                fileIds.add(mediaStorageService.downloadImageAndStore(urls.get(i), task.getUserId(), hint));
            }
        } catch (Exception e) {
            log.error("图片下载落盘失败 taskId={}: {}", taskId, e.getMessage(), e);
            txService.markDownloadFailed(taskId, "图片下载落盘失败: " + rootMessage(e));
            return false;
        }
        Integer imageCount = result.getGeneratedImages() != null ? result.getGeneratedImages() : fileIds.size();
        Long outputTokens = result.getOutputTokens();
        String resultMeta = buildImageResultMeta(fileIds, imageCount, outputTokens);
        // 按张计费扣减（返回实扣积分；null=未扣/系统调用/计费失败均吞不抛）
        BigDecimal chargedPoints = mediaBillingService.chargeMedia(task.getUserId(), task.getProviderId(),
                task.getModel(), LlmUsageLogEntity.KIND_IMAGE, null, 0, imageCount,
                LlmUsageLogEntity.STATUS_SUCCESS, taskId);
        try {
            txService.markImageSucceeded(taskId, resultMeta, imageCount, MediaGenTask.FLAG_SUCCESS);
        } catch (RuntimeException e) {
            // 扣了却落库失败：撤销已扣（防对账黑洞），再抛交 process()→markFailed
            mediaBillingService.refundMedia(task.getUserId(), chargedPoints, LlmUsageLogEntity.KIND_IMAGE, taskId);
            throw e;
        }
        log.info("生图任务成功 taskId={} model={} 张数={} fileIds={}",
                taskId, task.getModel(), imageCount, fileIds.size());
        return true;
    }

    /** result_meta JSON：{imageFileIds:[], generatedImages, outputTokens}（前端列表展示 + 逐张下载/入库用）。 */
    private String buildImageResultMeta(List<String> fileIds, Integer generatedImages, Long outputTokens) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("imageFileIds", fileIds);
        meta.put("generatedImages", generatedImages);
        meta.put("outputTokens", outputTokens);
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            throw new IllegalStateException("result_meta 序列化失败", e);
        }
    }

    /**
     * 从 requestConfig 解析图片参数 + 参考图 file_id → data URI（Ark image 入参）。
     * 图片 requestConfig 由 submitImage 落库：prompt/size/outputFormat/watermark/guidanceScale/
     * optimizeMode/sequential/maxImages/webSearch/refFileIds。
     */
    private MediaImageRequest buildImageRequest(MediaGenTask task) {
        String prompt = null, size = null, outputFormat = null, optimizeMode = null, sequential = null;
        Double guidanceScale = null;
        Integer maxImages = null;
        Boolean watermark = null, webSearch = null;
        List<String> refFileIds = new java.util.ArrayList<>();
        try {
            JsonNode cfg = objectMapper.readTree(task.getRequestConfig());
            prompt = cfg.path("prompt").asText(null);
            size = cfg.path("size").asText(null);
            outputFormat = cfg.path("outputFormat").asText(null);
            optimizeMode = cfg.path("optimizeMode").asText(null);
            sequential = cfg.path("sequential").asText(null);
            if (cfg.path("guidanceScale").isNumber()) guidanceScale = cfg.path("guidanceScale").asDouble();
            if (cfg.path("maxImages").isNumber()) maxImages = cfg.path("maxImages").asInt();
            if (cfg.has("watermark")) watermark = cfg.path("watermark").asBoolean();
            if (cfg.has("webSearch")) webSearch = cfg.path("webSearch").asBoolean();
            for (JsonNode f : cfg.path("refFileIds")) {
                String fid = f.asText(null);
                if (fid != null && !fid.isBlank()) refFileIds.add(fid);
            }
        } catch (Exception e) {
            log.warn("解析图片 requestConfig 失败 taskId={}: {}", task.getId(), e.getMessage());
        }
        // 参考图 file_id → data URI（图生图/多图融合；纯文生图 refFileIds 空）
        List<String> refDataUris = new java.util.ArrayList<>();
        for (String fid : refFileIds) {
            if (task.getUserId() == null) break;
            try {
                refDataUris.add(mediaStorageService.readAsDataUri(fid, task.getUserId()));
            } catch (Exception e) {
                log.warn("参考图读取失败 taskId={} fileId={}: {}", task.getId(), fid, e.getMessage());
                throw new IllegalArgumentException("参考图读取失败: " + rootMessage(e));
            }
        }
        return MediaImageRequest.builder()
                .model(task.getModel())
                .providerId(task.getProviderId())
                .prompt(prompt)
                .size(size)
                .outputFormat(outputFormat)
                .watermark(watermark)
                .guidanceScale(guidanceScale)
                .optimizeMode(optimizeMode)
                .sequential(sequential)
                .maxImages(maxImages)
                .webSearch(webSearch)
                .refImageUrls(refDataUris.isEmpty() ? null : refDataUris)
                .build();
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
        return buildRequest(task, true);
    }

    private MediaGenRequest buildRequest(MediaGenTask task, boolean resolveAttachments) {
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
        if (resolveAttachments && !attachments.isEmpty() && task.getUserId() != null) {
            List<MediaGenRequest.ResolvedAttachment> resolved = new java.util.ArrayList<>(attachments.size());
            for (String[] pair : attachments) {
                try {
                    resolved.add(MediaGenRequest.ResolvedAttachment.builder()
                            .kind(pair[1])
                            .fileId(pair[0])
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
        if (resolveAttachments && refFileId != null && !refFileId.isBlank() && task.getUserId() != null) {
            try {
                b.refImageUrl(mediaStorageService.readAsDataUri(refFileId, task.getUserId()));
                b.refFileId(refFileId);
                // C2：参考帧位置（last=尾帧 role:last_frame；first/默认=首帧裸 image_url）
                b.frameRole(frameRole);
            } catch (Exception e) {
                log.warn("参考图读取失败 taskId={} refFileId={}: {}", task.getId(), refFileId, e.getMessage());
                throw new IllegalArgumentException("参考图读取失败: " + rootMessage(e));
            }
        }
        return b.build();
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        if (m == null) m = c.getClass().getSimpleName();
        return m.length() > MAX_ERROR_LEN ? m.substring(0, MAX_ERROR_LEN) : m;
    }
}
