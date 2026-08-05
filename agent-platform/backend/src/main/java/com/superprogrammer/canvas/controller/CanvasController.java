package com.superprogrammer.canvas.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.canvas.dto.CanvasCreateRequest;
import com.superprogrammer.canvas.dto.CanvasNodeDTO;
import com.superprogrammer.canvas.dto.CanvasSaveRequest;
import com.superprogrammer.canvas.dto.CanvasVO;
import com.superprogrammer.canvas.dto.FrameExtractRequest;
import com.superprogrammer.canvas.dto.FrameExtractVO;
import com.superprogrammer.canvas.dto.NodeRunResult;
import com.superprogrammer.canvas.dto.StoryboardConcatRequest;
import com.superprogrammer.canvas.dto.StoryboardConcatVO;
import com.superprogrammer.canvas.dto.VideoClipRequest;
import com.superprogrammer.canvas.dto.VideoClipVO;
import com.superprogrammer.canvas.entity.Canvas;
import com.superprogrammer.canvas.service.CanvasNodeRunnerService;
import com.superprogrammer.canvas.service.CanvasService;
import com.superprogrammer.canvas.service.VideoFrameService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.R;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.file.service.StoredFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 无限画布 REST API（plan IC-14 快照持久化）。
 *
 * <p>权限 gated：所有端点 {@code @RequirePermission("canvas:write")}（切面 403 兜底）。
 * ownership：service 层硬过滤（普通用户只能操作自己的画布，admin 旁路看全量）。
 * 快照整存 JSONB，产出物走 stored_files(SOURCE_CANVAS)，端点不暴露 fileId 之外信息。
 */
@Slf4j
@RestController
@RequestMapping("/api/canvas")
@RequiredArgsConstructor
public class CanvasController {

    private final CanvasService canvasService;
    private final CanvasNodeRunnerService nodeRunnerService;
    private final FileStorageService fileStorageService;
    private final VideoFrameService videoFrameService;
    private final ObjectMapper objectMapper;

    @PostMapping
    @RequirePermission("canvas:write")
    public ResponseEntity<R<CanvasVO>> create(@Valid @RequestBody(required = false) CanvasCreateRequest req) {
        Long userId = getCurrentUserId();
        String name = req == null ? null : req.getName();
        Canvas c = canvasService.create(userId, name);
        return ResponseEntity.ok(R.ok("画布已创建", toVO(c, true)));
    }

    @GetMapping
    @RequirePermission("canvas:write")
    public ResponseEntity<R<List<CanvasVO>>> list() {
        return ResponseEntity.ok(R.ok(canvasService.list(getCurrentUserId(), isAdmin())));
    }

