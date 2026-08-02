package com.superprogrammer.file.service;

import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.mapper.StoredFileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/**
 * FileStorageService 单元测（磁盘落盘 + 归属登记行写入）。
 * 归属强制（FORBIDDEN/404/admin 越权）e2e 见 {@link FileStorageOwnershipIT}（真 PG）。
 */
@ExtendWith(MockitoExtension.class)
class FileStorageServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    StoredFileMapper storedFileMapper;

    private FileStorageService service;

    @BeforeEach
    void setUp() {
        service = new FileStorageService(tempDir.toString(), storedFileMapper);
    }

    @Test
    void store_savesFileAndRegistersOwnerRow() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "prompt.png", "image/png", new byte[]{1, 2, 3});

        StoredFile result = service.store(file, 7L, StoredFileEntity.SOURCE_KB);

        assertThat(result.fileId()).endsWith(".png");
        assertThat(result.url()).isEqualTo("/api/files/" + result.fileId());
        assertThat(result.name()).isEqualTo("prompt.png");
        assertThat(result.size()).isEqualTo(3);
        assertThat(Files.exists(tempDir.resolve(result.fileId()))).isTrue();

        ArgumentCaptor<StoredFileEntity> captor = ArgumentCaptor.forClass(StoredFileEntity.class);
        verify(storedFileMapper).insert(captor.capture());
        StoredFileEntity row = captor.getValue();
        assertThat(row.getFileId()).isEqualTo(result.fileId());
        assertThat(row.getOwnerUserId()).isEqualTo(7L);
        assertThat(row.getSource()).isEqualTo(StoredFileEntity.SOURCE_KB);
        assertThat(row.getStatus()).isEqualTo(StoredFileEntity.STATUS_ACTIVE);
    }

    @Test
    void store_rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[]{});

        assertThatThrownBy(() -> service.store(file, 7L, StoredFileEntity.SOURCE_KB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File must not be empty");
    }
}
