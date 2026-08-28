package com.superprogrammer.media.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.media.config.MediaGenProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaReferenceUrlServiceTest {

    @Test
    void createsAndVerifiesShortLivedHttpsUrl() {
        MediaReferenceUrlService service = new MediaReferenceUrlService(configuredProperties());

        String url = service.createVideoUrl("video-1.mp4");
        URI uri = URI.create(url);
        Map<String, String> query = Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> pair[1]));

        assertEquals("https", uri.getScheme());
        assertTrue(uri.getPath().endsWith("/api/media/reference/video-1.mp4"));
        assertTrue(service.isValid("video-1.mp4", Long.parseLong(query.get("expires")), query.get("sig")));
    }

    /** 修复VI（2x#5）：图片附件同链路——createMediaUrl 与视频同参同闸、同验签。 */
    @Test
    void createsMediaUrlForImageAttachmentsAndRejectsTamper() {
        MediaReferenceUrlService service = new MediaReferenceUrlService(configuredProperties());

        String url = service.createMediaUrl("ref-image.png");
        URI uri = URI.create(url);
        Map<String, String> query = Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> pair[1]));

        assertEquals("https", uri.getScheme());
        assertTrue(uri.getPath().endsWith("/api/media/reference/ref-image.png"));
        long expires = Long.parseLong(query.get("expires"));
        assertTrue(service.isValid("ref-image.png", expires, query.get("sig")));
        assertFalse(service.isValid("ref-image.png", expires, query.get("sig") + "x"));
        assertFalse(service.isValid("other.png", expires, query.get("sig")));
    }

    @Test
    void rejectsExpiredOrTamperedSignature() {
        MediaReferenceUrlService service = new MediaReferenceUrlService(configuredProperties());

        assertFalse(service.isValid("video-1.mp4", 1L, "bad"));
        assertFalse(service.isValid("other.mp4", Long.MAX_VALUE / 1000, "bad"));
    }

    @Test
    void missingOrNonHttpsConfigurationFailsClosed() {
        MediaReferenceUrlService missingService = new MediaReferenceUrlService(new MediaGenProperties());
        assertFalse(missingService.isConfigured());
        assertThrows(BusinessException.class, () -> missingService.createVideoUrl("video-1.mp4"));

        MediaGenProperties http = configuredProperties();
        http.getReference().setPublicBaseUrl("http://localhost:8080");
        assertFalse(new MediaReferenceUrlService(http).isConfigured());

        MediaGenProperties localHttps = configuredProperties();
        localHttps.getReference().setPublicBaseUrl("https://localhost:8080");
        assertFalse(new MediaReferenceUrlService(localHttps).isConfigured());
    }

    private MediaGenProperties configuredProperties() {
        MediaGenProperties properties = new MediaGenProperties();
        properties.getReference().setPublicBaseUrl("https://media.example.com/base/");
        properties.getReference().setSigningKey("test-secret-at-least-32-bytes-long");
        properties.getReference().setTtlSeconds(900);
        return properties;
    }
}
