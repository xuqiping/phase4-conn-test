package com.superprogrammer.media.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.media.config.MediaGenProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/** 为 Ark 参考媒体（视频/图片）生成短期、不可猜测的公开下载地址。 */
@Service
@RequiredArgsConstructor
public class MediaReferenceUrlService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final MediaGenProperties properties;

    public boolean isConfigured() {
        return properties.isReferenceVideoConfigured();
    }

    /** 兼容别名（视频链旧调用方/测试）；实现同 {@link #createMediaUrl}。 */
    public String createVideoUrl(String fileId) {
        return createMediaUrl(fileId);
    }

    /**
     * 修复VI（2x#5）：图片附件与视频同链路——签名公网 URL 传输（替代 base64，消除 ×4/3
     * 膨胀与「多图撑爆 64MB 请求体」隐雷）。签名/过期/端点与视频完全同参同闸。
     */
    public String createMediaUrl(String fileId) {
        if (!isConfigured()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "参考媒体功能未配置：请设置 Ark 可访问的 MEDIA_REFERENCE_PUBLIC_BASE_URL（HTTPS）和 MEDIA_REFERENCE_SIGNING_KEY");
        }
        long expires = Instant.now().getEpochSecond() + properties.getReference().getTtlSeconds();
        String signature = sign(fileId, expires);
        String baseUrl = properties.getReference().getPublicBaseUrl().strip().replaceAll("/+$", "");
        return baseUrl + "/api/media/reference/"
                + UriUtils.encodePathSegment(fileId, StandardCharsets.UTF_8)
                + "?expires=" + expires + "&sig=" + signature;
    }

    public boolean isValid(String fileId, long expires, String signature) {
        if (!isConfigured() || fileId == null || fileId.isBlank() || signature == null
                || expires <= Instant.now().getEpochSecond()) {
            return false;
        }
        byte[] actual = signature.getBytes(StandardCharsets.US_ASCII);
        byte[] expected = sign(fileId, expires).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private String sign(String fileId, long expires) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.getReference().getSigningKey().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal((fileId + "\n" + expires).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("参考视频签名生成失败", e);
        }
    }
}
