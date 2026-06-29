package com.superprogrammer.workreport.service.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Component
public class DingTalkWebhookAdapter implements WebhookAdapter {

    @Override
    public String platform() {
        return "DINGTALK";
    }

    @Override
    public boolean verifySignature(String body, String signature, String timestamp, String nonce, String secret) {
        if (signature == null || timestamp == null || secret == null) {
            return false;
        }
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(signData);
            return expected.equals(signature);
        } catch (Exception e) {
            log.error("[DingTalkWebhookAdapter] 验签失败", e);
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public WebhookParseResult parseMessage(Map<String, Object> payload) {
        Object textObj = payload.get("text");
        if (!(textObj instanceof Map)) {
            return null;
        }
        Map<String, Object> textMap = (Map<String, Object>) textObj;
        String content = textMap.get("content") == null ? null : textMap.get("content").toString();
        if (content == null || content.isBlank()) {
            return null;
        }

        String senderId = payload.get("senderStaffId") == null ? null : payload.get("senderStaffId").toString();
        String senderName = payload.get("senderNick") == null ? "Unknown" : payload.get("senderNick").toString();
        String chatId = payload.get("openConversationId") == null
                ? senderId
                : payload.get("openConversationId").toString();
        String messageId = payload.get("msgId") == null
                ? senderId + "_" + System.currentTimeMillis()
                : payload.get("msgId").toString();

        return new WebhookParseResult(messageId, senderId, senderName, content.trim(), chatId);
    }
}
