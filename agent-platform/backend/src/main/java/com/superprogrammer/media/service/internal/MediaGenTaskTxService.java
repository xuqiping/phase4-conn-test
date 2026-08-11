package com.superprogrammer.media.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

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

    /** 任务成功：写 result_file_id + tokens_cost + status_flag（Step4/5 填参）。 */
    @Transactional(rollbackFor = Exception.class)
    public void markSucceeded(Long taskId, String resultFileId, Integer tokensCost, String statusFlag) {
        LambdaUpdateWrapper<MediaGenTask> u = new LambdaUpdateWrapper<>();
        u.eq(MediaGenTask::getId, taskId)
                .set(MediaGenTask::getStatus, MediaGenTask.STATUS_SUCCEEDED)
                .set(MediaGenTask::getResultFileId, resultFileId)
                .set(MediaGenTask::getTokensCost, tokensCost)
                .set(MediaGenTask::getStatusFlag, statusFlag)
                .set(MediaGenTask::getLockedUntil, null)
                .set(MediaGenTask::getUpdatedAt, OffsetDateTime.now());
        taskMapper.update(null, u);
    }

    /**
     * 图片任务成功：写 result_meta（多图 fileId 元数据 JSONB）+ tokens_cost + status_flag。
     * 与 {@link #markSucceeded} 区别：图片一次返 N 张，无单 result_file_id，多图信息落 result_meta。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markImageSucceeded(Long taskId, String resultMeta, Integer tokensCost, String statusFlag) {
        // 走 mapper @Update 显式 ::jsonb 强转（见 MediaGenTaskMapper.markImageSucceeded javadoc）：
        // LambdaUpdateWrapper.set 不带 typeHandler，String 直入 jsonb 列会报类型不匹配。
        taskMapper.markImageSucceeded(taskId, resultMeta, tokensCost, statusFlag,
                MediaGenTask.STATUS_SUCCEEDED, OffsetDateTime.now());
    }

    @Transactional(rollbackFor = Exception.class)
    public void markFailed(Long taskId, String errorMsg) {
        LambdaUpdateWrapper<MediaGenTask> u = new LambdaUpdateWrapper<>();
        u.eq(MediaGenTask::getId, taskId)
                .set(MediaGenTask::getStatus, MediaGenTask.STATUS_FAILED)
                .set(MediaGenTask::getStatusFlag, MediaGenTask.FLAG_ESTIMATED)
                .set(MediaGenTask::getErrorMsg, truncate(errorMsg, 256))
                .set(MediaGenTask::getLockedUntil, null)
                .set(MediaGenTask::getUpdatedAt, OffsetDateTime.now());
        taskMapper.update(null, u);
    }

    /** 下载失败：保留 ark_task_id 便于人工/后续重试（worker 留重试入口）。 */
    @Transactional(rollbackFor = Exception.class)
    public void markDownloadFailed(Long taskId, String errorMsg) {
        LambdaUpdateWrapper<MediaGenTask> u = new LambdaUpdateWrapper<>();
        u.eq(MediaGenTask::getId, taskId)
                .set(MediaGenTask::getStatus, MediaGenTask.STATUS_DOWNLOAD_FAILED)
                .set(MediaGenTask::getErrorMsg, truncate(errorMsg, 256))
                .set(MediaGenTask::getLockedUntil, null)
                .set(MediaGenTask::getUpdatedAt, OffsetDateTime.now());
        taskMapper.update(null, u);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
