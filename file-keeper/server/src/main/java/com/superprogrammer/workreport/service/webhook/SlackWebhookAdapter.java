package com.superprogrammer.workreport.service.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Component
public class SlackWebhookAdapter implements WebhookAdapter {

    private static final String CHALLENGE = "url_verification";
    private static final String MESSAGE_EVENT = "message";

    @Override
    public String platform() {
        return "SLACK";
    }

    @Override
    public boolean verifySignature(String body, String signature, String timestamp, String nonce, String secret) {
        if (signature == null || timestamp == null || secret == null) {
            return false;
        }
        try {
            String baseString = "v0:" + timestamp + ":" + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sign = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));
            String expected = "v0=" + bytesToHex(sign);
            return expected.equals(signature);
        } catch (Exception e) {
            log.error("[SlackWebhookAdapter] 验签失败", e);
            return false;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Override
    @SuppressWarnings("unchecked")
    public WebhookParseResult parseMessage(Map<String, Object> payload) {
        Map<String, Object> event = (Map<String, Object>) payload.get("event");
        if (event == null) {
            return null;
        }
        if (!MESSAGE_EVENT.equals(event.get("type"))) {
            return null;
        }
        String text = event.get("text") == null ? null : event.get("text").toString();
        if (text == null || text.isBlank()) {
            return null;
        }

        String senderId = event.get("user") == null ? null : event.get("user").toString();
        String senderName = "Unknown";
        String channel = event.get("channel") == null ? null : event.get("channel").toString();
        String messageId = event.get("ts") == null
                ? senderId + "_" + System.currentTimeMillis()
                : event.get("ts").toString();

        return new WebhookParseResult(messageId, senderId, senderName, text.trim(), channel);
    }

    public boolean isChallenge(Map<String, Object> payload) {
        return CHALLENGE.equals(payload.get("type"));
    }

    public String extractChallenge(Map<String, Object> payload) {
        return payload.get("challenge") == null ? null : payload.get("challenge").toString();
    }
}
