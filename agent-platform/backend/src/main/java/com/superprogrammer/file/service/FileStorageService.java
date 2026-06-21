package com.superprogrammer.file.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

@Service
public class FileStorageService {

    private final Path storageRoot;

    public FileStorageService(@Value("${app.files.storage-dir:uploads/workflow-inputs}") String storageDir) {
        this.storageRoot = Paths.get(storageDir).toAbsolutePath().normalize();
    }

    public StoredFile store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File must not be empty");
        }
        String originalName = StringUtils.cleanPath(
                file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                        ? "file"
                        : file.getOriginalFilename());
        String fileId = UUID.randomUUID() + extensionOf(originalName);
        Path target = resolveSafe(fileId);

        try {
            Files.createDirectories(storageRoot);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store uploaded file", e);
        }

        String mimeType = file.getContentType();
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = "application/octet-stream";
        }

        return new StoredFile(
                fileId,
                "/api/files/" + fileId,
                originalName,
                mimeType,
                file.getSize());
    }

    public Resource load(String fileId) {
        Path path = resolveSafe(fileId);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not found: " + fileId);
        }
        return new FileSystemResource(path);
    }

    public String detectMimeType(String fileId) {
        try {
            String mimeType = Files.probeContentType(resolveSafe(fileId));
            return mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType;
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    private Path resolveSafe(String fileId) {
        if (fileId == null || fileId.isBlank() || fileId.contains("/") || fileId.contains("\\")) {
            throw new IllegalArgumentException("Invalid file id");
        }
        Path target = storageRoot.resolve(fileId).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Invalid file id");
        }
        return target;
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        String extension = filename.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,12}") ? extension : "";
    }
}
