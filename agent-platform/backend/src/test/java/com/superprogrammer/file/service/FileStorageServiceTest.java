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

    // 14x-4 决策矫正（原断言「html 拒收」已过时）：html 上传放行（KB 解析器认此类型），
    // 危险面由下载侧强制 attachment+nosniff 承接。svg/exe 仍拒收（下方用例）。
    @Test
    void store_allowsHtmlUpload_per14x4() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "page.html", "text/html", "<html><body>doc</body></html>".getBytes());

        StoredFile result = service.store(file, 7L, StoredFileEntity.SOURCE_WORKFLOW);

        assertThat(result.fileId()).endsWith(".html");
        assertThat(result.size()).isGreaterThan(0);
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

    // ============================ 安全体系 S4 · SEC-FR-033 per-user 存储配额 ============================

    @org.mockito.Mock
    private com.superprogrammer.system.service.SystemSettingService quotaSettings;
    @org.mockito.Mock
    private com.superprogrammer.common.metrics.BizMetrics quotaMetrics;

    private void wireQuota() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "systemSettingService", quotaSettings);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "bizMetrics", quotaMetrics);
    }

    private static java.util.Map<String, Object> row(long used) {
        return java.util.Map.of("used", used);
    }

    // AC：已用+本文件超配额 → 40011 固定话术（含用量/上限）+ 拒收计数
    @Test
    void store_overQuota_rejectedWithUsage() {
        wireQuota();
        org.mockito.Mockito.when(quotaSettings.getUserStorageQuotaMb()).thenReturn(1L);   // 1MB
        org.mockito.Mockito.when(storedFileMapper.selectMaps(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(row(1024 * 1024)));   // 已用满
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", new byte[100]);

        assertThatThrownBy(() -> service.store(file, 7L, StoredFileEntity.SOURCE_KB))
                .isInstanceOf(com.superprogrammer.common.exception.BusinessException.class)
                .hasMessageContaining("存储空间已满")
                .hasMessageContaining("上限");
        org.mockito.Mockito.verify(quotaMetrics).uploadQuotaDenied();
        org.mockito.Mockito.verify(storedFileMapper, org.mockito.Mockito.never())
                .insert(org.mockito.ArgumentMatchers.any(StoredFileEntity.class));
    }

    // AC：配额 0 = 关闭（不查库零开销）
    @Test
    void store_quotaZero_skipsCheck() {
        wireQuota();
        org.mockito.Mockito.when(quotaSettings.getUserStorageQuotaMb()).thenReturn(0L);
        MockMultipartFile file = new MockMultipartFile("file", "any.png", "image/png", new byte[10]);

        StoredFile result = service.store(file, 7L, StoredFileEntity.SOURCE_KB);

        assertThat(result.fileId()).endsWith(".png");
        org.mockito.Mockito.verify(storedFileMapper, org.mockito.Mockito.never())
                .selectMaps(org.mockito.ArgumentMatchers.any());
    }

    // AC：配额查询故障放行（检测层不自残）
    @Test
    void store_quotaQueryFails_passes() {
        wireQuota();
        org.mockito.Mockito.when(quotaSettings.getUserStorageQuotaMb()).thenReturn(1L);
        org.mockito.Mockito.when(storedFileMapper.selectMaps(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("db down"));
        MockMultipartFile file = new MockMultipartFile("file", "any.png", "image/png", new byte[10]);

        assertThat(service.store(file, 7L, StoredFileEntity.SOURCE_KB).fileId()).endsWith(".png");
    }

    // AC：预算内放行
    @Test
    void store_withinQuota_passes() {
        wireQuota();
        org.mockito.Mockito.when(quotaSettings.getUserStorageQuotaMb()).thenReturn(1L);
        org.mockito.Mockito.when(storedFileMapper.selectMaps(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(row(0)));
        MockMultipartFile file = new MockMultipartFile("file", "small.png", "image/png", new byte[10]);

        assertThat(service.store(file, 7L, StoredFileEntity.SOURCE_KB).fileId()).endsWith(".png");
    }
}
