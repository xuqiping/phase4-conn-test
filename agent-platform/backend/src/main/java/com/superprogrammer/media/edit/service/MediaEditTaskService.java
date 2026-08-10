package com.superprogrammer.media.edit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.media.edit.config.MediaEditProperties;
import com.superprogrammer.media.edit.dto.EditSpec;
import com.superprogrammer.media.edit.entity.MediaEditTask;
import com.superprogrammer.media.edit.mapper.MediaEditTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 视频剪辑任务提交入口。
 *
 * <p>职责：总开关 + 结构校验（片段数/非空）+ 序列化 edit_spec + 建 PENDING 任务行 + 返回 taskId。
 * 不在此派发执行——交由 {@link MediaEditTaskWorker} 定时轮询认领（纯 poll 模式，照抄 media 生成：崩溃恢复免费）。
 *
 * <p>素材归属/格式/时长校验由 {@code MediaAssetService.validate} 在 controller 层 submit 前完成（单一职责）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaEditTaskService {

    private final MediaEditTaskMapper taskMapper;
    private final MediaEditProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 提交剪辑渲染任务。
     *
     * @param spec   剪辑意图（须已由 {@code MediaAssetService.validate} 规范化成 V2 并通过归属/格式/上限校验）
     * @param userId 提交用户（nullable：系统调用）
     * @return 任务 id
     */
    public Long submit(EditSpec spec, Long userId) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "视频剪辑功能未开启");
        }
        // spec 须已规范化成 V2 并通过 validate；此处仅防空（防绕过 controller 直调 service）。
        if (spec == null || spec.getTracks() == null || spec.getTracks().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "剪辑意图不能为空（须含至少 1 个 VIDEO 轨）");
        }

        MediaEditTask task = new MediaEditTask();
        task.setUserId(userId);
        task.setStatus(MediaEditTask.STATUS_PENDING);
        task.setEditSpec(toJson(spec));
        task.setAttempt(0);
        taskMapper.insert(task);

        int videoClips = spec.getTracks().stream()
                .filter(t -> EditSpec.TrackType.VIDEO.name().equalsIgnoreCase(t.getType()))
                .mapToInt(t -> t.getSegments() == null ? 0 : t.getSegments().size())
                .sum();
        log.info("提交视频剪辑任务 taskId={} userId={} tracks={} videoClips={}",
                task.getId(), userId, spec.getTracks().size(), videoClips);
        return task.getId();
    }

    private String toJson(EditSpec spec) {
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("editSpec 序列化失败", e);
        }
    }
}
