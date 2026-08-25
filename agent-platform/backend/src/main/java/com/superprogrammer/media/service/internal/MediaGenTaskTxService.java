package com.superprogrammer.media.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.billing.service.MediaBillingService;
import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 媒体生成任务的全部 DB 写操作（独立 bean，@Transactional 经 Spring 代理生效）。
 *
 * <p>事务粒度刻意小：claim / markSucceeded / markFailed 各自短事务，Ark 轮询（秒~分钟级阻塞）
 * 必须在事务外，不能占着 DB 连接/事务（同 IndexJobTxService 设计）。
 *
 * <p>崩溃恢复：claim 认领 {@code PENDING} + {@code RUNNING 且锁过期} 的行（FOR UPDATE SKIP LOCKED），
 * 多 worker 安全；服务重启后下次 poll 自动续跑未完任务（照抄 knowledge_index_jobs claim 模式）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaGenTaskTxService {

    private final MediaGenTaskMapper taskMapper;
    /** 审计：媒体终态失败落库（问题修复 #1，markFailed/markDownloadFailed 咽喉覆盖全部失败路径）。 */
    private final AuditLogService auditLogService;
    /** 7x（V155）：失败终态退预扣。字段注入+required=false——单测手工 new 不传不炸。 */
    @Autowired(required = false)
    private MediaBillingService mediaBillingService;

    /**
     * 认领一批待处理任务（PENDING 或 RUNNING 且锁过期）。认领即置 RUNNING + attempt+1 + lockedUntil。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<MediaGenTask> claimBatch(int limit, int lockMinutes) {
        OffsetDateTime now = OffsetDateTime.now();
        LambdaQueryWrapper<MediaGenTask> w = new LambdaQueryWrapper<>();
        w.and(q -> q.eq(MediaGenTask::getStatus, MediaGenTask.STATUS_PENDING)
                        .or().eq(MediaGenTask::getStatus, MediaGenTask.STATUS_RUNNING))
                .and(q -> q.isNull(MediaGenTask::getLockedUntil)
                        .or().lt(MediaGenTask::getLockedUntil, now))
                .orderByAsc(MediaGenTask::getCreatedAt)
                .last("LIMIT " + limit + " FOR UPDATE SKIP LOCKED");
        List<MediaGenTask> tasks = taskMapper.selectList(w);
        if (tasks.isEmpty()) {
            return List.of();
        }
        OffsetDateTime lockUntil = OffsetDateTime.now().plusMinutes(lockMinutes);
        for (MediaGenTask t : tasks) {
            int attempt = (t.getAttempt() == null ? 0 : t.getAttempt()) + 1;
            t.setStatus(MediaGenTask.STATUS_RUNNING);
            t.setAttempt(attempt);
            t.setLockedUntil(lockUntil);
            t.setUpdatedAt(now);
            taskMapper.updateById(t);
        }
        return tasks;
    }

    /** 记录 Ark 任务 id（createTask 后），便于崩溃恢复续轮询。 */
    @Transactional(rollbackFor = Exception.class)
    public void setArkTaskId(Long taskId, String arkTaskId) {
        LambdaUpdateWrapper<MediaGenTask> u = new LambdaUpdateWrapper<>();
        u.eq(MediaGenTask::getId, taskId)
                .set(MediaGenTask::getArkTaskId, arkTaskId)
                .set(MediaGenTask::getUpdatedAt, OffsetDateTime.now());
        taskMapper.update(null, u);
    }

    /** 续锁（长时间轮询中防被其他 worker 重认领）。 */
    @Transactional(rollbackFor = Exception.class)
    public void renewLock(Long taskId, int lockMinutes) {
        LambdaUpdateWrapper<MediaGenTask> u = new LambdaUpdateWrapper<>();
        u.eq(MediaGenTask::getId, taskId)
                .set(MediaGenTask::getLockedUntil, OffsetDateTime.now().plusMinutes(lockMinutes))
                .set(MediaGenTask::getUpdatedAt, OffsetDateTime.now());
        taskMapper.update(null, u);
    }

    /** RUNNING/PENDING 仍未终态：把 locked_until 当作下一次可认领时间，当前 worker 立即归还线程。 */
    @Transactional(rollbackFor = Exception.class)
    public void scheduleNextQuery(Long taskId, long delayMs) {
        LambdaUpdateWrapper<MediaGenTask> u = new LambdaUpdateWrapper<>();
        u.eq(MediaGenTask::getId, taskId)
                .set(MediaGenTask::getStatus, MediaGenTask.STATUS_RUNNING)
                .set(MediaGenTask::getLockedUntil, OffsetDateTime.now().plusNanos(delayMs * 1_000_000L))
                .set(MediaGenTask::getUpdatedAt, OffsetDateTime.now());
        taskMapper.update(null, u);
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveProviderRequestSnapshot(Long taskId, String snapshot) {
        taskMapper.saveProviderRequestSnapshot(taskId, snapshot, OffsetDateTime.now());
    }

    /**
     * 任务成功：写 result_file_id + tokens_cost + status_flag（Step4/5 填参）。
     *
     * @return 终态迁移是否真正落库（C3 并发闸释放守卫：WHERE status IN(PENDING,RUNNING) 影响 0
     *         = 已终态重复回调 → worker 不再 release，防双 DECR 超卖）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markSucceeded(Long taskId, String resultFileId, Integer tokensCost, String statusFlag) {
        LambdaUpdateWrapper<MediaGenTask> u = new LambdaUpdateWrapper<>();
        u.eq(MediaGenTask::getId, taskId)
                .in(MediaGenTask::getStatus, MediaGenTask.STATUS_PENDING, MediaGenTask.STATUS_RUNNING)
                .set(MediaGenTask::getStatus, MediaGenTask.STATUS_SUCCEEDED)
                .set(MediaGenTask::getResultFileId, resultFileId)
                .set(MediaGenTask::getTokensCost, tokensCost)
                .set(MediaGenTask::getStatusFlag, statusFlag)
                .set(MediaGenTask::getLockedUntil, null)
                .set(MediaGenTask::getUpdatedAt, OffsetDateTime.now());
        return taskMapper.update(null, u) > 0;
    }

    /**
     * 图片任务成功：写 result_meta（多图 fileId 元数据 JSONB）+ tokens_cost + status_flag。
     * 与 {@link #markSucceeded} 区别：图片一次返 N 张，无单 result_file_id，多图信息落 result_meta。
     *
     * @return 终态迁移是否真正落库（同 {@link #markSucceeded} 释放守卫语义）。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markImageSucceeded(Long taskId, String resultMeta, Integer tokensCost, String statusFlag) {
        // 走 mapper @Update 显式 ::jsonb 强转（见 MediaGenTaskMapper.markImageSucceeded javadoc）：
        // LambdaUpdateWrapper.set 不带 typeHandler，String 直入 jsonb 列会报类型不匹配。
        return taskMapper.markImageSucceeded(taskId, resultMeta, tokensCost, statusFlag,
                MediaGenTask.STATUS_SUCCEEDED, OffsetDateTime.now()) > 0;
    }

    /**
     * 任务失败终态。
     *
     * @return 终态迁移是否真正落库（同 {@link #markSucceeded} 释放守卫语义）。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markFailed(Long taskId, String errorMsg) {
        LambdaUpdateWrapper<MediaGenTask> u = new LambdaUpdateWrapper<>();
        u.eq(MediaGenTask::getId, taskId)
                .in(MediaGenTask::getStatus, MediaGenTask.STATUS_PENDING, MediaGenTask.STATUS_RUNNING)
                .set(MediaGenTask::getStatus, MediaGenTask.STATUS_FAILED)
                .set(MediaGenTask::getStatusFlag, MediaGenTask.FLAG_ESTIMATED)
                .set(MediaGenTask::getErrorMsg, truncate(errorMsg, 256))
                .set(MediaGenTask::getLockedUntil, null)
                .set(MediaGenTask::getUpdatedAt, OffsetDateTime.now());
        boolean transitioned = taskMapper.update(null, u) > 0;
        if (transitioned) {
            refundHoldQuietly(taskId);
        }
        auditFail(taskId, "gen_fail", truncate(errorMsg, 200));
        return transitioned;
    }

    /**
     * 下载失败：保留 ark_task_id 便于人工/后续重试（worker 留重试入口）。
     *
     * @return 终态迁移是否真正落库（同 {@link #markSucceeded} 释放守卫语义）。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markDownloadFailed(Long taskId, String errorMsg) {
        LambdaUpdateWrapper<MediaGenTask> u = new LambdaUpdateWrapper<>();
        u.eq(MediaGenTask::getId, taskId)
                .in(MediaGenTask::getStatus, MediaGenTask.STATUS_PENDING, MediaGenTask.STATUS_RUNNING)
                .set(MediaGenTask::getStatus, MediaGenTask.STATUS_DOWNLOAD_FAILED)
                .set(MediaGenTask::getErrorMsg, truncate(errorMsg, 256))
                .set(MediaGenTask::getLockedUntil, null)
                .set(MediaGenTask::getUpdatedAt, OffsetDateTime.now());
        boolean transitioned = taskMapper.update(null, u) > 0;
        if (transitioned) {
            refundHoldQuietly(taskId);
        }
        auditFail(taskId, "download_failed", truncate(errorMsg, 200));
        return transitioned;
    }

    /**
     * 7x（V155）：失败终态退预扣（全额退 kind-HOLD 腿）。hold_applied=false（存量任务）/未注入计费 → 跳过。
     * 幂等键与 worker 落库失败撤销的预扣腿相同（media-hold-refund-{taskId}）——两条失败路径互斥，
     * 撞键即幂等跳过，绝不双退。
     */
    private void refundHoldQuietly(Long taskId) {
        if (mediaBillingService == null) {
            return;
        }
        try {
            MediaGenTask task = taskMapper.selectById(taskId);
            if (task == null || !Boolean.TRUE.equals(task.getHoldApplied())) {
                return;
            }
            String kind = (MediaGenTask.TYPE_TEXT2IMAGE.equals(task.getTaskType())
                    || MediaGenTask.TYPE_IMAGE2IMAGE.equals(task.getTaskType()))
                    ? LlmUsageLogEntity.KIND_IMAGE : LlmUsageLogEntity.KIND_VIDEO;
            mediaBillingService.refundMediaHold(task.getUserId(), task.getEstimatedCost(), kind,
                    taskId, task.getProjectGroupId());
        } catch (Exception e) {
            log.warn("媒体失败退预扣异常(吞) taskId={}: {}", taskId, e.toString());
        }
    }

    /**
     * 问题修复 #1：媒体终态失败审计。按 taskType 判 image/video 选 action，detail 带 model+reason
     * （不泄露 OSS URL/token）。task 不存在（已删）则跳过。审计异步 fire-and-forget，不影响本事务。
     */
    private void auditFail(Long taskId, String reasonPrefix, String reason) {
        MediaGenTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        boolean image = MediaGenTask.TYPE_TEXT2IMAGE.equals(task.getTaskType())
                || MediaGenTask.TYPE_IMAGE2IMAGE.equals(task.getTaskType());
        String action = (image ? "image_gen_fail" : "video_gen_fail");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("model", task.getModel());
        detail.put("kind", image ? "IMAGE" : "VIDEO");
        detail.put("reason", reasonPrefix + ": " + reason);
        try {
            String detailJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(detail);
            auditLogService.recordTask("media", action, "media_gen_task", String.valueOf(taskId),
                    task.getUserId(), null, task.getClientIp(), detailJson, AuditLogEntity.RESULT_FAIL);
        } catch (Exception e) {
            log.warn("媒体失败审计序列化失败(已跳过) taskId={} : {}", taskId, e.toString());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
