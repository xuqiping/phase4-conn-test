package com.superprogrammer.media.edit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.media.edit.dto.MediaEditTaskVO;
import com.superprogrammer.media.edit.entity.MediaEditTask;
import com.superprogrammer.media.edit.mapper.MediaEditTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 视频剪辑任务查询（读侧）：详情/列表，ownership 硬过滤。
 *
 * <p>归属规则：普通用户只能查/下载自己的任务（{@code WHERE user_id=current}）；admin 旁路（看全量，
 * 含系统任务 user_id=null）。SUCCEEDED 任务的 videoUrl 指向下载端点（不暴露内部存储路径）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaEditQueryService {

    private final MediaEditTaskMapper taskMapper;
    private final ObjectMapper objectMapper;

    public MediaEditTaskVO get(Long id, Long userId, boolean admin) {
        MediaEditTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        ensureOwnership(task, userId, admin);
        return toVO(task, admin || owns(task, userId));
    }

    public List<MediaEditTaskVO> list(Long userId, boolean admin, Integer limit) {
        int size = limit == null || limit < 1 || limit > 100 ? 50 : limit;
        LambdaQueryWrapper<MediaEditTask> w = new LambdaQueryWrapper<>();
        if (!admin) {
            w.eq(MediaEditTask::getUserId, userId);
        }
        w.orderByDesc(MediaEditTask::getCreatedAt).last("LIMIT " + size);
        return taskMapper.selectList(w).stream()
                .map(t -> toVO(t, admin || owns(t, userId)))
                .collect(Collectors.toList());
    }

    public MediaEditTask loadForDownload(Long id, Long userId, boolean admin) {
        MediaEditTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        ensureOwnership(task, userId, admin);
        if (!MediaEditTask.STATUS_SUCCEEDED.equals(task.getStatus()) || task.getResultFileId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务尚未渲染完成");
        }
        return task;
    }

    private void ensureOwnership(MediaEditTask task, Long userId, boolean admin) {
        if (!admin && !owns(task, userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该任务");
        }
    }

    private boolean owns(MediaEditTask task, Long userId) {
        return userId != null && userId.equals(task.getUserId());
    }

    private MediaEditTaskVO toVO(MediaEditTask task, boolean mayAccessFile) {
        int clipsCount = 0;
        boolean hasBgm = false;
        int subs = 0;
        try {
            JsonNode spec = objectMapper.readTree(task.getEditSpec());
            JsonNode tracks = spec.path("tracks");
            if (tracks.isArray() && !tracks.isEmpty()) {
                // V2 多轨：VIDEO 段数 / 是否有 AUDIO 轨 / TEXT 条数
                for (JsonNode tr : tracks) {
                    String type = tr.path("type").asText("");
                    if ("VIDEO".equalsIgnoreCase(type)) {
                        clipsCount += tr.path("segments").size();
                    } else if ("AUDIO".equalsIgnoreCase(type)) {
                        hasBgm = true;
                    } else if ("TEXT".equalsIgnoreCase(type)) {
                        subs += tr.path("texts").size();
                    }
                }
            } else {
                // V1 旧任务回退
                clipsCount = spec.path("clips").size();
                JsonNode audio = spec.path("audio");
                hasBgm = audio.has("fileId") && !audio.path("fileId").asText("").isBlank();
                subs = spec.path("texts").size();
            }
        } catch (Exception e) {
            log.warn("VO 解析 edit_spec 失败 taskId={}: {}", task.getId(), e.getMessage());
        }
        String videoUrl = (mayAccessFile && MediaEditTask.STATUS_SUCCEEDED.equals(task.getStatus())
                && task.getResultFileId() != null)
                ? "/api/media/edit/tasks/" + task.getId() + "/download"
                : null;
        return MediaEditTaskVO.builder()
                .id(task.getId())
                .status(task.getStatus())
                .errorMsg(task.getErrorMsg())
                .clipsCount(clipsCount)
                .hasBgm(hasBgm)
                .subtitlesCount(subs)
                .videoUrl(videoUrl)
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }
}
