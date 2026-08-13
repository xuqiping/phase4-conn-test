package com.superprogrammer.asset.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superprogrammer.asset.dto.MediaImportRequest;
import com.superprogrammer.asset.dto.MediaImportVO;
import com.superprogrammer.asset.entity.Asset;
import com.superprogrammer.asset.entity.AssetVersion;
import com.superprogrammer.asset.mapper.AssetMapper;
import com.superprogrammer.asset.mapper.AssetVersionMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.service.MediaGenQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 媒体生成产物 → 项目资产库 入库桥（media→asset，与 {@link AssetCanvasBridgeService} 画布→库并列）。
 *
 * <p>把媒体生成产物存入资产库（生图逐张 / 视频任务结果，4x-2 扩展）。
 * 复用 stored_files(SOURCE_MEDIA) 行，不拷贝（同画布桥复用 SOURCE_CANVAS）。
 * genMeta.source="MEDIA" 标记来源 + taskId/model/prompt 供追溯。
 *
 * <p>跨包单向依赖：本服务只读依赖 {@link MediaGenQueryService}（loadImageForImport 复用媒体归属/终态/idx 咽喉点），
 * media 包不 import asset（保持 media 零回归）——同 asset→canvas 的解耦模式。
 *
 * <p>权限：第一层 @RequirePermission("asset:write")（控制器）；第二层 {@link AssetAclService#requireWrite}
 * 校验目标项目可写（viewer 不可入库，安全清单）。媒体归属由 loadImageForImport 再校验。
 *
 * <p>与画布桥区别：生图入库无画布节点，无 PRODUCED 绑定、无重复入库三态（同一张图可多次入库为独立资产，
 * 例如存进不同项目）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetMediaBridgeService {

    private static final String SOURCE_MEDIA = "MEDIA";

    private final AssetMapper assetMapper;
    private final AssetVersionMapper versionMapper;
    private final AssetAclService aclService;
    private final AssetService assetService;
    private final MediaGenQueryService mediaGenQueryService;
    private final ObjectMapper objectMapper;

    /**
     * 媒体产物入库：定位目标文件（归属+SUCCEEDED 校验）→ requireWrite(目标项目) →
     * 建资产 v1（fileId 复用，genMeta 标 MEDIA 来源）。
     *
     * <p>4x-2：mediaKind=VIDEO 走视频分支（result_file_id 定位，复用 loadForDownload 归属/终态咽喉），
     * 建 VIDEO 资产；IMAGE/null 走原图分支（imageIdx 定位 imageFileIds）。
     */
    @Transactional
    public MediaImportVO importFromMediaTask(MediaImportRequest req, Long userId, boolean admin) {
        if (req == null || req.getTaskId() == null || req.getProjectId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "taskId/projectId 不能为空");
        }
        String kind = req.getMediaKind() == null || req.getMediaKind().isBlank()
                ? "IMAGE" : req.getMediaKind().trim().toUpperCase();
        if (!"IMAGE".equals(kind) && !"VIDEO".equals(kind)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "mediaKind 仅支持 IMAGE/VIDEO");
        }
        boolean isVideo = "VIDEO".equals(kind);
        if (!isVideo && req.getImageIdx() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "图片入库需带 imageIdx");
        }
        Long projectId = req.getProjectId();
        // 目标项目写权限（viewer 不可入库）
        aclService.requireWrite(projectId, userId, admin);
        final MediaGenTask task;
        final String fileId;
        final String mediaType;
        final String category;
        final String defaultName;
        if (isVideo) {
            // 视频分支：loadForDownload 已校验归属 + SUCCEEDED + resultFileId 非空（单一咽喉）
            task = mediaGenQueryService.loadForDownload(req.getTaskId(), userId, admin);
            fileId = task.getResultFileId();
            mediaType = Asset.MEDIA_VIDEO;
            category = Asset.CATEGORY_VIDEO;
            defaultName = "视频产出";
        } else {
            // 图片分支：归属 + SUCCEEDED + idx 校验，返任务实体 + 目标图 fileId
            MediaGenQueryService.ImageImportContext ctx = mediaGenQueryService.loadImageForImport(
                    req.getTaskId(), req.getImageIdx(), userId, admin);
            task = ctx.task();
            fileId = ctx.fileId();
            mediaType = Asset.MEDIA_IMAGE;
            category = Asset.CATEGORY_IMAGE;
            defaultName = "图片产出";
        }

        String name = assetService.validateAssetName(
                (req.getName() != null && !req.getName().isBlank()) ? req.getName().trim() : defaultName);
        Asset asset = new Asset();
        asset.setProjectId(projectId);
        asset.setMediaType(mediaType);
        asset.setMediaCategory(category);
        asset.setName(name);
        asset.setDescription(req.getDescription());
        asset.setStatus(Asset.STATUS_DRAFT);
        asset.setCurrentVersion(1);
        asset.setTags("[]");
        asset.setContent("{}");
        asset.setGenMeta(buildGenMeta(task));
        assetMapper.insert(asset);

        AssetVersion v1 = new AssetVersion();
        v1.setAssetId(asset.getId());
        v1.setVersion(1);
        v1.setContent("{}");
        v1.setFileId(fileId);
        v1.setCreatedBy(userId);
        versionMapper.insert(v1);

        log.info("media import as new asset: assetId={} kind={} taskId={} idx={} fileId={} projectId={} userId={}",
                asset.getId(), kind, req.getTaskId(), req.getImageIdx(), fileId, projectId, userId);
        return MediaImportVO.builder()
                .created(true)
                .assetId(asset.getId())
                .name(name)
                .mediaType(mediaType)
                .version(1)
                .message("已入库 v1")
                .build();
    }

    /** 生成谱系 JSON：source=MEDIA + taskId + model + prompt（从 requestConfig 解析）。 */
    private String buildGenMeta(MediaGenTask task) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("source", SOURCE_MEDIA);
            root.put("taskId", task.getId());
            if (task.getModel() != null) {
                root.put("model", task.getModel());
            }
            JsonNode cfg = objectMapper.readTree(task.getRequestConfig() == null ? "{}" : task.getRequestConfig());
            String prompt = cfg.path("prompt").asText(null);
            if (prompt != null && !prompt.isBlank()) {
                root.put("prompt", prompt);
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("build media genMeta failed: {}", e.getMessage());
            return "{\"source\":\"" + SOURCE_MEDIA + "\"}";
        }
    }
}
