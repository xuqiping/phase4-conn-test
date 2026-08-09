package com.superprogrammer.file.controller;

import com.superprogrammer.common.result.R;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileSecurityPolicy;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.file.service.StoredFile;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    @PostMapping("/upload")
    public ResponseEntity<R<StoredFile>> upload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(R.ok(fileStorageService.store(
                file, getCurrentUserId(), StoredFileEntity.SOURCE_WORKFLOW)));
    }

    /**
     * 取文件 —— 走 load 咽喉点强校验归属（V40 修既有 authenticated IDOR）。
     * 任何登录用户无法再凭泄露 fileId 读他人文件：owner 不匹配且非 admin → FORBIDDEN。
     *
     * <p>安全体系 S1 · F-1 存储型 XSS 修复（SEC-FR-030a/b）：按 {@link FileSecurityPolicy}
     * 判定 disposition——危险类型（html/svg/xml…）与未知类型强制 attachment + octet-stream 下载，
     * 仅安全白名单（png/mp4/pdf…）维持 inline 预览；响应固定 nosniff 防 MIME 嗅探绕过。
     */
    @GetMapping("/{fileId}")
    public ResponseEntity<Resource> get(@PathVariable String fileId) {
        Resource resource = fileStorageService.load(fileId, getCurrentUserId(), isAdmin());
        boolean inlineSafe = FileSecurityPolicy.isInlineSafe(fileId);
        MediaType contentType = inlineSafe
                ? MediaType.parseMediaType(fileStorageService.detectMimeType(fileId))
                : MediaType.APPLICATION_OCTET_STREAM;
        String disposition = (inlineSafe ? "inline" : "attachment") + "; filename=\"" + fileId + "\"";
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header("X-Content-Type-Options", "nosniff")
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