    @GetMapping("/{id}")
    @RequirePermission("canvas:write")
    public ResponseEntity<R<CanvasVO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(canvasService.get(id, getCurrentUserId(), isAdmin())));
    }

    @PutMapping("/{id}")
    @RequirePermission("canvas:write")
    public ResponseEntity<R<CanvasVO>> save(@PathVariable Long id,
                                            @Valid @RequestBody CanvasSaveRequest req) {
        Canvas c = canvasService.save(id, getCurrentUserId(), isAdmin(), req);
        return ResponseEntity.ok(R.ok("已保存", toVO(c, true)));
    }

    @PatchMapping("/{id}/rename")
    @RequirePermission("canvas:write")
    public ResponseEntity<R<CanvasVO>> rename(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Canvas c = canvasService.rename(id, getCurrentUserId(), isAdmin(), body.get("name"));
        return ResponseEntity.ok(R.ok("已重命名", toVO(c, false)));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("canvas:write")
    public ResponseEntity<R<Void>> delete(@PathVariable Long id) {
        canvasService.delete(id, getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("已删除", null));
    }

    // ==================== C4：节点产出触发 + 产出物上传 ====================

    /**
     * 运行单节点（plan IC-2/IC-3 起步）。无状态：回 {@link NodeRunResult}，前端合并 dataPatch 后随快照保存。
     * ownership：校验画布归属，节点运行天然绑用户（LLM/media 走各自权限）。
     */
    @PostMapping("/{id}/nodes/run")
    @RequirePermission("canvas:write")
    public ResponseEntity<R<NodeRunResult>> runNode(@PathVariable Long id,
                                                    @RequestBody CanvasNodeDTO node) {
        Long userId = getCurrentUserId();
        // 归属咽喉点：即便运行无状态，也禁止在他人画布上触发（避免用他人 canvasId 借道跑 LLM）
        canvasService.loadOwned(id, userId, isAdmin());
        return ResponseEntity.ok(R.ok(nodeRunnerService.run(node, userId)));
    }

    /**
     * 画布产出物上传（plan IC-3 图片节点 MVP / IC-5 音频 / 视频参考图通用）。
     * 落 {@code stored_files}(source={@code SOURCE_CANVAS})，返 fileId + 预览 URL。
     * 衍生/生图不覆盖原图：每次上传产新 fileId（plan R-5 只存引用不嵌 base64）。
     */
    @PostMapping("/{id}/upload")
    @RequirePermission("canvas:write")
    public ResponseEntity<R<StoredFile>> upload(@PathVariable Long id,
                                                @RequestParam("file") MultipartFile file) {
        Long userId = getCurrentUserId();
        canvasService.loadOwned(id, userId, isAdmin());
        StoredFile stored = fileStorageService.store(file, userId, StoredFileEntity.SOURCE_CANVAS);
        log.info("canvas upload: canvasId={} userId={} fileId={} mime={} size={}",
                id, userId, stored.fileId(), stored.mimeType(), stored.size());
        return ResponseEntity.ok(R.ok("已上传", stored));
    }

    // ==================== C11：视频抽帧（IC-12，R-2 javacv）====================

    /**
     * 从视频节点抽帧（首/尾/指定秒）→ 新图片文件（SOURCE_CANVAS）→ 前端建图节点 + 自动连边。
     *
     * <p>归属咽喉点：loadOwned（画布）+ loadPath（视频源文件 ownership 复检，防借他人 fileId 抽帧）。
     * 失败不产空文件（plan 边界）：service 抛 → 端点直接返错误，不落 stored_files。
     */
    @PostMapping("/{id}/nodes/{nodeId}/frames")
    @RequirePermission("canvas:write")
    public ResponseEntity<R<FrameExtractVO>> extractFrame(@PathVariable Long id,
                                                          @PathVariable String nodeId,
                                                          @RequestBody FrameExtractRequest req) {
        Long userId = getCurrentUserId();
        boolean admin = isAdmin();
        Canvas c = canvasService.loadOwned(id, userId, isAdmin());

        String sourceFileId = resolveVideoFileId(c.getSnapshot(), nodeId);
        VideoFrameService.FrameMode mode = parseFrameMode(req == null ? null : req.getMode());

        Path videoPath = fileStorageService.loadPath(sourceFileId, userId, admin);
        VideoFrameService.ExtractedFrame ef = videoFrameService.extract(videoPath, mode, req == null ? null : req.getSecond());

        String fileName = "frame_" + nodeId + "_" + mode.name().toLowerCase(Locale.ROOT) + ".jpg";
        String newFileId = fileStorageService.storeStream(
                new ByteArrayInputStream(ef.bytes()), fileName, ef.mimeType(), ef.size(),
                userId, StoredFileEntity.SOURCE_CANVAS);
        log.info("canvas frame extracted: canvasId={} sourceNodeId={} mode={} newFileId={} bytes={}",
                id, nodeId, mode, newFileId, ef.size());

        return ResponseEntity.ok(R.ok("已抽帧", FrameExtractVO.builder()
                .fileId(newFileId)
                .url("/api/files/" + newFileId)
                .mime(ef.mimeType())
                .size(ef.size())
                .sourceNodeId(nodeId)
                .build()));
    }

    // ==================== C12：视频截取（IC-13，R-2 javacv）====================

    /**
     * 从视频节点截取时间段（[startSec,endSec)）→ 新视频文件（SOURCE_CANVAS）→ 前端建视频节点 + 自动连边。
     *
     * <p>归属咽喉点：loadOwned（画布）+ loadPath（视频源文件 ownership 复检，防借他人 fileId 截取）。
     * 失败不产空文件（plan 边界）：service 抛 → 端点直接返错误，不落 stored_files。
     * 临时 mp4 文件由 try-finally 兜底删（storeStream 成败都删，防磁盘泄漏）。
     */
    @PostMapping("/{id}/nodes/{nodeId}/clip")
    @RequirePermission("canvas:write")
    public ResponseEntity<R<VideoClipVO>> clipVideo(@PathVariable Long id,
                                                    @PathVariable String nodeId,
                                                    @RequestBody VideoClipRequest req) {
        Long userId = getCurrentUserId();
        boolean admin = isAdmin();
        Canvas c = canvasService.loadOwned(id, userId, isAdmin());

        if (req == null || req.getStartSec() == null || req.getEndSec() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "截取起止秒缺失");
        }
        String sourceFileId = resolveVideoFileId(c.getSnapshot(), nodeId);

        Path videoPath = fileStorageService.loadPath(sourceFileId, userId, admin);
        VideoFrameService.ClipResult clip = videoFrameService.clip(videoPath, req.getStartSec(), req.getEndSec());
        Path clipPath = clip.tempFile();
        try {
            String fileName = "clip_" + nodeId + "_" + req.getStartSec() + "-" + req.getEndSec() + ".mp4";
            String newFileId;
            try {
                newFileId = fileStorageService.storeStream(
                        Files.newInputStream(clipPath), fileName, clip.mimeType(),
                        clip.size(), userId, StoredFileEntity.SOURCE_CANVAS);
            } catch (IOException e) {
                log.warn("canvas clip storeStream failed: canvasId={} nodeId={} err={}", id, nodeId, e.getMessage());
                throw new BusinessException(ErrorCode.UNPROCESSABLE, "截取文件落盘失败");
            }
            log.info("canvas clip stored: canvasId={} sourceNodeId={} newFileId={} size={}",
                    id, nodeId, newFileId, clip.size());
            return ResponseEntity.ok(R.ok("已截取", VideoClipVO.builder()
                    .fileId(newFileId)
                    .url("/api/files/" + newFileId)
                    .mime(clip.mimeType())
                    .size(clip.size())
                    .sourceNodeId(nodeId)
                    .build()));
        } finally {
            try {
                Files.deleteIfExists(clipPath);
            } catch (IOException ignored) {
                // 临时文件删失败不阻断（OS 临时目录定期清理兜底）
            }
        }
    }

    // ==================== C13：故事板拼接（IC-11，基础剪辑成片）====================

    /**
     * 故事板顺序拼接：把多个视频产出物按 fileIds 顺序首尾相接 → 新成片视频（SOURCE_CANVAS）→ 前端建成片节点。
     *
     * <p>归属咽喉点：loadOwned（画布）+ 每段 fileId {@code loadPath}（ownership 复检，防借他人 fileId 拼接）。
     * 失败不产空文件（plan 边界）；临时 mp4 try-finally 删。
     */
    @PostMapping("/{id}/storyboard/concat")
    @RequirePermission("canvas:write")
    public ResponseEntity<R<StoryboardConcatVO>> concatStoryboard(@PathVariable Long id,
                                                                  @RequestBody StoryboardConcatRequest req) {
        Long userId = getCurrentUserId();
        boolean admin = isAdmin();
        canvasService.loadOwned(id, userId, isAdmin());

        if (req == null || req.getFileIds() == null || req.getFileIds().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "拼接 fileId 列表为空");
        }
        // 去重保序（同 fileId 重复入列无意义，且会让成片重复同段）
        LinkedHashSet<String> unique = new LinkedHashSet<>(req.getFileIds());

        List<Path> parts = new ArrayList<>(unique.size());
        for (String fileId : unique) {
            if (fileId == null || fileId.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "拼接 fileId 缺失");
            }
            parts.add(fileStorageService.loadPath(fileId, userId, admin));
        }

        VideoFrameService.ConcatResult concat = videoFrameService.concat(parts);
        Path concatPath = concat.tempFile();
        try {
            String fileName = "storyboard_concat_" + unique.size() + "seg.mp4";
            String newFileId;
            try {
                newFileId = fileStorageService.storeStream(
                        Files.newInputStream(concatPath), fileName, concat.mimeType(),
                        concat.size(), userId, StoredFileEntity.SOURCE_CANVAS);
            } catch (IOException e) {
                log.warn("canvas concat storeStream failed: canvasId={} segments={} err={}",
                        id, unique.size(), e.getMessage());
                throw new BusinessException(ErrorCode.UNPROCESSABLE, "拼接成片落盘失败");
            }
            log.info("canvas concat stored: canvasId={} newFileId={} segments={} totalDurationSec={} size={}",
                    id, newFileId, concat.segmentCount(), concat.totalDurationMs() / 1_000, concat.size());
            return ResponseEntity.ok(R.ok("已拼接成片", StoryboardConcatVO.builder()
                    .fileId(newFileId)
                    .url("/api/files/" + newFileId)
                    .mime(concat.mimeType())
                    .size(concat.size())
                    .segmentCount(concat.segmentCount())
                    .totalDurationSec(concat.totalDurationMs() / 1_000)
                    .sourceNodeIds(List.copyOf(unique))
                    .build()));
        } finally {
            try {
                Files.deleteIfExists(concatPath);
            } catch (IOException ignored) {
                // 临时文件删失败不阻断（OS 临时目录定期清理兜底）
            }
        }
    }

    /** 从快照定位视频节点 + 取 data.fileId；非视频节点 / 无源文件 / 节点不存在 → 业务异常。 */
    private String resolveVideoFileId(String snapshot, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "节点 id 缺失");
        }
        JsonNode target = null;
        try {
            JsonNode root = objectMapper.readTree(snapshot == null ? "{}" : snapshot);
            JsonNode nodes = root.path("nodes");
            if (nodes.isArray()) {
                for (JsonNode n : nodes) {
                    if (nodeId.equals(n.path("id").asText())) {
                        target = n;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "画布快照解析失败");
        }
        if (target == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "节点不存在: " + nodeId);
        }
        if (!CanvasNodeDTO.TYPE_VIDEO.equals(target.path("type").asText())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅视频节点可抽帧");
        }
        String fileId = target.path("data").path("fileId").asText(null);
        if (fileId == null || fileId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "视频节点无源文件，无法抽帧");
        }
        return fileId;
    }

    private VideoFrameService.FrameMode parseFrameMode(String mode) {
        if (mode == null || mode.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "抽帧模式缺失");
        }
        try {
            return VideoFrameService.FrameMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "抽帧模式非法: " + mode);
        }
    }

    private CanvasVO toVO(Canvas c, boolean withSnapshot) {
        return CanvasVO.builder()
                .id(c.getId())
                .name(c.getName())
                .snapshot(withSnapshot ? c.getSnapshot() : null)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : (Long) auth.getPrincipal();
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> "ROLE_admin".equalsIgnoreCase(a) || "ROLE_ADMIN".equalsIgnoreCase(a));
    }
}
