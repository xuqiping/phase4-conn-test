package com.superprogrammer.workreport.dto;

public record PushCredentialDto(
        Long id,
        String name,
        String platform,
        Boolean hasCredential
) {
}
