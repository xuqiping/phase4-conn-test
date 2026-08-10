package com.superprogrammer.media.edit.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.media.edit.entity.MediaEditTask;
import com.superprogrammer.media.edit.mapper.MediaEditTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 视频剪辑任务的全部 DB 写操作（独立 bean，@Transactional 经 Spring 代理生效）。
 *
 * <p>事务粒度刻意小：claim / markSucceeded / markFailed 各自短事务，FFmpeg 渲染（分钟级阻塞）
 * 必须在事务外，不能占着 DB 连接/事务（同 {@code MediaGenTaskTxService} 设计）。
 *
 * <p>崩溃恢复：claim 认领 {@code PENDING} + {@code RUNNING 且锁过期} 的行（FOR UPDATE SKIP LOCKED），
 * 多 worker 安全；服务重启后下次 poll 自动续跑未完任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaEditTaskTxService {

    private final MediaEditTaskMapper taskMapper;

    /**
     * 认领一批待处理任务（PENDING 或 RUNNING 且锁过期）。认领即置 RUNNING + attempt+1 + lockedUntil。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<MediaEditTask> claimBatch(int limit, int lockMinutes) {
        OffsetDateTime now = OffsetDateTime.now();
        LambdaQueryWrapper<MediaEditTask> w = new LambdaQueryWrapper<>();
        w.and(q -> q.eq(MediaEditTask::getStatus, MediaEditTask.STATUS_PENDING)
                        .or().eq(MediaEditTask::getStatus, MediaEditTask.STATUS_RUNNING))
                .and(q -> q.isNull(MediaEditTask::getLockedUntil)
                        .or().lt(MediaEditTask::getLockedUntil, now))
                .orderByAsc(MediaEditTask::getCreatedAt)
                .last("LIMIT " + limit + " FOR UPDATE SKIP LOCKED");
        List<MediaEditTask> tasks = taskMapper.selectList(w);
        if (tasks.isEmpty()) {
            return List.of();
        }
        OffsetDateTime lockUntil = OffsetDateTime.now().plusMinutes(lockMinutes);
        for (MediaEditTask t : tasks) {
            int attempt = (t.getAttempt() == null ? 0 : t.getAttempt()) + 1;
            t.setStatus(MediaEditTask.STATUS_RUNNING);
            t.setAttempt(attempt);
            t.setLockedUntil(lockUntil);
            t.setUpdatedAt(now);
            taskMapper.updateById(t);
        }
        return tasks;
    }

    /** 任务成功：写 result_file_id（渲染产物 fileId）。 */
    @Transactional(rollbackFor = Exception.class)
    public void markSucceeded(Long taskId, String resultFileId) {
        LambdaUpdateWrapper<MediaEditTask> u = new LambdaUpdateWrapper<>();
        u.eq(MediaEditTask::getId, taskId)
                .set(MediaEditTask::getStatus, MediaEditTask.STATUS_SUCCEEDED)
                .set(MediaEditTask::getResultFileId, resultFileId)
                .set(MediaEditTask::getLockedUntil, null)
                .set(MediaEditTask::getUpdatedAt, OffsetDateTime.now());
        taskMapper.update(null, u);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markFailed(Long taskId, String errorMsg) {
        LambdaUpdateWrapper<MediaEditTask> u = new LambdaUpdateWrapper<>();
        u.eq(MediaEditTask::getId, taskId)
                .set(MediaEditTask::getStatus, MediaEditTask.STATUS_FAILED)
                .set(MediaEditTask::getErrorMsg, truncate(errorMsg, 256))
                .set(MediaEditTask::getLockedUntil, null)
                .set(MediaEditTask::getUpdatedAt, OffsetDateTime.now());
        taskMapper.update(null, u);
    }

    /** 落盘失败（渲染成功但产物存盘失败）：保留 spec 便于人工/后续重试。 */
    @Transactional(rollbackFor = Exception.class)
    public void markDownloadFailed(Long taskId, String errorMsg) {
        LambdaUpdateWrapper<MediaEditTask> u = new LambdaUpdateWrapper<>();
        u.eq(MediaEditTask::getId, taskId)
                .set(MediaEditTask::getStatus, MediaEditTask.STATUS_DOWNLOAD_FAILED)
                .set(MediaEditTask::getErrorMsg, truncate(errorMsg, 256))
                .set(MediaEditTask::getLockedUntil, null)
                .set(MediaEditTask::getUpdatedAt, OffsetDateTime.now());
        taskMapper.update(null, u);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
