package com.superprogrammer.authorization.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

public final class OfflineTokenSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String SEPARATOR = "|";

    private OfflineTokenSigner() {
    }

    public static String sign(String secret, Long userId, String deviceId, OffsetDateTime offlineUsableUntil, List<String> allowedModules) {
        String payload = buildPayload(userId, deviceId, offlineUsableUntil, allowedModules);
        String signature = hmac(secret, payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString((payload + SEPARATOR + signature).getBytes(StandardCharsets.UTF_8));
    }

    public static OfflineTokenPayload verify(String secret, String token) {
        String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        int separatorIndex = decoded.lastIndexOf(SEPARATOR);
        if (separatorIndex < 0) {
            throw new IllegalArgumentException("Invalid offline token format");
        }
        String payload = decoded.substring(0, separatorIndex);
        String signature = decoded.substring(separatorIndex + 1);
        String expected = hmac(secret, payload);
        if (!constantTimeEquals(signature, expected)) {
            throw new IllegalArgumentException("Offline token signature mismatch");
        }
        return parsePayload(payload);
    }

    private static String buildPayload(Long userId, String deviceId, OffsetDateTime offlineUsableUntil, List<String> allowedModules) {
        return userId + SEPARATOR
                + deviceId + SEPARATOR
                + offlineUsableUntil.toInstant().toEpochMilli() + SEPARATOR
                + String.join(",", allowedModules);
    }

    private static OfflineTokenPayload parsePayload(String payload) {
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid offline token payload");
        }
        long epochMilli = Long.parseLong(parts[2]);
        List<String> modules = parts[3].isEmpty() ? List.of() : List.of(parts[3].split(","));
        return new OfflineTokenPayload(Long.parseLong(parts[0]), parts[1], epochMilli, modules);
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to sign offline token", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        if (aBytes.length != bBytes.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        return result == 0;
    }

    public record OfflineTokenPayload(Long userId, String deviceId, long offlineUsableUntilEpochMilli, List<String> allowedModules) {
    }
}
