package com.superprogrammer.workreport.dto;

public record PushTargetDto(
        Long id,
        String name,
        String platform,
        String targetType,
        String targetId,
        Long credentialId,
        String credentialName
) {
}
