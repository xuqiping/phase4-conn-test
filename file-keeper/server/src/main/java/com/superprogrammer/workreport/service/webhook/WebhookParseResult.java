package com.superprogrammer.workreport.service.webhook;

public record WebhookParseResult(
        String platformMessageId,
        String senderId,
        String senderName,
        String rawText,
        String chatId
) {
}
