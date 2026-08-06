package com.superprogrammer.media.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.media.config.MediaGenProperties;
import com.superprogrammer.media.config.MediaModelCapability;
import com.superprogrammer.media.config.MediaModelCapabilityService;
import com.superprogrammer.media.dto.AttachmentRef;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 媒体生成任务提交入口。
 *
 * <p>职责：参数校验（运维上限 + 模型能力上限）+ 解析视频 provider/model
 * （指定 model 时跨全部 ACTIVE VIDEO provider 反查；未指定回退 media.provider-name 的 models[0]）
 * + 建 PENDING 任务行 + 返回 taskId。
 * 不在此派发执行——交由 {@link com.superprogrammer.media.service.MediaGenTaskWorker} 定时轮询认领
 * （纯 poll 模式，照抄 IndexJob：天然崩溃恢复，重启后下次 poll 自动续跑 RUNNING 行）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaGenTaskService {

    /** 分辨率等级（≤ maxRes 运维上限校验用）。 */
    private static final Map<String, Integer> RES_RANK = Map.of("480p", 1, "720p", 2, "1080p", 3, "4K", 4);
    /** 附件类型白名单。 */
    private static final Set<String> ATTACHMENT_KINDS = Set.of("image", "video", "audio");
    private static final int PROMPT_MAX_LEN = 2000;

    private final MediaGenTaskMapper taskMapper;
    private final MediaModelService mediaModelService;
    private final MediaModelCapabilityService capabilityService;
    private final FileStorageService fileStorageService;
    private final MediaGenProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 提交生成任务。
     *
     * @param prompt        提示词（必填）
     * @param ratio         画面比例（官方 ratio，null 默认 16:9）
     * @param duration      时长秒（4–15）
     * @param resolution    分辨率（null 默认 720p）
     * @param watermark     水印开关（null 默认 false）
     * @param generateAudio 生成原生音频开关（null 默认 false）
     * @param taskType      TEXT2VIDEO / IMAGE2VIDEO（attachments 非空时服务端强制 IMAGE2VIDEO）
     * @param refFileId     旧版单首帧参考图 file_id（与 attachments 互斥，保留兼容）
     * @param attachments   多模态参考附件（图/视频/音频，上限按模型能力）
     * @param model         视频模型 id（null 则取默认 provider 首个模型）
     * @param userId        提交用户（nullable：系统调用）
     * @param admin         是否 admin（附件归属校验旁路）
     * @return 任务 id
     */
    public Long submit(String prompt, String ratio, Integer duration, String resolution,
                       Boolean watermark, Boolean generateAudio, String taskType,
                       String refFileId, List<AttachmentRef> attachments,
                       String model, Long userId, boolean admin) {
        if (!properties.isGenEnabled()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "视频生成功能未开启");
        }

        // 1) 解析 provider + model（指定 model 时跨 VIDEO provider 反查，未指定走旧默认路径）
        LlmProviderEntity provider;
        String resolvedModel;
        if (model == null || model.isBlank()) {
            provider = mediaModelService.defaultProvider();
            if (provider == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND,
                        "未找到默认视频 provider(name=" + properties.getProviderName()
                                + ")，请先在「全局模型供应商」建一条 VIDEO 类 provider");
            }
            resolvedModel = mediaModelService.firstModelOf(provider);
            if (resolvedModel == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "视频 provider 未配置模型列表（models）");
            }
        } else {
            provider = mediaModelService.resolveProviderByModel(model);
            if (provider == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "模型不可用: " + model + "（不在任何 ACTIVE 媒体 provider 的 models 列表中）");
            }
            resolvedModel = model;
        }
        MediaModelCapability cap = capabilityService.resolve(resolvedModel, provider.getConfig());

        // 2) 参数校验（基础白名单 + 模型能力上限）
        validate(prompt, ratio, duration, resolution, generateAudio, taskType, refFileId, attachments,
                cap, userId, admin);

        // 3) attachments 非空 → 服务端派生 IMAGE2VIDEO（多模态参考生视频）
        String resolvedType = (attachments != null && !attachments.isEmpty())
                ? MediaGenTask.TYPE_IMAGE2VIDEO
                : (taskType == null || taskType.isBlank() ? MediaGenTask.TYPE_TEXT2VIDEO : taskType);

        Map<String, Object> config = new HashMap<>();
        config.put("prompt", prompt);
        config.put("ratio", ratio);
        config.put("duration", duration);
        config.put("resolution", resolution);
        config.put("watermark", Boolean.TRUE.equals(watermark));
        config.put("generateAudio", Boolean.TRUE.equals(generateAudio));
        if (refFileId != null) config.put("refFileId", refFileId);
        if (attachments != null && !attachments.isEmpty()) {
            List<Map<String, String>> list = new ArrayList<>(attachments.size());
            for (AttachmentRef a : attachments) {
                list.add(Map.of("fileId", a.getFileId(), "kind", a.getKind()));
            }
            config.put("attachments", list);
        }

        MediaGenTask task = new MediaGenTask();
        task.setUserId(userId);
        task.setProviderId(provider.getId());
        task.setModel(resolvedModel);
        task.setTaskType(resolvedType);
        task.setStatus(MediaGenTask.STATUS_PENDING);
        task.setRequestConfig(toJson(config));
        task.setStatusFlag(MediaGenTask.FLAG_SUCCESS);
        task.setAttempt(0);
        taskMapper.insert(task);

        log.info("提交视频生成任务 taskId={} userId={} type={} model={} ratio={} res={} audio={} 附件={}",
                task.getId(), userId, resolvedType, resolvedModel, ratio, resolution, generateAudio,
                attachments == null ? 0 : attachments.size());
        return task.getId();
    }

    private void validate(String prompt, String ratio, Integer duration, String resolution,
                          Boolean generateAudio, String taskType, String refFileId,
                          List<AttachmentRef> attachments, MediaModelCapability cap,
                          Long userId, boolean admin) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提示词不能为空");
        }
        if (prompt.length() > PROMPT_MAX_LEN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提示词长度超限（≤" + PROMPT_MAX_LEN + "）");
        }
        int maxDuration = Math.min(cap.getMaxDuration(), properties.getMaxDuration());
        if (duration == null || duration < cap.getMinDuration() || duration > maxDuration) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "时长须 ∈ [" + cap.getMinDuration() + ", " + maxDuration + "]");
        }
        if (ratio != null && !ratio.isBlank() && !cap.getSupportedRatios().contains(ratio)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "画面比例非法: " + ratio);
        }
        if (resolution != null && !resolution.isBlank() && !cap.getSupportedResolutions().contains(resolution)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该模型不支持分辨率: " + resolution);
        }
        Integer maxRank = RES_RANK.get(properties.getMaxRes());
        if (maxRank == null) maxRank = RES_RANK.get("720p");
        if (resolution != null && RES_RANK.getOrDefault(resolution, 0) > maxRank) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "分辨率超上限（≤" + properties.getMaxRes() + "）");
        }
        if (Boolean.TRUE.equals(generateAudio) && !cap.isSupportsGenerateAudio()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该模型不支持生成音频");
        }
        if (attachments != null && !attachments.isEmpty()) {
            if (refFileId != null && !refFileId.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "attachments 与 refFileId 互斥，请只用一种参考方式");
            }
            validateAttachments(attachments, cap, userId, admin);
            return;
        }
        // 旧路径：无 attachments 时沿用 taskType + refFileId 规则（null/blank 视为 TEXT2VIDEO）
        String tt = (taskType == null || taskType.isBlank()) ? MediaGenTask.TYPE_TEXT2VIDEO : taskType;
        if (!MediaGenTask.TYPE_TEXT2VIDEO.equals(tt) && !MediaGenTask.TYPE_IMAGE2VIDEO.equals(tt)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务类型非法: " + taskType);
        }
        if (MediaGenTask.TYPE_IMAGE2VIDEO.equals(tt)) {
            if (refFileId == null || refFileId.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "图生视频须提供参考图");
            }
            if (cap.getMaxImages() < 1) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "该模型不支持参考图");
            }
        }
    }

    /**
     * 附件校验：类型白名单 + 分类上限 + 总数上限 + 归属校验（防 IDOR）+ MIME 粗匹配。
     */
    private void validateAttachments(List<AttachmentRef> attachments, MediaModelCapability cap,
                                     Long userId, boolean admin) {
        if (attachments.size() > cap.getMaxAttachments()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "附件总数超限（≤" + cap.getMaxAttachments() + "，当前 " + attachments.size() + "）");
        }
        int images = 0, videos = 0, audios = 0;
        for (AttachmentRef a : attachments) {
            String kind = a.getKind() == null ? "" : a.getKind().trim().toLowerCase();
            if (!ATTACHMENT_KINDS.contains(kind)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "附件类型非法: " + a.getKind() + "（image/video/audio）");
            }
            switch (kind) {
                case "image" -> images++;
                case "video" -> videos++;
                case "audio" -> audios++;
                default -> { /* 白名单已挡 */ }
            }
            checkAttachmentOwnership(a.getFileId(), kind, userId, admin);
        }
        if (images > cap.getMaxImages()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "参考图超限（该模型 ≤" + cap.getMaxImages() + " 张，当前 " + images + "）");
        }
        if (videos > cap.getMaxVideos()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    cap.getMaxVideos() == 0 ? "该模型不支持参考视频"
                            : "参考视频超限（该模型 ≤" + cap.getMaxVideos() + " 个，当前 " + videos + "）");
        }
        if (audios > cap.getMaxAudios()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    cap.getMaxAudios() == 0 ? "该模型不支持参考音频"
                            : "参考音频超限（该模型 ≤" + cap.getMaxAudios() + " 个，当前 " + audios + "）");
        }
        if (videos > 0 && !cap.isVideoDataUri()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该模型暂不支持视频参考（data URI 通道关闭）");
        }
    }

    /**
     * 附件归属 + MIME 校验。提交即拒（400/403），不拖到 worker 异步失败。
     * admin 与系统调用（userId=null）旁路归属校验。
     */
    private void checkAttachmentOwnership(String fileId, String kind, Long userId, boolean admin) {
        StoredFileEntity meta = fileStorageService.findMeta(fileId);
        if (meta == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "附件不存在: " + fileId);
        }
        if (!admin && userId != null && !userId.equals(meta.getOwnerUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权使用该附件: " + fileId);
        }
        String mime = meta.getMime();
        if (mime != null && !mime.isBlank() && !mime.startsWith(kind + "/")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "附件类型不符: 声明 " + kind + "，实际 " + mime + "（" + meta.getOriginalName() + "）");
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
