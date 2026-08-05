package com.superprogrammer.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.media.dto.MediaTaskVO;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 媒体任务查询（读侧）：详情/列表，ownership 硬过滤。
 *
 * <p>归属规则：普通用户只能查/下载自己的任务（{@code WHERE user_id=current}）；admin 旁路（看全量，
 * 含系统任务 user_id=null）。SUCCEEDED 任务的 videoUrl 指向下载端点（不暴露 Ark 临时 URL）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaGenQueryService {

    private final MediaGenTaskMapper taskMapper;
    private final ObjectMapper objectMapper;

    public MediaTaskVO get(Long id, Long userId, boolean admin) {
        MediaGenTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        ensureOwnership(task, userId, admin);
        return toVO(task, admin || owns(task, userId));
    }

    public List<MediaTaskVO> list(Long userId, boolean admin, Integer limit) {
        int size = limit == null || limit < 1 || limit > 100 ? 50 : limit;
        LambdaQueryWrapper<MediaGenTask> w = new LambdaQueryWrapper<>();
        if (!admin) {
            w.eq(MediaGenTask::getUserId, userId);
        }
        w.orderByDesc(MediaGenTask::getCreatedAt).last("LIMIT " + size);
        return taskMapper.selectList(w).stream()
                .map(t -> toVO(t, admin || owns(t, userId)))
                .collect(Collectors.toList());
    }

    public MediaGenTask loadForDownload(Long id, Long userId, boolean admin) {
        MediaGenTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        ensureOwnership(task, userId, admin);
        if (!MediaGenTask.STATUS_SUCCEEDED.equals(task.getStatus()) || task.getResultFileId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务尚未生成完成");
        }
        return task;
    }

    private void ensureOwnership(MediaGenTask task, Long userId, boolean admin) {
        if (!admin && !owns(task, userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该任务");
        }
    }

    private boolean owns(MediaGenTask task, Long userId) {
        return userId != null && userId.equals(task.getUserId());
    }

    private MediaTaskVO toVO(MediaGenTask task, boolean mayAccessFile) {
        String prompt = null;
        Integer duration = null;
        String resolution = null;
        try {
            JsonNode cfg = objectMapper.readTree(task.getRequestConfig());
            prompt = cfg.path("prompt").asText(null);
            if (cfg.path("duration").isNumber()) duration = cfg.path("duration").asInt();
            resolution = cfg.path("resolution").asText(null);
        } catch (Exception e) {
            log.warn("VO 解析 requestConfig 失败 taskId={}: {}", task.getId(), e.getMessage());
        }
        String videoUrl = (mayAccessFile && MediaGenTask.STATUS_SUCCEEDED.equals(task.getStatus())
                && task.getResultFileId() != null)
                ? "/api/media/tasks/" + task.getId() + "/download"
                : null;
        String resultFileId = (mayAccessFile && MediaGenTask.STATUS_SUCCEEDED.equals(task.getStatus()))
                ? task.getResultFileId() : null;
        return MediaTaskVO.builder()
                .id(task.getId())
                .status(task.getStatus())
                .statusFlag(task.getStatusFlag())
                .taskType(task.getTaskType())
                .model(task.getModel())
                .prompt(prompt)
                .duration(duration)
                .resolution(resolution)
                .tokensCost(task.getTokensCost())
                .errorMsg(task.getErrorMsg())
                .videoUrl(videoUrl)
                .resultFileId(resultFileId)
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }
}
