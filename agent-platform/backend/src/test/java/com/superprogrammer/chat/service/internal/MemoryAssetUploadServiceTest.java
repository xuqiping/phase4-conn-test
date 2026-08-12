package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryAssetUploadVO;
import com.superprogrammer.chat.entity.MemoryAssetMemory;
import com.superprogrammer.chat.mapper.MemoryAssetMemoryMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.file.service.StoredFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 聊天附件上传 + 文件记忆登记（V69 二期 P3 Step 1，FR-201）。
 * AC 对应 spec FR-201：类型/大小/页数/时长校验超限友好拒收；一文件一记忆 PROCESSING 行。
 */
@ExtendWith(MockitoExtension.class)
class MemoryAssetUploadServiceTest {

    @Mock private FileStorageService fileStorageService;
    @Mock private MemoryAssetMemoryMapper assetMemoryMapper;

    @TempDir Path tempDir;

    private MemoryAssetUploadService newService() {
        return new MemoryAssetUploadService(50, 200, 30, fileStorageService, assetMemoryMapper);
    }

    private MockMultipartFile pdfFile(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/pdf", content);
    }

    /** 生成 n 页空白 PDF 到临时目录。 */
    private Path writePdf(String fileName, int pages) throws IOException {
        Path path = tempDir.resolve(fileName);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(path.toFile());
        }
        return path;
    }

    private void stubStore(String fileId, String name, long size) {
        when(fileStorageService.store(any(), eq(100L), eq(StoredFileEntity.SOURCE_CHAT)))
                .thenReturn(new StoredFile(fileId, "/api/files/" + fileId, name, "application/pdf", size));
    }

    // ============================ FR-201 上传校验 ============================

    @Test
    @DisplayName("FR-201 空文件 → BAD_REQUEST 不触落盘")
    void upload_emptyFile_rejected() {
        MockMultipartFile empty = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0]);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> newService().upload(empty, 100L));
        assertTrue(ex.getMessage().contains("不能为空"));
        verifyNoInteractions(fileStorageService, assetMemoryMapper);
    }

    @Test
    @DisplayName("FR-201 超 50MB → BAD_REQUEST 友好话术，不触落盘")
    void upload_oversize_rejectedBeforeStore() {
        MemoryAssetUploadService service = new MemoryAssetUploadService(
                1, 200, 30, fileStorageService, assetMemoryMapper);   // 上限压到 1MB 便于构造
        MockMultipartFile big = new MockMultipartFile("file", "big.pdf", "application/pdf",
                new byte[2 * 1024 * 1024]);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.upload(big, 100L));
        assertTrue(ex.getMessage().contains("大小上限"));
        verifyNoInteractions(fileStorageService, assetMemoryMapper);
    }

    @Test
    @DisplayName("FR-201 不支持类型（exe）→ BAD_REQUEST，不触落盘")
    void upload_unsupportedType_rejected() {
        MockMultipartFile exe = new MockMultipartFile("file", "evil.exe", "application/x-msdownload",
                "MZ".getBytes());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> newService().upload(exe, 100L));
        assertTrue(ex.getMessage().contains("不支持的文件类型"));
        verifyNoInteractions(fileStorageService, assetMemoryMapper);
    }

    @Test
    @DisplayName("FR-201 正常 PDF → stored_files(CHAT) + 记忆行 PROCESSING + VO 回填")
    void upload_validPdf_storesAndRegisters() throws Exception {
        Path pdf = writePdf("ok.pdf", 3);
        stubStore("uuid-ok.pdf", "ok.pdf", Files.size(pdf));
        when(fileStorageService.loadPath("uuid-ok.pdf", 100L, false)).thenReturn(pdf);
        when(assetMemoryMapper.insert(any(MemoryAssetMemory.class))).thenAnswer(inv -> {
            inv.getArgument(0, MemoryAssetMemory.class).setId(42L);
            return 1;
        });

        MockMultipartFile file = pdfFile("ok.pdf", Files.readAllBytes(pdf));
        MemoryAssetUploadVO vo = newService().upload(file, 100L);

        assertEquals(42L, vo.getMemoryId());
        assertEquals("uuid-ok.pdf", vo.getFileId());
        assertEquals("PDF", vo.getFileKind());
        assertEquals(MemoryAssetMemory.STATUS_PROCESSING, vo.getIngestStatus());
        verify(assetMemoryMapper).insert(argThat(row ->
                row.getOwnerUserId().equals(100L)
                        && row.getFileId().equals("uuid-ok.pdf")
                        && row.getIngestStatus().equals(MemoryAssetMemory.STATUS_PROCESSING)
                        && Boolean.FALSE.equals(row.getWeakMemory())));
    }

    @Test
    @DisplayName("FR-201 PDF 201 页超限 → 拒收 + 删已落盘文件，记忆行不建")
    void upload_pdfOverPages_rejectedAndDeleted() throws Exception {
        Path pdf = writePdf("huge.pdf", 201);
        stubStore("uuid-huge.pdf", "huge.pdf", Files.size(pdf));
        when(fileStorageService.loadPath("uuid-huge.pdf", 100L, false)).thenReturn(pdf);

        MockMultipartFile file = pdfFile("huge.pdf", Files.readAllBytes(pdf));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> newService().upload(file, 100L));
        assertTrue(ex.getMessage().contains("页数上限"));
        verify(fileStorageService).delete("uuid-huge.pdf");
        verify(assetMemoryMapper, never()).insert(any());
    }

    @Test
    @DisplayName("FR-201 损坏文件预检读不懂 → 不拒收（留 ingestion 标 FAILED），记忆行照常建")
    void upload_corruptFile_precheckPassThrough() {
        stubStore("uuid-bad.pdf", "bad.pdf", 10L);
        when(fileStorageService.loadPath("uuid-bad.pdf", 100L, false))
                .thenReturn(tempDir.resolve("nonexistent.pdf"));   // 预检读不到 → 异常放行
        when(assetMemoryMapper.insert(any(MemoryAssetMemory.class))).thenAnswer(inv -> {
            inv.getArgument(0, MemoryAssetMemory.class).setId(7L);
            return 1;
        });

        MockMultipartFile file = pdfFile("bad.pdf", "not-a-pdf".getBytes());
        MemoryAssetUploadVO vo = newService().upload(file, 100L);
        assertEquals(7L, vo.getMemoryId());
        verify(fileStorageService, never()).delete(anyString());
    }

    // ============================ FR-201 消息附件归属校验 ============================

    @Test
    @DisplayName("FR-201 消息附件：null/空集 → 空列表不触查询")
    void resolveNames_empty_shortCircuit() {
        assertEquals(List.of(), newService().resolveOwnedAttachmentNames(null, 100L));
        assertEquals(List.of(), newService().resolveOwnedAttachmentNames(List.of(), 100L));
        verifyNoInteractions(fileStorageService);
    }

    @Test
    @DisplayName("FR-201 消息附件：超 5 个 → BAD_REQUEST")
    void resolveNames_overLimit_rejected() {
        List<String> ids = List.of("a", "b", "c", "d", "e", "f");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> newService().resolveOwnedAttachmentNames(ids, 100L));
        assertTrue(ex.getMessage().contains("最多携带"));
        verifyNoInteractions(fileStorageService);
    }

    @Test
    @DisplayName("FR-201 消息附件：fileId 不存在 → BAD_REQUEST 统一话术")
    void resolveNames_notFound_rejected() {
        when(fileStorageService.findMeta("ghost")).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> newService().resolveOwnedAttachmentNames(List.of("ghost"), 100L));
        assertTrue(ex.getMessage().contains("附件不存在或已失效"));
    }

    @Test
    @DisplayName("FR-201 消息附件：非本人 / 非 CHAT 源 / 非 ACTIVE → 全拒（IDOR 咽喉）")
    void resolveNames_wrongOwnerOrSourceOrStatus_rejected() {
        MemoryAssetUploadService service = newService();
        // 非本人
        when(fileStorageService.findMeta("f1")).thenReturn(meta(200L, StoredFileEntity.SOURCE_CHAT,
                StoredFileEntity.STATUS_ACTIVE));
        assertThrows(BusinessException.class, () -> service.resolveOwnedAttachmentNames(List.of("f1"), 100L));
        // 非 CHAT 源（KB 文件不可当聊天附件引用）
        when(fileStorageService.findMeta("f2")).thenReturn(meta(100L, StoredFileEntity.SOURCE_KB,
                StoredFileEntity.STATUS_ACTIVE));
        assertThrows(BusinessException.class, () -> service.resolveOwnedAttachmentNames(List.of("f2"), 100L));
        // 非 ACTIVE（CLEANED 不可引用）
        when(fileStorageService.findMeta("f3")).thenReturn(meta(100L, StoredFileEntity.SOURCE_CHAT,
                StoredFileEntity.STATUS_CLEANED));
        assertThrows(BusinessException.class, () -> service.resolveOwnedAttachmentNames(List.of("f3"), 100L));
    }

    @Test
    @DisplayName("FR-201 消息附件：全合法 → 返原始名同序")
    void resolveNames_valid_returnsNamesInOrder() {
        when(fileStorageService.findMeta("f1")).thenReturn(meta(100L, StoredFileEntity.SOURCE_CHAT,
                StoredFileEntity.STATUS_ACTIVE, "课件.pptx"));
        when(fileStorageService.findMeta("f2")).thenReturn(meta(100L, StoredFileEntity.SOURCE_CHAT,
                StoredFileEntity.STATUS_ACTIVE, "笔记.pdf"));
        List<String> names = newService().resolveOwnedAttachmentNames(List.of("f1", "f2"), 100L);
        assertEquals(List.of("课件.pptx", "笔记.pdf"), names);
    }

    private StoredFileEntity meta(Long owner, String source, String status) {
        return meta(owner, source, status, "file.bin");
    }

    private StoredFileEntity meta(Long owner, String source, String status, String name) {
        StoredFileEntity e = new StoredFileEntity();
        e.setFileId("x");
        e.setOwnerUserId(owner);
        e.setSource(source);
        e.setStatus(status);
        e.setOriginalName(name);
        return e;
    }
}
