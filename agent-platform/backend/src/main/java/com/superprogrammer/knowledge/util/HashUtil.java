package com.superprogrammer.knowledge.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 十六进制哈希工具。
 * 用于 knowledge_nodes.content_hash、knowledge_index_jobs.idempotency_key 等不变式（I1/I4）。
 */
public final class HashUtil {

    private HashUtil() {
    }

    public static String sha256(String text) {
        return sha256(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes == null ? new byte[0] : bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 为 JDK 内置算法，理论上不会缺失
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
