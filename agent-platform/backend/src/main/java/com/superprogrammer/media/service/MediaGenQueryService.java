package com.superprogrammer.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.media.dto.MediaTaskVO;
import com.superprogrammer.media.dto.InputAttachmentVO;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
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
        return toVO(task, admin || owns(task, userId), true);
    }

    /** 6 参兼容重载（视频第三轮测试契约）：kind=null 不过滤，视频/图片全量返回。 */
    public List<MediaTaskVO> list(Long userId, boolean admin, String query,
                                  OffsetDateTime from, OffsetDateTime to, Integer limit) {
        return list(userId, admin, query, from, to, limit, null);
    }

    /**
     * 历史列表（服务端筛选）。kind 白名单：IMAGE=仅图片任务、VIDEO=仅视频任务、null=全量。
     * 过滤在 SQL 层完成——前端先 LIMIT 再内存过滤会行数不足/仍混杂。
     */
    public List<MediaTaskVO> list(Long userId, boolean admin, String query,
                                  OffsetDateTime from, OffsetDateTime to, Integer limit, String kind) {
        if (limit != null && (limit < 1 || limit > 100)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "limit 必须在 1-100 之间");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "开始时间必须早于结束时间");
        }
        String normalizedQuery = query == null || query.isBlank() ? null : query.strip();
        if (normalizedQuery != null && normalizedQuery.length() > 8000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "提示词筛选条件不能超过 8000 字符");
        }
        String normalizedKind = normalizeKind(kind);
        String escapedQuery = normalizedQuery == null ? null : escapeLikeLiteral(normalizedQuery);
        int size = limit == null ? 50 : limit;
        return taskMapper.selectHistory(userId, admin, escapedQuery, from, to, size, normalizedKind).stream()
                .map(t -> toVO(t, admin || owns(t, userId), false))
                .collect(Collectors.toList());
    }

    /** kind 白名单校验：null/空白→null；IMAGE/VIDEO（大小写不敏感）→大写归一；其余 400 不泄 SQL 细节。 */
    private String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        String k = kind.strip().toUpperCase();
        if (!"IMAGE".equals(k) && !"VIDEO".equals(k)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "kind 仅支持 IMAGE/VIDEO");
        }
        return k;
    }

    private String escapeLikeLiteral(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
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

    private MediaTaskVO toVO(MediaGenTask task, boolean mayAccessFile, boolean includeRequestDetails) {
        String prompt = null;
        String ratio = null;
        Integer duration = null;
        String resolution = null;
        Boolean watermark = false;
        Boolean generateAudio = false;
        List<InputAttachmentVO> inputAttachments = List.of();
        JsonNode submittedRequest = null;
        JsonNode providerRequestSnapshot = null;
        String size = null;
        String outputFormat = null;
        try {
            JsonNode cfg = objectMapper.readTree(task.getRequestConfig());
            prompt = cfg.path("prompt").asText(null);
            ratio = cfg.path("ratio").asText(null);
            if (cfg.path("duration").isNumber()) duration = cfg.path("duration").asInt();
            resolution = cfg.path("resolution").asText(null);
            watermark = cfg.path("watermark").asBoolean(false);
            generateAudio = cfg.path("generateAudio").asBoolean(false);
            inputAttachments = readInputAttachments(cfg);
            if (includeRequestDetails) {
                ObjectNode submitted = cfg.deepCopy();
                JsonNode storedSnapshot = submitted.remove("providerRequestSnapshot");
                submittedRequest = redactDataUris(submitted);
                if (storedSnapshot != null && !storedSnapshot.isNull()) {
                    providerRequestSnapshot = redactDataUris(storedSnapshot.deepCopy());
                }
            }
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
        // 图片任务：各张下载端点 + fileId 列表 + 计费/审计字段
        List<String> imageUrls = null;
        List<String> imageFileIds = null;
        Integer generatedImages = null;
        Long outputTokens = null;
        if (imageTask && mayAccessFile && succeeded) {
            List<String> fileIds = readImageFileIds(task);
            if (!fileIds.isEmpty()) {
                imageFileIds = fileIds;
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
                .ratio(ratio)
                .duration(duration)
                .resolution(resolution)
                .watermark(watermark)
                .generateAudio(generateAudio)
                .inputAttachments(inputAttachments)
                // 7x-4：按附件是否有 kind=="video" 算 hasReference（首尾帧图 kind=="image" 不算）
                .hasReference(inputAttachments.stream().anyMatch(a -> "video".equals(a.getKind())))
                .submittedRequest(submittedRequest)
                .providerRequestSnapshot(providerRequestSnapshot)
                .tokensCost(task.getTokensCost())
                .errorMsg(task.getErrorMsg())
                .videoUrl(videoUrl)
                .resultFileId(resultFileId)
                .imageUrls(imageUrls)
                .imageFileIds(imageFileIds)
                .generatedImages(generatedImages)
                .outputTokens(outputTokens)
                .size(size)
                .outputFormat(outputFormat)
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }

    /** 防御性脱敏：即使历史脏数据误存了 data URI，查询接口也不返回正文。 */
    private JsonNode redactDataUris(JsonNode node) {
        if (node == null || node.isNull()) return node;
        if (node.isTextual()) {
            String value = node.asText();
            return value.startsWith("data:") ? TextNode.valueOf("[REDACTED_DATA_URI]") : node;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            object.fields().forEachRemaining(entry -> object.set(entry.getKey(), redactDataUris(entry.getValue())));
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) array.set(i, redactDataUris(array.get(i)));
        }
        return node;
    }

    private List<InputAttachmentVO> readInputAttachments(JsonNode config) {
        List<InputAttachmentVO> attachments = new java.util.ArrayList<>();
        JsonNode arr = config.path("attachments");
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                String fileId = item.path("fileId").asText(null);
                String kind = item.path("kind").asText(null);
                if (fileId == null || fileId.isBlank() || kind == null || kind.isBlank()) continue;
                attachments.add(InputAttachmentVO.builder()
                        .fileId(fileId)
                        .kind(kind)
                        .frameRole(item.path("frameRole").asText(null))
                        .name(item.path("name").asText(null))
                        .previewUrl("/api/files/" + fileId)
                        .build());
            }
        }
        String legacyFileId = config.path("refFileId").asText(null);
        if (attachments.isEmpty() && legacyFileId != null && !legacyFileId.isBlank()) {
            String legacyRole = "last".equalsIgnoreCase(config.path("frameRole").asText())
                    ? "last_frame" : "first_frame";
            attachments.add(InputAttachmentVO.builder()
                    .fileId(legacyFileId)
                    .kind("image")
                    .frameRole(legacyRole)
                    .previewUrl("/api/files/" + legacyFileId)
                    .build());
        }
        return attachments;
    }
}
