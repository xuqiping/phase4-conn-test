package com.superprogrammer.media.edit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.media.edit.config.MediaEditProperties;
import com.superprogrammer.media.edit.dto.EditSpec;
import com.superprogrammer.media.edit.entity.MediaEditTask;
import com.superprogrammer.media.edit.mapper.MediaEditTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /** 剪辑提交指标（media.task.submitted, kind=edit）。 */
    private final BizMetrics bizMetrics;
    /** 审计：剪辑提交编程式落库（问题修复 #1，AOP @AuditLog 取不到 taskId，改 service 内落可关联终态）。 */
    private final AuditLogService auditLogService;

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
        // 提交者 IP 盖戳（worker 终态审计取用，无 MDC 则 null，问题修复 #6）
        task.setClientIp(MDC.get("clientIp"));
        taskMapper.insert(task);

        int videoClips = spec.getTracks().stream()
                .filter(t -> EditSpec.TrackType.VIDEO.name().equalsIgnoreCase(t.getType()))
                .mapToInt(t -> t.getSegments() == null ? 0 : t.getSegments().size())
                .sum();
        log.info("提交视频剪辑任务 taskId={} userId={} tracks={} videoClips={}",
                task.getId(), userId, spec.getTracks().size(), videoClips);
        bizMetrics.mediaSubmit(BizMetrics.MEDIA_EDIT);
        auditEditSubmit(task.getId(), userId, videoClips);
        return task.getId();
    }

    private String toJson(EditSpec spec) {
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("editSpec 序列化失败", e);
        }
    }

    /**
     * 问题修复 #1：剪辑提交审计（编程式，taskId 已生成可关联终态成功/失败行）。
     * detail 仅记 videoClips（轨道数在指标层），不含 spec 全文（可能含文本/URL，防泄露）。IP 从 MDC 取（同 submit 同线程）。
     */
    private void auditEditSubmit(Long taskId, Long userId, int videoClips) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", "EDIT");
        detail.put("videoClips", videoClips);
        try {
            String detailJson = objectMapper.writeValueAsString(detail);
            auditLogService.recordTask("media", "edit_submit", "media_edit_task", String.valueOf(taskId),
                    userId, null, MDC.get("clientIp"), detailJson, AuditLogEntity.RESULT_SUCCESS);
        } catch (Exception e) {
            log.warn("剪辑提交审计失败(已跳过) taskId={} : {}", taskId, e.toString());
        }
    }
}
