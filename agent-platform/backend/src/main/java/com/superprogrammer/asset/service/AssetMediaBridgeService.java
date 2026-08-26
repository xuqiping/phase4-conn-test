package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        // 修复III F1（17x#1）：同项目判重——(projectId, taskId, imageIdx) 已入库 → 复用既有资产，不重复建
        // （不同项目仍可各自入库；并发双击极小竞态由前端入库后置已入库态兜底，不做唯一索引）
        Asset existing = findExistingBySource(projectId, req.getTaskId(),
                isVideo ? null : req.getImageIdx(),
                isVideo ? Asset.CATEGORY_VIDEO : Asset.CATEGORY_IMAGE);
        if (existing != null) {
            log.info("media import dedup hit: assetId={} taskId={} idx={} projectId={} userId={}",
                    existing.getId(), req.getTaskId(), req.getImageIdx(), projectId, userId);
            return MediaImportVO.builder()
                    .created(false)
                    .duplicate(true)
                    .assetId(existing.getId())
                    .name(existing.getName())
                    .mediaType(existing.getMediaType())
                    .version(existing.getCurrentVersion())
                    .message("该项目已入库过该产物（资产 #" + existing.getId() + "），未重复创建")
                    .build();
        }
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
        asset.setGenMeta(buildGenMeta(task, isVideo ? null : req.getImageIdx()));
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

    /** 生成谱系 JSON：source=MEDIA + taskId + model + prompt（从 requestConfig 解析）+imageIdx（图片行，F1 判重键）。 */
    private String buildGenMeta(MediaGenTask task, Integer imageIdx) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("source", SOURCE_MEDIA);
            root.put("taskId", task.getId());
            if (imageIdx != null) {
                root.put("imageIdx", imageIdx);
            }
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

    /**
     * 修复III F1：同项目同任务产物判重——按 gen_meta JSONB 的 taskId(+imageIdx) 文本匹配。
     * 存量旧资产 genMeta 无 imageIdx 键 → 图片行判重不命中（允许补入库，向前兼容）。
     */
    private Asset findExistingBySource(Long projectId, Long taskId, Integer imageIdx, String category) {
        LambdaQueryWrapper<Asset> qw = new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, projectId)
                .eq(Asset::getMediaCategory, category)
                .apply("gen_meta->>'source' = {0}", SOURCE_MEDIA)
                .apply("gen_meta->>'taskId' = {0}", String.valueOf(taskId));
        if (imageIdx != null) {
            qw.apply("gen_meta->>'imageIdx' = {0}", String.valueOf(imageIdx));
        }
        return assetMapper.selectOne(qw.orderByAsc(Asset::getId).last("LIMIT 1"));
    }

    /**
     * 修复III F2（17x#1）：批量查媒体任务已入库状态（跨项目，source=MEDIA）——
     * 组产出 tab 加载后一条 IN 查询回填行首「已入库」tag（防 N+1）。
     *
     * @return taskId → 首个资产 id（同一任务入库多项目取最小 id）
     */
    public Map<Long, Long> existsBySourceTaskIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Map.of();
        }
        // Long 逐一校验拼 IN（参数化占位符不支持 IN 列表展开；值域 Long 无注入面）
        String joined = taskIds.stream().filter(java.util.Objects::nonNull)
                .map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        if (joined.isEmpty()) {
            return Map.of();
        }
        List<Asset> rows = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .select(Asset::getId, Asset::getGenMeta)
                .apply("gen_meta->>'source' = {0} AND gen_meta->>'taskId' IN (" + joined + ")")
                .orderByAsc(Asset::getId));
        Map<Long, Long> result = new LinkedHashMap<>();
        for (Asset a : rows) {
            try {
                JsonNode taskIdNode = objectMapper.readTree(a.getGenMeta() == null ? "{}" : a.getGenMeta()).path("taskId");
                if (taskIdNode.canConvertToLong()) {
                    result.putIfAbsent(taskIdNode.asLong(), a.getId());
                }
            } catch (Exception e) {
                log.warn("parse genMeta taskId failed: assetId={} {}", a.getId(), e.getMessage());
            }
        }
        return result;
    }
}
