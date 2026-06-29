package com.superprogrammer.workreport.service.webhook;

import java.util.Map;

public interface WebhookAdapter {

    String platform();

    boolean verifySignature(String body, String signature, String timestamp, String nonce, String secret);

    WebhookParseResult parseMessage(Map<String, Object> payload);
}
