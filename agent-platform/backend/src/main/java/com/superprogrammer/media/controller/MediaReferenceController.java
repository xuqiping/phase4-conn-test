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

/** Ark 使用签名和过期时间拉取本地参考视频；除此之外不开放文件访问。 */
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
            throw new BusinessException(ErrorCode.FORBIDDEN, "参考视频地址无效或已过期");
        }
        StoredFileEntity meta = fileStorageService.findMeta(fileId);
        if (meta == null || meta.getMime() == null || !meta.getMime().toLowerCase().startsWith("video/")) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "参考视频不存在");
        }
        Resource resource = fileStorageService.load(fileId, null, true);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(meta.getMime()))
                .contentLength(meta.getSize() == null ? resource.contentLength() : meta.getSize())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"reference-video\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }
}
