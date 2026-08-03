package com.superprogrammer.media.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.media.config.MediaGenProperties;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 媒体生成任务提交入口。
 *
 * <p>职责：参数校验（运维上限）+ 解析 doubao provider + 建 PENDING 任务行 + 返回 taskId。
 * 不在此派发执行——交由 {@link com.superprogrammer.media.service.MediaGenTaskWorker} 定时轮询认领
 * （纯 poll 模式，照抄 IndexJob：天然崩溃恢复，重启后下次 poll 自动续跑 RUNNING 行）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaGenTaskService {

    /** doubao provider name（Ark 视频 key 复用入口，与 ArkSeedanceProvider 一致）。 */
    private static final String DOUBAO_PROVIDER_NAME = "doubao";

    /** 分辨率白名单（含上限校验：≤ maxRes）。 */
    private static final Set<String> RES_WHITELIST = Set.of("480p", "720p", "1080p");
    private static final Map<String, Integer> RES_RANK = Map.of("480p", 1, "720p", 2, "1080p", 3);
    private static final int PROMPT_MAX_LEN = 2000;

    private final MediaGenTaskMapper taskMapper;
    private final LlmProviderService llmProviderService;
    private final MediaGenProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 提交生成任务。
     *
     * @param prompt    提示词（必填）
     * @param duration  时长秒
     * @param resolution 分辨率
     * @param taskType  TEXT2VIDEO / IMAGE2VIDEO
     * @param refFileId 图生视频参考图 stored_files.file_id（IMAGE2VIDEO 必填）
     * @param model     Ark 模型 id（null 则取 doubao 首个模型）
     * @param userId    提交用户（nullable：系统调用）
     * @return 任务 id
     */
    public Long submit(String prompt, Integer duration, String resolution, String taskType,
                       String refFileId, String model, Long userId) {
        if (!properties.isGenEnabled()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "视频生成功能未开启");
        }
        validate(prompt, duration, resolution, taskType, refFileId);

        LlmProviderEntity doubao = llmProviderService.getByName(DOUBAO_PROVIDER_NAME);
        if (doubao == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到 doubao provider，请先在 LLM 供应商配置");
        }
        String resolvedModel = (model == null || model.isBlank()) ? firstModel(doubao) : model;
        if (resolvedModel == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "doubao provider 未配置模型列表");
        }

        Map<String, Object> config = new HashMap<>();
        config.put("prompt", prompt);
        config.put("duration", duration);
        config.put("resolution", resolution);
        if (refFileId != null) config.put("refFileId", refFileId);

        MediaGenTask task = new MediaGenTask();
        task.setUserId(userId);
        task.setProviderId(doubao.getId());
        task.setModel(resolvedModel);
        task.setTaskType(taskType);
        task.setStatus(MediaGenTask.STATUS_PENDING);
        task.setRequestConfig(toJson(config));
        task.setStatusFlag(MediaGenTask.FLAG_SUCCESS);
        task.setAttempt(0);
        taskMapper.insert(task);

        log.info("提交视频生成任务 taskId={} userId={} type={} model={}", task.getId(), userId, taskType, resolvedModel);
        return task.getId();
    }

    private void validate(String prompt, Integer duration, String resolution, String taskType, String refFileId) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提示词不能为空");
        }
        if (prompt.length() > PROMPT_MAX_LEN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提示词长度超限（≤" + PROMPT_MAX_LEN + "）");
        }
        if (duration == null || duration < 1 || duration > properties.getMaxDuration()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "时长须 ∈ [1, " + properties.getMaxDuration() + "]");
        }
        if (resolution == null || !RES_WHITELIST.contains(resolution)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分辨率非法: " + resolution);
        }
        Integer maxRank = RES_RANK.get(properties.getMaxRes());
        if (maxRank == null || RES_RANK.get(resolution) > maxRank) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "分辨率超上限（≤" + properties.getMaxRes() + "）");
        }
        if (!MediaGenTask.TYPE_TEXT2VIDEO.equals(taskType) && !MediaGenTask.TYPE_IMAGE2VIDEO.equals(taskType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务类型非法: " + taskType);
        }
        if (MediaGenTask.TYPE_IMAGE2VIDEO.equals(taskType) && (refFileId == null || refFileId.isBlank())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "图生视频须提供参考图");
        }
    }

    private String firstModel(LlmProviderEntity doubao) {
        if (doubao.getModels() == null || doubao.getModels().isBlank()) return null;
        try {
            List<?> models = objectMapper.readValue(doubao.getModels(), List.class);
            return models.isEmpty() ? null : String.valueOf(models.get(0));
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("requestConfig 序列化失败", e);
        }
    }
}
