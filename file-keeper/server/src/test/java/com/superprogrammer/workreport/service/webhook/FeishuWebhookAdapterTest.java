package com.superprogrammer.workreport.service.webhook;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuWebhookAdapterTest {

    private final FeishuWebhookAdapter adapter = new FeishuWebhookAdapter();

    @Test
    void shouldRejectWrongSignature() {
        boolean result = adapter.verifySignature("body", "wrong", "ts", "nonce", "secret");
        assertThat(result).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldParseMessageEvent() {
        Map<String, Object> payload = Map.of(
            "event", Map.of(
                "message", Map.of(
                    "message_id", "om_123",
                    "chat_id", "oc_123",
                    "content", "{\"text\":\"完成日报设计\"}"
                ),
                "sender", Map.of(
                    "sender_id", "ou_123",
                    "sender_info", Map.of("name", "张三")
                )
            )
        );

        WebhookParseResult result = adapter.parseMessage(payload);
        assertThat(result).isNotNull();
        assertThat(result.platformMessageId()).isEqualTo("om_123");
        assertThat(result.senderId()).isEqualTo("ou_123");
        assertThat(result.senderName()).isEqualTo("张三");
        assertThat(result.rawText()).isEqualTo("完成日报设计");
        assertThat(result.chatId()).isEqualTo("oc_123");
    }
}
