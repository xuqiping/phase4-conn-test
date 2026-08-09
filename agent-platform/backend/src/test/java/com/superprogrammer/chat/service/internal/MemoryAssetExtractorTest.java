package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemoryAssetMemory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文件内容分页/分段解析器（V69 二期 P3 Step 2，FR-202）。
 * 真文件解析（PDFBox/POI），无 mock。
 */
class MemoryAssetExtractorTest {

    @TempDir Path tempDir;

    private final MemoryAssetExtractor extractor = new MemoryAssetExtractor();

    /** 生成带文字 PDF；pages[i]=null 表示该页无文字层（模拟扫描件页）。 */
    private Path writePdf(String fileName, String[] pageTexts) throws IOException {
        Path path = tempDir.resolve(fileName);
        try (PDDocument doc = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (text != null) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(PDType1Font.HELVETICA, 12);
                        cs.newLineAtOffset(50, 700);
                        cs.showText(text);
                        cs.endText();
                    }
                }
            }
            doc.save(path.toFile());
        }
        return path;
    }

    @Test
    @DisplayName("FR-202 PDF 逐页出 chunk，page_ref=第N页")
    void pdf_perPageChunks() throws Exception {
        Path pdf = writePdf("a.pdf", new String[]{"intro hooks", "state basics", "effect pitfalls"});
        MemoryAssetExtractor.ExtractResult r = extractor.extract(pdf, MemoryAssetMemory.KIND_PDF, "a.pdf");
        assertTrue(r.hasText());
        assertFalse(r.unsupported());
        assertEquals(3, r.totalUnits());
        assertEquals(3, r.chunks().size());
        assertEquals("第1页", r.chunks().get(0).pageRef());
        assertEquals("第3页", r.chunks().get(2).pageRef());
        assertTrue(r.chunks().get(0).text().contains("intro hooks"));
        assertEquals(List.of(1, 2, 3), r.chunks().stream().map(MemoryAssetExtractor.ChunkDraft::chunkNo).toList());
    }

    @Test
    @DisplayName("FR-202 PDF 无文字层页跳过（扫描件页不出 chunk）")
    void pdf_blankPagesSkipped() throws Exception {
        Path pdf = writePdf("b.pdf", new String[]{"has text", null, "more text"});
        MemoryAssetExtractor.ExtractResult r = extractor.extract(pdf, MemoryAssetMemory.KIND_PDF, "b.pdf");
        assertEquals(3, r.totalUnits());
        assertEquals(2, r.chunks().size());
        assertEquals("第3页", r.chunks().get(1).pageRef());
    }

    @Test
    @DisplayName("FR-202 PDF 全文无文字层 → hasText=false（调用方走弱记忆降级）")
    void pdf_allBlank_noText() throws Exception {
        Path pdf = writePdf("c.pdf", new String[]{null, null});
        MemoryAssetExtractor.ExtractResult r = extractor.extract(pdf, MemoryAssetMemory.KIND_PDF, "c.pdf");
        assertFalse(r.hasText());
        assertFalse(r.unsupported());
    }

    @Test
    @DisplayName("FR-202 txt 按长度分段，page_ref=段N")
    void txt_segments() throws Exception {
        Path txt = tempDir.resolve("notes.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("这是第").append(i).append("行笔记内容，凑够长度用来分段。\n");
        }
        Files.writeString(txt, sb.toString(), StandardCharsets.UTF_8);
        MemoryAssetExtractor.ExtractResult r = extractor.extract(txt, MemoryAssetMemory.KIND_DOC, "notes.txt");
        assertTrue(r.hasText());
        assertTrue(r.chunks().size() >= 2, "长文本应分多段");
        assertEquals("段1", r.chunks().get(0).pageRef());
    }

    @Test
    @DisplayName("FR-205 IMAGE/AUDIO/VIDEO/OTHER 无解析器 → unsupported（弱记忆降级）")
    void unsupportedKinds() throws Exception {
        Path any = Files.writeString(tempDir.resolve("x.bin"), "data");
        for (String kind : List.of(MemoryAssetMemory.KIND_IMAGE, MemoryAssetMemory.KIND_AUDIO,
                MemoryAssetMemory.KIND_VIDEO, MemoryAssetMemory.KIND_OTHER)) {
            MemoryAssetExtractor.ExtractResult r = extractor.extract(any, kind, "x.bin");
            assertTrue(r.unsupported(), kind + " 应 unsupported");
            assertFalse(r.hasText());
        }
    }

    @Test
    @DisplayName("FR-205 老格式 .ppt/.doc（需 poi-scratchpad 未引）→ unsupported 弱记忆")
    void legacyFormats_unsupported() throws Exception {
        Path any = Files.writeString(tempDir.resolve("old.ppt"), "fake");
        assertTrue(extractor.extract(any, MemoryAssetMemory.KIND_PPT, "old.ppt").unsupported());
        assertTrue(extractor.extract(any, MemoryAssetMemory.KIND_DOC, "old.doc").unsupported());
    }
}
