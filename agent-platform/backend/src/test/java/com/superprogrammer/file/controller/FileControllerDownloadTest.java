package com.superprogrammer.file.controller;

import com.superprogrammer.file.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 安全体系 S1 · SEC-FR-030a/b 下载端点 disposition 决策测试。
 * 直调 controller（mock service + 手工 SecurityContext），断言响应头。
 */
class FileControllerDownloadTest {

    private final FileStorageService storage = mock(FileStorageService.class);
    private final FileController controller = new FileController(storage);

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    private void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // AC-SEC-FR-030a：evil.html → attachment + octet-stream（不渲染）
    @Test
    void dangerousHtmlForcesAttachmentDownload() {
        authenticate();
        try {
            when(storage.load(anyString(), any(), anyBoolean()))
                    .thenReturn(new ByteArrayResource("<script>alert(1)</script>".getBytes()));

            ResponseEntity<Resource> resp = controller.get("uuid.html");

            assertEquals(MediaType.APPLICATION_OCTET_STREAM, resp.getHeaders().getContentType());
            assertTrue(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"));
            // AC-SEC-FR-030b：固定 nosniff
            assertEquals("nosniff", resp.getHeaders().getFirst("X-Content-Type-Options"));
        } finally {
            tearDown();
        }
    }

    // AC-SEC-FR-030a：svg（可内嵌 script）同样强制下载
    @Test
    void svgForcesAttachmentDownload() {
        authenticate();
        try {
            when(storage.load(anyString(), any(), anyBoolean()))
                    .thenReturn(new ByteArrayResource("<svg/>".getBytes()));

            ResponseEntity<Resource> resp = controller.get("uuid.svg");

            assertEquals(MediaType.APPLICATION_OCTET_STREAM, resp.getHeaders().getContentType());
            assertTrue(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"));
        } finally {
            tearDown();
        }
    }

    // 回归保护：png 维持 inline + 探测 MIME，预览不回归
    @Test
    void safePngStaysInlineWithDetectedMime() {
        authenticate();
        try {
            when(storage.load(anyString(), any(), anyBoolean()))
                    .thenReturn(new ByteArrayResource(new byte[]{1, 2, 3}));
            when(storage.detectMimeType("uuid.png")).thenReturn("image/png");

            ResponseEntity<Resource> resp = controller.get("uuid.png");

            assertEquals(MediaType.IMAGE_PNG, resp.getHeaders().getContentType());
            assertTrue(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("inline"));
            assertEquals("nosniff", resp.getHeaders().getFirst("X-Content-Type-Options"));
        } finally {
            tearDown();
        }
    }

    // 未知类型默认 attachment（安全换体验）
    @Test
    void unknownTypeDefaultsToAttachment() {
        authenticate();
        try {
            when(storage.load(anyString(), any(), anyBoolean()))
                    .thenReturn(new ByteArrayResource(new byte[]{1}));

            ResponseEntity<Resource> resp = controller.get("uuid.xyz123");

            assertEquals(MediaType.APPLICATION_OCTET_STREAM, resp.getHeaders().getContentType());
            assertTrue(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("attachment"));
        } finally {
            tearDown();
        }
    }
}
