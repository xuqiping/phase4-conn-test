package com.superprogrammer.media.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.media.dto.MediaSubmitRequest;
import com.superprogrammer.media.dto.MediaTaskVO;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.service.MediaGenQueryService;
import com.superprogrammer.media.service.MediaGenTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
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
                request.getRefFileId(), request.getModel(), getCurrentUserId());
        return ResponseEntity.ok(R.ok("任务已提交", Map.of("id", taskId, "status", MediaGenTask.STATUS_PENDING)));
    }

    @GetMapping("/tasks/{id}")
    @RequirePermission("media:gen")
    public ResponseEntity<R<MediaTaskVO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(queryService.get(id, getCurrentUserId(), isAdmin())));
    }

    @GetMapping("/tasks")
    @RequirePermission("media:gen")
    public ResponseEntity<R<List<MediaTaskVO>>> list(@RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(R.ok(queryService.list(getCurrentUserId(), isAdmin(), limit)));
    }

    @GetMapping("/tasks/{id}/download")
    @RequirePermission("media:gen")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        boolean admin = isAdmin();
        MediaGenTask task = queryService.loadForDownload(id, userId, admin);
        Resource resource = fileStorageService.load(task.getResultFileId(), userId, admin);
        StoredFileEntity meta = fileStorageService.findMeta(task.getResultFileId());
        String mime = meta != null && meta.getMime() != null ? meta.getMime() : "video/mp4";
        String filename = meta != null && meta.getOriginalName() != null && !meta.getOriginalName().isBlank()
                ? meta.getOriginalName() : ("task-" + id + ".mp4");
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
