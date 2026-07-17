package com.superprogrammer.workreport.service.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * 企业微信自建应用回调适配器。
 *
 * <p>企业微信普通群机器人不支持消息回调，只能Outgoing消息模式且能力有限；
 * 若要实现完整的 IM 交互（接收用户消息、回复菜单、确认指令），必须使用「自建应用」的回调模式。
 *
 * <p>回调消息格式为 XML，核心字段：
 * <ul>
 *   <li>Encrypt: AES 加密后的消息体</li>
 *   <li>MsgSignature / TimeStamp / Nonce: 用于验签</li>
 * </ul>
 */
@Slf4j
@Component
public class WeComWebhookAdapter implements WebhookAdapter {

    private static final String AES_MODE = "AES/CBC/NoPadding";

    @Override
    public String platform() {
        return "WECHAT_WORK";
    }

    /**
     * 验证企业微信回调签名。
     *
     * <p>签名算法：SHA1(sort(token, timestamp, nonce, encrypt/echostr))
     */
    @Override
    public boolean verifySignature(String body, String signature, String timestamp, String nonce, String secret) {
        if (signature == null || timestamp == null || nonce == null || secret == null || body == null) {
            return false;
        }
        try {
            String expected = sha1(sortAndJoin(secret, timestamp, nonce, body));
            return expected.equals(signature);
        } catch (Exception e) {
            log.error("[WeComWebhookAdapter] 验签失败", e);
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public WebhookParseResult parseMessage(Map<String, Object> payload) {
        // 解密后的 payload 通常是 Map（由 controller 解密后重新解析 JSON/XML 得到）
        String msgType = stringValue(payload.get("MsgType"));
        if (msgType == null) {
            return null;
        }

        String content = extractContent(payload);
        if (content == null || content.isBlank()) {
            return null;
        }

        String fromUser = stringValue(payload.get("FromUserName"));
        String toUser = stringValue(payload.get("ToUserName"));
        String msgId = stringValue(payload.get("MsgId"));
        if (msgId == null) {
            msgId = fromUser + "_" + System.currentTimeMillis();
        }

        return new WebhookParseResult(msgId, fromUser, toUser, content.trim(), toUser);
    }

    /**
     * 解密企业微信 XML 中的 Encrypt 字段。
     *
     * @param encryptBase64 XML 中 Encrypt 字段的 Base64 值
     * @param encodingAesKey 企业微信后台生成的 43 位 EncodingAESKey
     * @return 解密后的 XML 字符串
     */
    public String decrypt(String encryptBase64, String encodingAesKey) {
        try {
            byte[] key = aesKey(encodingAesKey);
            byte[] cipherData = Base64.getDecoder().decode(encryptBase64);

            Cipher cipher = Cipher.getInstance(AES_MODE);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
            byte[] decrypted = cipher.doFinal(cipherData);
            byte[] unpadded = pkcs7Unpad(decrypted);

            // 格式：16 字节随机前缀 + 4 字节内容长度 + 内容 + AppId
            int msgLen = ByteBuffer.wrap(unpadded, 16, 4).getInt();
            int contentStart = 20;
            if (contentStart + msgLen > unpadded.length) {
                throw new IllegalArgumentException("解密后消息长度异常");
            }
            return new String(unpadded, contentStart, msgLen, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("企业微信消息解密失败", e);
        }
    }

    private String extractContent(Map<String, Object> payload) {
        String content = stringValue(payload.get("Content"));
        if (content != null && !content.isBlank()) {
            return content;
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private byte[] aesKey(String encodingAesKey) {
        if (encodingAesKey == null || encodingAesKey.length() != 43) {
            throw new IllegalArgumentException("EncodingAESKey 长度必须为 43");
        }
        return Base64.getDecoder().decode(encodingAesKey + "=");
    }

    private byte[] pkcs7Unpad(byte[] data) {
        int padLen = data[data.length - 1] & 0xFF;
        if (padLen < 1 || padLen > 32 || padLen > data.length) {
            return data;
        }
        for (int i = 0; i < padLen; i++) {
            if (data[data.length - 1 - i] != padLen) {
                return data;
            }
        }
        return Arrays.copyOfRange(data, 0, data.length - padLen);
    }

    private String sortAndJoin(String token, String timestamp, String nonce, String encrypt) {
        String[] arr = {token, timestamp, nonce, encrypt};
        Arrays.sort(arr);
        StringBuilder sb = new StringBuilder();
        for (String s : arr) {
            sb.append(s);
        }
        return sb.toString();
    }

    private String sha1(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
