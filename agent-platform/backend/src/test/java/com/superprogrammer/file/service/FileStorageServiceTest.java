package com.superprogrammer.file.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void store_savesFileAndReturnsStructuredReference() throws Exception {
        FileStorageService service = new FileStorageService(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "prompt.png",
                "image/png",
                new byte[]{1, 2, 3});

        StoredFile result = service.store(file);

        assertThat(result.fileId()).endsWith(".png");
        assertThat(result.url()).isEqualTo("/api/files/" + result.fileId());
        assertThat(result.name()).isEqualTo("prompt.png");
        assertThat(result.mimeType()).isEqualTo("image/png");
        assertThat(result.size()).isEqualTo(3);
        assertThat(Files.exists(tempDir.resolve(result.fileId()))).isTrue();
    }

    @Test
    void store_rejectsEmptyFile() {
        FileStorageService service = new FileStorageService(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[]{});

        assertThatThrownBy(() -> service.store(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File must not be empty");
    }
}
