package com.superprogrammer.file.controller;

import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import com.superprogrammer.common.security.SecurityEventPublisher;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileSecurityPolicy;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.file.service.StoredFile;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
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

import java.util.Map;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    /**
     * 11x 加固 P3-C9：下载咽喉发 KIND_DATA_EXFIL（数据外带规则消费，count=1/次）。
     * 字段注入 required=false——横切可选依赖，@WebMvcTest 切片/单测无此 Bean 时静默跳过（不破坏既有测试）。
     */
    @Autowired(required = false)
    private SecurityEventPublisher securityEventPublisher;

    @PostMapping("/upload")
    @AuditLog(module = "system", action = "upload_file", targetType = "file")
    // 安全体系 S4 · SEC-FR-124：上传频率限制（5 入口共用 upload_file 动作，L5 补齐）
    @com.superprogrammer.common.ratelimit.RateLimit(action = "upload_file", max = 10, windowSeconds = 60,
            algo = com.superprogrammer.common.ratelimit.RateLimit.RateLimitAlgo.SLIDING)
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
     *
     * <p>6x#2 跨会话缓存：响应 {@code private, no-cache + ETag}，浏览器每次带 If-None-Match
     * 回源再验证——Spring MVC 对 ResponseEntity eTag 自动做条件请求短路（命中返 304 零 body）。
     * 复验请求仍完整走 load 归属校验与 DATA_EXFIL 计数（304 也算一次访问），审计不打折。
     * ETag=fileId+size：文件被替换（size 变）或换文件（fileId 变）缓存自动失效。
     */
    @GetMapping("/{fileId}")
    public ResponseEntity<Resource> get(@PathVariable String fileId) {
        Long userId = getCurrentUserId();
        Resource resource = fileStorageService.load(fileId, userId, isAdmin());
        // 11x P3-C9：下载成功才计数（load 抛异常=未外带），单文件 count=1
        if (securityEventPublisher != null) {
            securityEventPublisher.publish(ApplicationSecurityEvent.KIND_DATA_EXFIL, userId,
                    Map.of("resourceType", "file", "count", 1));
        }
        boolean inlineSafe = FileSecurityPolicy.isInlineSafe(fileId);
        MediaType contentType = inlineSafe
                ? MediaType.parseMediaType(fileStorageService.detectMimeType(fileId))
                : MediaType.APPLICATION_OCTET_STREAM;
        String disposition = (inlineSafe ? "inline" : "attachment") + "; filename=\"" + fileId + "\"";
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noCache().cachePrivate());
        StoredFileEntity meta = fileStorageService.findMeta(fileId);
        if (meta != null) {
            // 对齐 MediaGenController.serveFile：ETag 由 fileId+size 组成
            builder = builder.eTag("\"" + fileId + "-" + (meta.getSize() != null ? meta.getSize() : 0) + "\"");
        }
        return builder.body(resource);
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
