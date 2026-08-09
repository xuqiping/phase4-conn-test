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

    // ============================ 安全体系 S1 · SEC-FR-030c 上传白名单 ============================

    // AC-SEC-FR-030c：危险类型（html/svg）在 store 咽喉点拒收 → 40010
    @Test
    void store_rejectsDangerousHtmlUpload() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.html", "text/html", "<script>alert(1)</script>".getBytes());

        assertThatThrownBy(() -> service.store(file, 7L, StoredFileEntity.SOURCE_WORKFLOW))
                .isInstanceOf(com.superprogrammer.common.exception.BusinessException.class)
                .hasMessageContaining("文件类型不允许上传");
    }

    @Test
    void store_rejectsSvgAndExecutableUpload() {
        MockMultipartFile svg = new MockMultipartFile("file", "evil.svg", "image/svg+xml", "<svg/>".getBytes());
        MockMultipartFile exe = new MockMultipartFile("file", "evil.exe", "application/x-msdownload", new byte[]{1});

        assertThatThrownBy(() -> service.store(svg, 7L, StoredFileEntity.SOURCE_CANVAS))
                .isInstanceOf(com.superprogrammer.common.exception.BusinessException.class);
        assertThatThrownBy(() -> service.store(exe, 7L, StoredFileEntity.SOURCE_ASSET))
                .isInstanceOf(com.superprogrammer.common.exception.BusinessException.class);
    }

    // AC-SEC-FR-030c：正常生产资料（png/docx/mp4）不受影响
    @Test
    void store_allowsWhitelistedProductivityFiles() {
        MockMultipartFile docx = new MockMultipartFile(
                "file", "report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{1, 2});
        MockMultipartFile mp4 = new MockMultipartFile("file", "clip.mp4", "video/mp4", new byte[]{3});

        assertThat(service.store(docx, 7L, StoredFileEntity.SOURCE_KB).fileId()).endsWith(".docx");
        assertThat(service.store(mp4, 7L, StoredFileEntity.SOURCE_CANVAS).fileId()).endsWith(".mp4");
    }

    // ============================ FR-204 共享放行分支 ============================

    private StoredFileEntity metaOf(String fileId, Long owner) {
        StoredFileEntity e = new StoredFileEntity();
        e.setFileId(fileId);
        e.setOwnerUserId(owner);
        e.setStatus(StoredFileEntity.STATUS_ACTIVE);
        return e;
    }

    @Test
    void load_nonOwner_grantorAllows_passes() throws Exception {
        String fileId = "shared.pdf";
        Files.write(tempDir.resolve(fileId), new byte[]{1});
        FileStorageService withGrantor = new FileStorageService(tempDir.toString(), storedFileMapper,
                java.util.List.of((fid, uid) -> true));   // 放行桩
        org.mockito.Mockito.when(storedFileMapper.selectById(fileId)).thenReturn(metaOf(fileId, 7L));

        Path p = withGrantor.loadPath(fileId, 99L, false);   // 非 owner 非 admin，grantor 放行

        assertThat(p).exists();
    }

    @Test
    void load_nonOwner_grantorDeniesOrThrows_forbidden() throws Exception {
        String fileId = "deny.pdf";
        Files.write(tempDir.resolve(fileId), new byte[]{1});
        org.mockito.Mockito.when(storedFileMapper.selectById(fileId)).thenReturn(metaOf(fileId, 7L));
        FileStorageService denying = new FileStorageService(tempDir.toString(), storedFileMapper,
                java.util.List.of((fid, uid) -> false));
        FileStorageService throwing = new FileStorageService(tempDir.toString(), storedFileMapper,
                java.util.List.of((fid, uid) -> { throw new RuntimeException("acl db down"); }));

        assertThatThrownBy(() -> denying.loadPath(fileId, 99L, false))
                .hasMessageContaining("无权访问");
        assertThatThrownBy(() -> throwing.loadPath(fileId, 99L, false))
                .hasMessageContaining("无权访问");   // grantor 异常 fail-closed
    }
}
