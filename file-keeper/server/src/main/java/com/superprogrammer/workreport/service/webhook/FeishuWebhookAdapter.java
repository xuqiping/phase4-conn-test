package com.superprogrammer.workreport.service.webhook;

import com.superprogrammer.common.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Component
public class FeishuWebhookAdapter implements WebhookAdapter {

    private static final String CHALLENGE = "url_verification";
    private static final String MESSAGE_EVENT = "im.message.receive_v1";

    @Override
    public String platform() {
        return "FEISHU";
    }

    @Override
    public boolean verifySignature(String body, String signature, String timestamp, String nonce, String secret) {
        if (signature == null || timestamp == null || nonce == null || secret == null) {
            return false;
        }
        try {
            String baseString = timestamp + nonce + secret + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sign = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(sign);
            return expected.equals(signature);
        } catch (Exception e) {
            log.error("[FeishuWebhookAdapter] 验签失败", e);
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public WebhookParseResult parseMessage(Map<String, Object> payload) {
        Map<String, Object> event = (Map<String, Object>) payload.get("event");
        if (event == null) {
            return null;
        }
        Map<String, Object> message = (Map<String, Object>) event.get("message");
        if (message == null) {
            return null;
        }
        String messageId = (String) message.get("message_id");
        String chatId = (String) message.get("chat_id");

        Map<String, Object> contentMap = JsonUtils.parseMap((String) message.getOrDefault("content", "{}"));
        String text = (String) contentMap.get("text");
        if (text == null || text.isBlank()) {
            return null;
        }

        Map<String, Object> sender = (Map<String, Object>) event.get("sender");
        String senderId = sender != null ? (String) sender.get("sender_id") : null;
        String senderName = "Unknown";
        if (sender != null) {
            Map<String, Object> senderInfo = (Map<String, Object>) sender.get("sender_info");
            if (senderInfo != null && senderInfo.get("name") != null) {
                senderName = (String) senderInfo.get("name");
            }
        }

        return new WebhookParseResult(messageId, senderId, senderName, text.trim(), chatId);
    }

    public boolean isChallenge(Map<String, Object> payload) {
        return CHALLENGE.equals(payload.get("type"));
    }

    public String extractChallenge(Map<String, Object> payload) {
        return (String) payload.get("challenge");
    }

    @SuppressWarnings("unchecked")
    public boolean isMessageEvent(Map<String, Object> payload) {
        Object header = payload.get("header");
        if (!(header instanceof Map)) {
            return false;
        }
        return MESSAGE_EVENT.equals(((Map<String, Object>) header).get("event_type"));
    }
}
