package com.superprogrammer.media.controller;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.media.service.MediaReferenceUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Ark 使用签名和过期时间拉取本地参考媒体（视频/图片——修复VI 2x#5 图片附件切 URL 传输）；
 * 除此之外不开放文件访问。音频仍走 base64 data URI（规格 VI §8 边界，不在本端点）。
 */
@RestController
@RequestMapping("/api/media/reference")
@RequiredArgsConstructor
public class MediaReferenceController {

    private final MediaReferenceUrlService referenceUrlService;
    private final FileStorageService fileStorageService;

    @GetMapping("/{fileId}")
    public ResponseEntity<Resource> download(@PathVariable String fileId,
                                             @RequestParam long expires,
                                             @RequestParam String sig) throws IOException {
        if (!referenceUrlService.isValid(fileId, expires, sig)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "参考媒体地址无效或已过期");
        }
        StoredFileEntity meta = fileStorageService.findMeta(fileId);
        String mime = meta == null || meta.getMime() == null ? "" : meta.getMime().toLowerCase();
        if (meta == null || !(mime.startsWith("video/") || mime.startsWith("image/"))) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "参考媒体不存在或不支持该类型");
        }
        Resource resource = fileStorageService.load(fileId, null, true);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(meta.getMime()))
                .contentLength(meta.getSize() == null ? resource.contentLength() : meta.getSize())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"reference-media\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }
}
