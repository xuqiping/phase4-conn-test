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

    /**
     * 图片任务逐张下载：校验归属 + SUCCEEDED + idx 合法，返回 imageFileIds[idx]。
     *
     * @param idx 图片下标（0-based，对应 imageFileIds 顺序）
     * @return 该张 stored_files.file_id
     */
    public String loadImageFileId(Long id, int idx, Long userId, boolean admin) {
        return loadImageForImport(id, idx, userId, admin).fileId();
    }

    /** 媒体→资产库导入上下文（任务实体 + 目标图 fileId，归属/终态/idx 已校验）。 */
    public record ImageImportContext(MediaGenTask task, String fileId) {
    }

    /**
     * 图片入库用：校验归属 + SUCCEEDED + idx，返任务实体 + imageFileIds[idx]。
     * 资产桥据此取 model/prompt 写 genMeta + fileId 建 AssetVersion（单一咽喉，避免桥内重复校验）。
     */
    public ImageImportContext loadImageForImport(Long id, int idx, Long userId, boolean admin) {
        MediaGenTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        ensureOwnership(task, userId, admin);
        if (!MediaGenTask.STATUS_SUCCEEDED.equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务尚未生成完成");
        }
        List<String> fileIds = readImageFileIds(task);
        if (idx < 0 || idx >= fileIds.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "图片下标越界（0-" + (fileIds.size() - 1) + "）");
        }
        return new ImageImportContext(task, fileIds.get(idx));
    }

    /** 解析 result_meta.imageFileIds（图片任务）；非图片任务或无 meta 返回空表。 */
    private List<String> readImageFileIds(MediaGenTask task) {
        if (task.getResultMeta() == null || task.getResultMeta().isBlank()) {
            return List.of();
        }
        try {
            JsonNode meta = objectMapper.readTree(task.getResultMeta());
            JsonNode arr = meta.path("imageFileIds");
            if (!arr.isArray()) {
                return List.of();
            }
            List<String> ids = new java.util.ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                String fid = n.asText(null);
                if (fid != null && !fid.isBlank()) {
                    ids.add(fid);
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("解析 result_meta 失败 taskId={}: {}", task.getId(), e.getMessage());
            return List.of();
        }
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
        String size = null;
        String outputFormat = null;
        try {
            JsonNode cfg = objectMapper.readTree(task.getRequestConfig());
            prompt = cfg.path("prompt").asText(null);
            if (cfg.path("duration").isNumber()) duration = cfg.path("duration").asInt();
            resolution = cfg.path("resolution").asText(null);
            size = cfg.path("size").asText(null);
            outputFormat = cfg.path("outputFormat").asText(null);
        } catch (Exception e) {
            log.warn("VO 解析 requestConfig 失败 taskId={}: {}", task.getId(), e.getMessage());
        }
        boolean succeeded = MediaGenTask.STATUS_SUCCEEDED.equals(task.getStatus());
        boolean imageTask = MediaGenTask.TYPE_TEXT2IMAGE.equals(task.getTaskType())
                || MediaGenTask.TYPE_IMAGE2IMAGE.equals(task.getTaskType());
        String videoUrl = (mayAccessFile && succeeded && task.getResultFileId() != null)
                ? "/api/media/tasks/" + task.getId() + "/download"
                : null;
        String resultFileId = (mayAccessFile && succeeded) ? task.getResultFileId() : null;
        // 图片任务：各张下载端点 + 计费/审计字段
        List<String> imageUrls = null;
        Integer generatedImages = null;
        Long outputTokens = null;
        if (imageTask && mayAccessFile && succeeded) {
            List<String> fileIds = readImageFileIds(task);
            if (!fileIds.isEmpty()) {
                imageUrls = new java.util.ArrayList<>(fileIds.size());
                for (int i = 0; i < fileIds.size(); i++) {
                    imageUrls.add("/api/media/tasks/" + task.getId() + "/images/" + i + "/download");
                }
            }
            try {
                JsonNode meta = objectMapper.readTree(task.getResultMeta());
                generatedImages = meta.path("generatedImages").asInt(imageUrls == null ? null : imageUrls.size());
                outputTokens = meta.path("outputTokens").asLong(0L);
            } catch (Exception e) {
                log.warn("VO 解析 result_meta 失败 taskId={}: {}", task.getId(), e.getMessage());
            }
        }
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
                .imageUrls(imageUrls)
                .generatedImages(generatedImages)
                .outputTokens(outputTokens)
                .size(size)
                .outputFormat(outputFormat)
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }
}
