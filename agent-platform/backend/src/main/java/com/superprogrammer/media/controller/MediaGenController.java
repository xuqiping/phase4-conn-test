package com.superprogrammer.media.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.media.dto.ImageModelVO;
import com.superprogrammer.media.dto.ImageSubmitRequest;
import com.superprogrammer.media.dto.MediaSubmitRequest;
import com.superprogrammer.media.dto.MediaTaskVO;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.service.MediaGenQueryService;
import com.superprogrammer.media.service.MediaGenTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 媒体生成 REST API（spec SD-4）。
 *
 * <p>权限 gated：所有端点 {@code @RequirePermission("media:gen")}（切面 403 兜底）。
 * ownership：普通用户只能查/下载自己的任务；admin 看全量。下载端点 Content-Disposition 附件（防 inline 执行）。
 */
@Slf4j
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaGenController {

    private final MediaGenTaskService taskService;
    private final MediaGenQueryService queryService;
    private final FileStorageService fileStorageService;
    private final com.superprogrammer.media.service.MediaModelService mediaModelService;
    private final com.superprogrammer.media.provider.ArkSeedanceProvider arkSeedanceProvider;

    @PostMapping("/video")
    @RequirePermission("media:gen")
    public ResponseEntity<R<Map<String, Object>>> submit(@Valid @RequestBody MediaSubmitRequest request) {
        String taskType = request.getTaskType() == null || request.getTaskType().isBlank()
                ? MediaGenTask.TYPE_TEXT2VIDEO : request.getTaskType();
        String ratio = request.getRatio() == null || request.getRatio().isBlank()
                ? "16:9" : request.getRatio();
        Integer duration = request.getDuration() == null ? 5 : request.getDuration();
        String resolution = request.getResolution() == null || request.getResolution().isBlank()
                ? "720p" : request.getResolution();
        Long taskId = taskService.submit(
                request.getPrompt(), ratio, duration, resolution,
                request.getWatermark(), request.getGenerateAudio(), taskType,
                request.getRefFileId(), request.getAttachments(),
                request.getModel(), getCurrentUserId(), isAdmin(), request.getFrameRole());
        return ResponseEntity.ok(R.ok("任务已提交", Map.of("id", taskId, "status", MediaGenTask.STATUS_PENDING)));
    }

    /**
     * 可选视频模型目录（含每模型能力画像：附件上限/比例/分辨率/时长）。
     * 前端据此渲染模型下拉 + 动态附件上传区。
     */
    @GetMapping("/models")
    @RequirePermission("media:gen")
    public ResponseEntity<R<List<com.superprogrammer.media.dto.MediaModelVO>>> models() {
        return ResponseEntity.ok(R.ok(mediaModelService.listModels()));
    }

    /**
     * 生图任务提交（Seedream 同步生图，按张计费）。
     * 参数按模型能力清单校验；参考图从资产库 file_id 选取。
     */
    @PostMapping("/image")
    @RequirePermission("media:gen")
    public ResponseEntity<R<Map<String, Object>>> submitImage(@Valid @RequestBody ImageSubmitRequest request) {
        Long taskId = taskService.submitImage(
                request.getPrompt(), request.getRefFileIds(), request.getSize(), request.getOutputFormat(),
                request.getWatermark(), request.getGuidanceScale(), request.getOptimizeMode(),
                request.getSequential(), request.getMaxImages(), request.getWebSearch(),
                request.getModel(), getCurrentUserId(), isAdmin());
        return ResponseEntity.ok(R.ok("任务已提交", Map.of("id", taskId, "status", MediaGenTask.STATUS_PENDING)));
    }

    /**
     * 可选生图模型目录（含每模型能力清单 ImageModelCapability：参考图上限/size枚举/组图/联网/引导尺度等）。
     * 前端据此渲染模型下拉 + 数据驱动动态表单（按 supportsXxx 显隐控件、按 List 枚举填下拉）。
     */
    @GetMapping("/image/models")
    @RequirePermission("media:gen")
    public ResponseEntity<R<List<ImageModelVO>>> imageModels() {
        return ResponseEntity.ok(R.ok(mediaModelService.listImageModels()));
    }

    /**
     * VIDEO provider 连通性测试（供应商管理页「测试」按钮，category=VIDEO 分流到这里）。
     * 零成本探测：GET 任务端点/不存在id，按状态码判定端点+Key 有效性，不建任务不计费。
     * 权限与 /api/llm/providers 管理端点一致（role:manage），非 media:gen。
     */
    @PostMapping("/providers/{id}/test")
    @RequirePermission("role:manage")
    public ResponseEntity<R<com.superprogrammer.llm.dto.TestConnectionResult>> testMediaProvider(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(arkSeedanceProvider.testConnection(id)));
    }

    @GetMapping("/tasks/{id}")
    @RequirePermission("media:gen")
    public ResponseEntity<R<MediaTaskVO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(queryService.get(id, getCurrentUserId(), isAdmin())));
    }

    @GetMapping("/tasks")
    @RequirePermission("media:gen")
    public ResponseEntity<R<List<MediaTaskVO>>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String kind) {
        return ResponseEntity.ok(R.ok(queryService.list(getCurrentUserId(), isAdmin(), q, from, to, limit, kind)));
    }

    @GetMapping("/tasks/{id}/download")
    @RequirePermission("media:gen")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        boolean admin = isAdmin();
        MediaGenTask task = queryService.loadForDownload(id, userId, admin);
        return serveFile(task.getResultFileId(), id, userId, admin, "task-" + id, ".mp4", "video/mp4");
    }

    /**
     * 图片任务逐张下载：{idx} 对应 result_meta.imageFileIds 顺序（0-based）。
     * 归属门控 + Content-Disposition 附件（同视频 download，防 inline 执行）。
     */
    @GetMapping("/tasks/{id}/images/{idx}/download")
    @RequirePermission("media:gen")
    public ResponseEntity<Resource> downloadImage(@PathVariable Long id, @PathVariable int idx) {
        Long userId = getCurrentUserId();
        boolean admin = isAdmin();
        String fileId = queryService.loadImageFileId(id, idx, userId, admin);
        return serveFile(fileId, id, userId, admin, "task-" + id + "-" + idx, ".png", "image/png");
    }

    /** 通用文件下发：load + findMeta → Content-Disposition 附件。视频/图片共用（只差默认名/mime）。 */
    private ResponseEntity<Resource> serveFile(String fileId, Long id, Long userId, boolean admin,
                                               String nameFallback, String extFallback, String mimeFallback) {
        Resource resource = fileStorageService.load(fileId, userId, admin);
        StoredFileEntity meta = fileStorageService.findMeta(fileId);
        String mime = meta != null && meta.getMime() != null ? meta.getMime() : mimeFallback;
        String filename = meta != null && meta.getOriginalName() != null && !meta.getOriginalName().isBlank()
                ? meta.getOriginalName() : (nameFallback + extFallback);
        String disposition = "attachment; filename=\"" + URLEncoder.encode(filename, StandardCharsets.UTF_8) + "\"";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType(mime))
                .body(resource);
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
