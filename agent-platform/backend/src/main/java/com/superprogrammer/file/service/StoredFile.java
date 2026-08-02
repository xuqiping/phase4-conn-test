package com.superprogrammer.file.service;

public record StoredFile(
        String fileId,
        String url,
        String name,
        String mimeType,
        long size
) {
}
