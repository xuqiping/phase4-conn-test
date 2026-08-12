package com.superprogrammer.media.edit.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.media.edit.dto.EditSpec;
import com.superprogrammer.media.edit.dto.MediaAssetVO;
import com.superprogrammer.media.edit.dto.MediaEditTaskVO;
import com.superprogrammer.media.edit.entity.MediaEditTask;
import com.superprogrammer.media.edit.config.MediaEditProperties;
import com.superprogrammer.media.edit.service.DraftExportService;
import com.superprogrammer.media.edit.service.MediaAssetService;
import com.superprogrammer.media.edit.service.MediaEditQueryService;
import com.superprogrammer.media.edit.service.MediaEditTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 视频剪辑 REST API（FR-ED5/ED6/ED7）。
 *
 * <p>权限 gated：所有端点 {@code @RequirePermission("media:edit")}（切面 403 兜底）。
 * ownership：普通用户只能查/下载自己的任务；admin 看全量。下载端点 Content-Disposition 附件（防 inline 执行）。
 *
 * <p>请求体直接用 {@link EditSpec}（无 SubmitRequest 包装：剪辑意图即请求，YAGNI，偏离 plan 备注）。
 */
@Slf4j
@RestController
@RequestMapping("/api/media/edit")
@RequiredArgsConstructor
public class MediaEditController {

    private final MediaEditTaskService taskService;
    private final MediaEditQueryService queryService;
    private final MediaAssetService assetService;
    private final FileStorageService fileStorageService;
    private final DraftExportService draftExportService;
    private final MediaEditProperties properties;

    /** 列素材库（已生成视频）。 */
    @GetMapping("/assets")
    @RequirePermission("media:edit")
    public ResponseEntity<R<List<MediaAssetVO>>> assets() {
        return ResponseEntity.ok(R.ok(assetService.listGeneratedAssets(getCurrentUserId(), isAdmin())));
    }

    /** 提交剪辑渲染任务：先校验素材（归属/格式/上限）并规范化成 V2，再建 PENDING 行。 */
    @PostMapping("/submit")
    @RequirePermission("media:edit")
    public ResponseEntity<R<Map<String, Object>>> submit(@Valid @RequestBody EditSpec spec) {
        Long userId = getCurrentUserId();
        boolean admin = isAdmin();
        log.info("收到剪辑提交 edit_spec={}", spec);
        EditSpec normalized = assetService.validate(spec, userId, admin); // 归属/格式/上限校验 + V2 规范化
        Long taskId = taskService.submit(normalized, userId);
        return ResponseEntity.ok(R.ok("任务已提交", Map.of("id", taskId, "status", MediaEditTask.STATUS_PENDING)));
    }

    @GetMapping("/tasks/{id}")
    @RequirePermission("media:edit")
    public ResponseEntity<R<MediaEditTaskVO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(queryService.get(id, getCurrentUserId(), isAdmin())));
    }

    @GetMapping("/tasks")
    @RequirePermission("media:edit")
    public ResponseEntity<R<List<MediaEditTaskVO>>> list(@RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(R.ok(queryService.list(getCurrentUserId(), isAdmin(), limit)));
    }

    @GetMapping("/tasks/{id}/download")
    @AuditLog(module = "media", action = "download_edit_video", targetType = "media_edit_task")
    @RequirePermission("media:edit")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        boolean admin = isAdmin();
        MediaEditTask task = queryService.loadForDownload(id, userId, admin);
        Resource resource = fileStorageService.load(task.getResultFileId(), userId, admin);
        StoredFileEntity meta = fileStorageService.findMeta(task.getResultFileId());
        String mime = meta != null && meta.getMime() != null ? meta.getMime() : "video/mp4";
        String filename = meta != null && meta.getOriginalName() != null && !meta.getOriginalName().isBlank()
                ? meta.getOriginalName() : ("edit-" + id + ".mp4");
        String disposition = "attachment; filename=\"" + URLEncoder.encode(filename, StandardCharsets.UTF_8) + "\"";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType(mime))
                .body(resource);
    }

    /**
     * 导出剪映草稿（zip）：从 EditSpec 直接生成 draft_content.json + draft_meta_info.json + 素材，不渲染、不建任务。
     * prepare 同步执行（归属 probe + 预检 + normalize，出错抛 4xx），streamZip 流式写 zip。
     * @param absolutePath true=素材 path 写服务器绝对路径（不打包，仅同文件系统可用）；false=打包素材进 zip（可移植，默认）
     */
    @PostMapping("/export-draft")
    @RequirePermission("media:edit")
    public ResponseEntity<StreamingResponseBody> exportDraft(@Valid @RequestBody EditSpec spec,
                                                             @RequestParam(defaultValue = "false") boolean absolutePath) {
        Long userId = getCurrentUserId();
        boolean admin = isAdmin();
        String draftName = "futurex-draft-" + LocalDate.now();
        boolean bundle = properties.isDraftBundleMedia() && !absolutePath;
        DraftExportService.DraftContext ctx = draftExportService.prepare(spec, userId, admin, draftName, bundle);
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(draftName + ".zip", StandardCharsets.UTF_8));
        h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        StreamingResponseBody body = out -> draftExportService.streamZip(ctx, out);
        return ResponseEntity.ok().headers(h).body(body);
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
