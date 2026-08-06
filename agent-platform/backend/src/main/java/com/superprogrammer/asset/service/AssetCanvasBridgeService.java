package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superprogrammer.asset.dto.AssetUsageVO;
import com.superprogrammer.asset.dto.CanvasImportRequest;
import com.superprogrammer.asset.dto.CanvasImportVO;
import com.superprogrammer.asset.dto.ResolveVO;
import com.superprogrammer.asset.dto.VersionCreateRequest;
import com.superprogrammer.asset.entity.Asset;
import com.superprogrammer.asset.entity.AssetBinding;
import com.superprogrammer.asset.entity.AssetVersion;
import com.superprogrammer.asset.mapper.AssetMapper;
import com.superprogrammer.asset.mapper.AssetVersionMapper;
import com.superprogrammer.canvas.dto.CanvasNodeDTO;
import com.superprogrammer.canvas.entity.Canvas;
import com.superprogrammer.canvas.service.CanvasService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * 项目资产库·画布双向打通（plan §S7 / FR-008/009/011，设计方案 §八「闭环核心」）。
 *
 * <p>三端点核心逻辑：
 * <ul>
 *   <li>{@link #importFromCanvas}：画布节点产出入库（画布→库）。按节点类型映射资产类型，
 *       捕获生成谱系入 gen_meta，落 PRODUCED 绑定供双向追溯；重复入库支持「存为新版本」(L5)</li>
 *   <li>{@link #resolve}：库→画布引用解析（库→画布）。返当前/指定版本快照（fileId+content），
 *       viewer 可用（只读引用，设计方案 §7.2）；引用=版本快照，资产升级不影响已引用方（§六）</li>
 *   <li>{@link #listUsages}：资产详情页「使用记录」（by assetId，PRODUCED+REFERENCE 全列）</li>
 * </ul>
 *
 * <p>跨包解耦（设计方案 §十四）：本服务单向只读依赖 {@link CanvasService}（loadOwned 复用画布归属咽喉点 +
 * 读 snapshot），canvas 包不 import asset（保持 canvas 零回归）。节点从快照 JSON 解析（同 CanvasController.resolveVideoFileId 范式）。
 *
 * <p>权限：入库 requireWrite（editor+owner）；resolve/usages loadAccessible（viewer 可读，安全清单防 fileId 遍历）。
 *
 * <p>可观测性：入库/解析打日志（assetId/canvasId/nodeId/userId），复用 media traceId 风格。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetCanvasBridgeService {

    /** 重复入库模式：存为新版本（推荐，变体归组）。 */
    public static final String MODE_NEW_VERSION = "NEW_VERSION";
    /** 重复入库模式：另起新资产。 */
    public static final String MODE_NEW_ASSET = "NEW_ASSET";

    private static final String SOURCE_CANVAS = "CANVAS";

    private final AssetMapper assetMapper;
    private final AssetVersionMapper versionMapper;
    private final AssetAclService aclService;
    private final AssetService assetService;
    private final AssetVersionService versionService;
    private final AssetBindingService bindingService;
    private final CanvasService canvasService;
    private final ObjectMapper objectMapper;

    // ==================== 画布 → 库（节点入库，FR-008） ====================

    /**
     * 画布节点产出入库。requireWrite（viewer 不可入库，安全清单）。
     *
     * <p>流程：画布归属校验 → 定位节点 → 据类型映射资产类型 + 提取产出 → 重复入库检测 →
     * （新版本 | 新资产）→ 捕获 gen_meta → 落 PRODUCED 绑定。
     */
    @Transactional
    public CanvasImportVO importFromCanvas(CanvasImportRequest req, Long userId, boolean admin) {
        if (req == null || req.getProjectId() == null || req.getCanvasId() == null || req.getNodeId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId/canvasId/nodeId 不能为空");
        }
        Long projectId = req.getProjectId();
        Long canvasId = req.getCanvasId();
        String nodeId = req.getNodeId();
        // 项目写权限（入库须 editor+owner）
        aclService.requireWrite(projectId, userId, admin);
        // 画布归属咽喉点（复用，禁止在他人画布借道入库）+ 拿 snapshot
        Canvas canvas = canvasService.loadOwned(canvasId, userId, admin);
        JsonNode node = extractNode(canvas.getSnapshot(), nodeId);
        String nodeType = node.path("type").asText("");
        String mediaType = mapMediaType(nodeType);
        JsonNode data = node.path("data");

        // 重复入库检测（plan L5）
        AssetBinding dup = bindingService.findProduced(canvasId, nodeId);
        String mode = req.getMode();
        if (dup != null && (mode == null || mode.isBlank())) {
            // 不创建，返回 duplicate 提示，前端弹「存为新版本/新建」
            Asset dupAsset = assetMapper.selectById(dup.getAssetId());
            Integer dupVer = dupAsset != null ? dupAsset.getCurrentVersion() : null;
            log.info("canvas import duplicate detected: canvasId={} nodeId={} assetId={} userId={}",
                    canvasId, nodeId, dup.getAssetId(), userId);
            return CanvasImportVO.builder()
                    .created(false)
                    .duplicateAssetId(dup.getAssetId())
                    .duplicateVersion(dupVer)
                    .mediaType(mediaType)
                    .message("该节点产出入库的资产已存在，建议「存为新版本」或「另存为新资产」")
                    .build();
        }

        // 提取产出物（文本正文 JSON / 文件 fileId）
        boolean textType = isTextType(mediaType);
        String contentJson = textType ? extractTextContent(mediaType, data) : "{}";
        String fileId = !textType ? extractFileId(mediaType, data) : null;
        String genMeta = buildGenMeta(canvasId, nodeId, nodeType, data);

        if (dup != null && MODE_NEW_VERSION.equals(mode)) {
            // 在已入库资产上建新版本（versionService 自带 requireWrite 再校验该项目权限）
            int newVer = createVersionFromCanvas(dup.getAssetId(), contentJson, fileId, userId, admin);
            assetMapper.updateGenMeta(dup.getAssetId(), genMeta, userId);
            bindingService.recordProduced(dup.getAssetId(), newVer, canvasId, nodeId, userId);
            Asset a = assetMapper.selectById(dup.getAssetId());
            log.info("canvas import as new version: assetId={} v={} canvasId={} nodeId={} userId={}",
                    dup.getAssetId(), newVer, canvasId, nodeId, userId);
            return CanvasImportVO.builder()
                    .created(true)
                    .assetId(dup.getAssetId())
                    .name(a.getName())
                    .mediaType(a.getMediaType())
                    .version(newVer)
                    .message("已存为新版本 v" + newVer)
                    .build();
        }

        // 新建资产（dup==null 或 mode=NEW_ASSET）
        String name = assetService.validateAssetName(resolveName(req.getName(), data, nodeType));
        Asset asset = new Asset();
        asset.setProjectId(projectId);
        asset.setMediaType(mediaType);
        asset.setName(name);
        asset.setDescription(req.getDescription());
        asset.setStatus(Asset.STATUS_DRAFT);
        asset.setTags(serializeList(req.getTags()));
        asset.setCurrentVersion(1);
        asset.setContent(textType ? contentJson : "{}");
        asset.setGenMeta(genMeta);
        assetMapper.insert(asset);

        AssetVersion v1 = new AssetVersion();
        v1.setAssetId(asset.getId());
        v1.setVersion(1);
        v1.setContent(textType ? contentJson : "{}");
        v1.setFileId(fileId);
        v1.setCreatedBy(userId);
        versionMapper.insert(v1);

        assetService.attachRoles(projectId, asset.getId(), req.getRoleKeys());
        bindingService.recordProduced(asset.getId(), 1, canvasId, nodeId, userId);
        log.info("canvas import as new asset: assetId={} mediaType={} canvasId={} nodeId={} userId={}",
                asset.getId(), mediaType, canvasId, nodeId, userId);
        return CanvasImportVO.builder()
                .created(true)
                .assetId(asset.getId())
                .name(name)
                .mediaType(mediaType)
                .version(1)
                .message("已入库 v1")
                .build();
    }

    // ==================== 库 → 画布（引用解析，FR-009） ====================

    /**
     * 引用解析：返当前/指定版本快照（fileId+content），viewer 可用（loadAccessible，只读引用）。
     * 引用=版本快照，资产升级不影响已引用方（设计方案 §六）。
     *
     * <p>canvasId+nodeId 同时给定时落 REFERENCE 绑定（双向追溯「被引用」台账，FR-011）；
     * 缺省（详情页纯预览解析）不落绑定。
     */
    public ResolveVO resolve(Long assetId, Integer version, Long canvasId, String nodeId, Long userId, boolean admin) {
        Asset asset = loadAsset(assetId);
        aclService.loadAccessible(asset.getProjectId(), userId, admin);
        Integer ver = (version == null || version <= 0) ? asset.getCurrentVersion() : version;
        AssetVersion av = versionMapper.selectOne(new LambdaQueryWrapper<AssetVersion>()
                .eq(AssetVersion::getAssetId, assetId)
                .eq(AssetVersion::getVersion, ver));
        if (av == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "版本不存在: v" + ver);
        }
        // 落 REFERENCE 绑定（库→画布引用台账，仅当带画布+节点上下文时；详情页预览不记账）
        if (canvasId != null && nodeId != null && !nodeId.isBlank()) {
            bindingService.recordReference(assetId, ver, canvasId, nodeId, userId);
        }
        String url = (av.getFileId() != null && !av.getFileId().isBlank())
                ? "/api/files/" + av.getFileId() : null;
        log.info("asset resolved: assetId={} v={} canvasId={} nodeId={} userId={}", assetId, ver, canvasId, nodeId, userId);
        return ResolveVO.builder()
                .assetId(assetId)
                .mediaType(asset.getMediaType())
                .version(ver)
                .fileId(av.getFileId())
                .url(url)
                .content(isTextType(asset.getMediaType()) ? av.getContent() : null)
                .name(asset.getName())
                .build();
    }

    // ==================== 使用记录（双向追溯，FR-011） ====================

    /** 资产详情页「使用记录」（PRODUCED+REFERENCE 全列，倒序）。loadAccessible（viewer 可读）。 */
    public List<AssetUsageVO> listUsages(Long assetId, Long userId, boolean admin) {
        Asset asset = loadAsset(assetId);
        aclService.loadAccessible(asset.getProjectId(), userId, admin);
        return bindingService.listUsages(assetId);
    }

    // ==================== 内部：节点解析 + 类型映射 + 产出提取 ====================

    /** 从快照定位节点（同 CanvasController.resolveVideoFileId 范式）。缺失→NOT_FOUND。 */
    private JsonNode extractNode(String snapshot, String nodeId) {
        try {
            JsonNode root = objectMapper.readTree(snapshot == null ? "{}" : snapshot);
            JsonNode nodes = root.path("nodes");
            if (nodes.isArray()) {
                for (JsonNode n : nodes) {
                    if (nodeId.equals(n.path("id").asText())) {
                        return n;
                    }
                }
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "画布快照解析失败");
        }
        throw new BusinessException(ErrorCode.NOT_FOUND, "节点不存在: " + nodeId);
    }

    /** 节点类型 → 资产内容类型映射（设计方案 §八 节点入库映射表）。 */
    private String mapMediaType(String nodeType) {
        return switch (nodeType) {
            case CanvasNodeDTO.TYPE_TEXT -> Asset.MEDIA_PROMPT;
            case CanvasNodeDTO.TYPE_SCRIPT -> Asset.MEDIA_SCRIPT;
            case CanvasNodeDTO.TYPE_IMAGE -> Asset.MEDIA_IMAGE;
            case CanvasNodeDTO.TYPE_VIDEO -> Asset.MEDIA_VIDEO;
            case CanvasNodeDTO.TYPE_AUDIO -> Asset.MEDIA_AUDIO;
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的节点类型: " + nodeType);
        };
    }

    private boolean isTextType(String mediaType) {
        return Asset.MEDIA_PROMPT.equals(mediaType) || Asset.MEDIA_SCRIPT.equals(mediaType);
    }

    /**
     * 提取文本类节点正文 JSON：
     * <ul>
     *   <li>提示词（text 节点）：优先 outputText（运行产出），退回 prompt（输入）</li>
     *   <li>剧本（script 节点）：synopsis + scenes（若已拆分场）</li>
     * </ul>
     * 空产出 → 400 拦截（plan 入库规则「空节点拦截」）。
     */
    private String extractTextContent(String mediaType, JsonNode data) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            if (Asset.MEDIA_SCRIPT.equals(mediaType)) {
                String synopsis = textOr(data, "synopsis");
                if (synopsis == null) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "剧本节点无可入库内容（请先填写剧本）");
                }
                root.put("synopsis", synopsis);
                JsonNode scenes = data.path("scenes");
                if (scenes.isArray() && scenes.size() > 0) {
                    root.set("scenes", scenes);
                }
            } else {
                // 提示词：outputText 优先（运行后的产出），退回 prompt（输入原文）
                String body = textOr(data, "outputText");
                if (body == null) {
                    body = textOr(data, "prompt");
                }
                if (body == null) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "文本节点无可入库产出（请先生成或填写提示词）");
                }
                root.put("body", body);
            }
            return objectMapper.writeValueAsString(root);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("extract text content failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "节点产出解析失败");
        }
    }

    /** 提取文件类节点 fileId（image/video/audio）。空 → 400 拦截。 */
    private String extractFileId(String mediaType, JsonNode data) {
        String fileId = textOr(data, "fileId");
        if (fileId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "节点无产出文件，无法入库（请先上传或生成" + mediaType + "）");
        }
        return fileId;
    }

    /** 构造生成谱系 JSON（设计方案 §三 第四层）：source/canvasId/nodeId/nodeType + 节点携带的 prompt/model/seed。 */
    private String buildGenMeta(Long canvasId, String nodeId, String nodeType, JsonNode data) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("source", SOURCE_CANVAS);
            root.put("canvasId", canvasId);
            root.put("nodeId", nodeId);
            root.put("nodeType", nodeType);
            String prompt = textOr(data, "prompt");
            if (prompt != null) {
                root.put("prompt", prompt);
            }
            String model = textOr(data, "model");
            if (model != null) {
                root.put("model", model);
            }
            JsonNode seed = data.path("seed");
            if (seed != null && seed.isNumber()) {
                root.set("seed", seed);
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("build genMeta failed: {}", e.getMessage());
            return "{\"source\":\"" + SOURCE_CANVAS + "\"}";
        }
    }

    /** 在已入库资产上建新版本（画布产出更新入库）。委托 versionService 复用乐观锁+权限校验。 */
    private int createVersionFromCanvas(Long assetId, String contentJson, String fileId,
                                        Long userId, boolean admin) {
        VersionCreateRequest vReq = new VersionCreateRequest();
        if (fileId != null) {
            vReq.setFileId(fileId);
        } else {
            vReq.setContent(contentJson);
        }
        vReq.setChangeNote("画布节点产出更新入库");
        return versionService.createVersion(assetId, userId, admin, vReq);
    }

    /** 解析资产名：请求显式给 > 节点 label/title > 节点类型兜底。 */
    private String resolveName(String reqName, JsonNode data, String nodeType) {
        if (reqName != null && !reqName.isBlank()) {
            return reqName.trim();
        }
        String label = textOr(data, "label");
        if (label != null) {
            return label;
        }
        String title = textOr(data, "title");
        if (title != null) {
            return title;
        }
        return switch (nodeType) {
            case CanvasNodeDTO.TYPE_TEXT -> "文本产出";
            case CanvasNodeDTO.TYPE_SCRIPT -> "剧本产出";
            case CanvasNodeDTO.TYPE_IMAGE -> "图片产出";
            case CanvasNodeDTO.TYPE_VIDEO -> "视频产出";
            case CanvasNodeDTO.TYPE_AUDIO -> "音频产出";
            default -> "画布产出";
        };
    }

    /** JsonNode 字段取非空白文本，null/空白返 null。 */
    private String textOr(JsonNode data, String field) {
        JsonNode v = data.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText("");
        return (s == null || s.isBlank()) ? null : s;
    }

    private String serializeList(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list == null ? Collections.emptyList() : list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Asset loadAsset(Long assetId) {
        Asset a = assetMapper.selectById(assetId);
        if (a == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        return a;
    }
}
