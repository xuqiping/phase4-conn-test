package com.superprogrammer.media.edit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.media.edit.config.MediaEditProperties;
import com.superprogrammer.media.edit.dto.EditSpec;
import com.superprogrammer.media.edit.dto.EditSpecNormalizer;
import com.superprogrammer.media.edit.entity.MediaEditTask;
import com.superprogrammer.media.edit.mapper.MediaEditTaskMapper;
import com.superprogrammer.media.edit.provider.MediaEditProvider;
import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.media.edit.service.internal.MediaEditTaskTxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * 视频剪辑渲染 worker（异步 FFmpeg 渲染至终态）。
 *
 * <p>纯 poll 模式（照抄 {@code MediaGenTaskWorker} / {@code IndexJobWorker}）：{@link #poll} 定时认领
 * PENDING + 锁过期 RUNNING（FOR UPDATE SKIP LOCKED，多 worker 安全），分发到 {@code mediaEditExecutor}
 * 异步消费。服务重启后下次 poll 自动续跑未完任务（崩溃恢复免费）。
 *
 * <p>process(task)：① 反序列 edit_spec → {@link EditSpec} ② 建按 taskId 隔离的 temp 目录 ③ 逐素材经
 * {@code FileStorageService.load}（admin 旁路，归属已在提交期校验）copy 到 temp ④ {@link MediaEditProvider#render}
 * ⑤ {@link MediaEditStorageService#store} 落 stored_files(source=EDIT) → markSucceeded ⑥ finally 删 temp。
 *
 * <p>FFmpeg 渲染阻塞（分钟级）必须在事务外，故本类无 @Transactional，DB 写经 txService 代理。
 * 时序不变式：{@code lockMinutes*60 > renderTimeoutSeconds}（见 MediaEditProperties），保证单次渲染在锁窗内完成，防长渲染被重认领。
 */
@Slf4j
@Component
public class MediaEditTaskWorker {

    private static final int BATCH = 4;
    private static final int MAX_ERROR_LEN = 200;
    private static final Duration STALE_TEMP = Duration.ofHours(2);

    private final MediaEditTaskTxService txService;
    private final MediaEditTaskMapper taskMapper;
    private final MediaEditProvider provider;
    private final MediaEditStorageService storageService;
    private final FileStorageService fileStorageService;
    private final MediaEditProperties properties;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    /** 剪辑终态/耗时指标（media.task.terminal/duration, kind=edit）。 */
    private final BizMetrics bizMetrics;
    /** 审计：剪辑终态成功落库（问题修复 #1，本地 FFmpeg 无 model/token/积分，detail 仅 kind+mediaCount）。 */
    private final AuditLogService auditLogService;

    public MediaEditTaskWorker(MediaEditTaskTxService txService,
                               MediaEditTaskMapper taskMapper,
                               MediaEditProvider provider,
                               MediaEditStorageService storageService,
                               FileStorageService fileStorageService,
                               MediaEditProperties properties,
                               ObjectMapper objectMapper,
                               @Qualifier("mediaEditExecutor") Executor executor,
                               BizMetrics bizMetrics,
                               AuditLogService auditLogService) {
        this.txService = txService;
        this.taskMapper = taskMapper;
        this.provider = provider;
        this.storageService = storageService;
        this.fileStorageService = fileStorageService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.bizMetrics = bizMetrics;
        this.auditLogService = auditLogService;
    }

    @Scheduled(fixedDelayString = "${media.edit.poll-ms:5000}")
    public void poll() {
        try {
            List<MediaEditTask> tasks = txService.claimBatch(BATCH, properties.getLockMinutes());
            if (tasks.isEmpty()) {
                return;
            }
            log.debug("认领剪辑任务 {} 条", tasks.size());
            for (MediaEditTask task : tasks) {
                executor.execute(() -> process(task.getId()));
            }
        } catch (Exception e) {
            log.error("剪辑任务轮询/认领失败: {}", e.getMessage(), e);
        }
    }

    private void process(Long taskId) {
        MediaEditTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        // 终态结果（每次处理正好记一次）：renderTask 到 markSucceeded 才置 true
        boolean succeeded = false;
        try {
            EditSpec spec;
            try {
                spec = objectMapper.readValue(task.getEditSpec(), EditSpec.class);
            } catch (Exception e) {
                log.error("剪辑任务 edit_spec 解析失败 taskId={}: {}", taskId, e.getMessage());
                txService.markFailed(taskId, "edit_spec 解析失败: " + rootMessage(e));
                return;
            }
            Path workDir;
            try {
                workDir = Files.createTempDirectory("edit-" + taskId + "-");
            } catch (IOException e) {
                log.error("剪辑任务建 temp 目录失败 taskId={}: {}", taskId, e.getMessage());
                txService.markFailed(taskId, "建 temp 目录失败: " + rootMessage(e));
                return;
            }
            try {
                succeeded = renderTask(task, spec, workDir);
            } catch (Exception e) {
                log.error("剪辑任务处理失败 taskId={}: {}", taskId, e.getMessage(), e);
                txService.markFailed(taskId, rootMessage(e));
            } finally {
                cleanupDir(workDir);
            }
        } finally {
            bizMetrics.mediaTaskTerminal(BizMetrics.MEDIA_EDIT,
                    succeeded ? BizMetrics.RESULT_SUCCESS : BizMetrics.RESULT_FAIL);
            if (task.getCreatedAt() != null) {
                bizMetrics.mediaTaskDuration(BizMetrics.MEDIA_EDIT,
                        Duration.between(task.getCreatedAt(), java.time.OffsetDateTime.now()));
            }
        }
    }

    /** 渲染主链；返回 true 仅当 markSucceeded（产物存盘失败/异常均 false）。 */
    private boolean renderTask(MediaEditTask task, EditSpec spec, Path workDir) throws Exception {
        Long taskId = task.getId();
        Long userId = task.getUserId();

        // ① 收集所有 fileId（V2 tracks.segments 或 V1 clips/audio，去重），copy 到 temp + probe 时长
        Set<String> fileIds = EditSpecNormalizer.collectFileIds(spec);
        Map<String, Path> mediaByFileId = new LinkedHashMap<>();
        Map<String, Double> durationByFileId = new HashMap<>();
        int idx = 0;
        for (String fid : fileIds) {
            Path dst = workDir.resolve("media-" + idx + extFor(fid));
            copyResource(fileStorageService.load(fid, userId, true), dst);
            mediaByFileId.put(fid, dst);
            durationByFileId.put(fid, provider.probeDurationSeconds(dst));
            idx++;
        }

        // ② normalize V1/V2 → V2（用 probe 时长解析缺省 trimEnd/target），统一新旧任务渲染路径
        EditSpec normalized = EditSpecNormalizer.normalize(spec, durationByFileId::get);

        // ③ 渲染（provider 内部有 renderTimeoutSeconds 进程超时）
        Path output = workDir.resolve("output.mp4");
        provider.render(normalized, mediaByFileId, output);

        // ④ 落盘 stored_files(source=EDIT)（失败单列 DOWNLOAD_FAILED，保留 spec 可重试）
        String resultFileId;
        try {
            resultFileId = storageService.store(output, userId, taskId);
        } catch (Exception e) {
            log.error("剪辑产物存盘失败 taskId={}: {}", taskId, e.getMessage());
            txService.markDownloadFailed(taskId, "产物存盘失败: " + rootMessage(e));
            return false;
        }
        txService.markSucceeded(taskId, resultFileId);
        log.info("剪辑任务成功 taskId={} media={}", taskId, mediaByFileId.size());
        auditEditSuccess(task, mediaByFileId.size());
        return true;
    }

    /**
     * 问题修复 #1：剪辑终态成功审计。与 edit_submit 行通过 taskId 关联（同 targetId）。
     * 本地 FFmpeg 渲染无 model/token/积分，detail 仅 kind+mediaCount。IP 取提交期盖戳。
     */
    private void auditEditSuccess(MediaEditTask task, int mediaCount) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", "EDIT");
        detail.put("mediaCount", mediaCount);
        try {
            String detailJson = objectMapper.writeValueAsString(detail);
            auditLogService.recordTask("media", "video_edit_success", "media_edit_task",
                    String.valueOf(task.getId()), task.getUserId(), null, task.getClientIp(),
                    detailJson, AuditLogEntity.RESULT_SUCCESS);
        } catch (Exception e) {
            log.warn("剪辑成功审计失败(已跳过) taskId={} : {}", task.getId(), e.toString());
        }
    }

    /** 兜底清理：worker 崩溃残留的 temp 目录（>2h）每日清一次，防磁盘涨爆。 */
    @Scheduled(fixedDelayString = "PT2H", initialDelayString = "PT10M")
    public void cleanupStaleTemp() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Instant cutoff = Instant.now().minus(STALE_TEMP);
        try (Stream<Path> dirs = Files.list(tmp)) {
            dirs.filter(p -> {
                        String name = p.getFileName().toString();
                        return Files.isDirectory(p) && name.startsWith("edit-");
                    })
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> cleanupDir(p));
        } catch (IOException e) {
            log.warn("扫描 temp 目录失败: {}", e.getMessage());
        }
    }

    private void cleanupDir(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignore) { /* 尽力而为 */ }
            });
        } catch (IOException e) {
            log.warn("删除 temp 目录失败 {}: {}", dir, e.getMessage());
        }
    }

    private void copyResource(Resource resource, Path dst) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            Files.copy(in, dst);
        }
    }

    /** 取素材原始扩展名（FFmpeg 按内容探测，扩展名仅兜底）；未知用 .mp4。 */
    private String extFor(String fileId) {
        try {
            StoredFileEntity meta = fileStorageService.findMeta(fileId);
            if (meta != null && meta.getOriginalName() != null) {
                String name = meta.getOriginalName();
                int dot = name.lastIndexOf('.');
                if (dot >= 0 && dot < name.length() - 1) {
                    return name.substring(dot).toLowerCase();
                }
            }
        } catch (Exception ignore) {
            // 兜底
        }
        return ".mp4";
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        if (m == null) m = c.getClass().getSimpleName();
        return m.length() > MAX_ERROR_LEN ? m.substring(0, MAX_ERROR_LEN) : m;
    }
}
