package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.entity.MemoryAssetMemory;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件内容分页/分段解析器（V69 二期 P3 Step 2，FR-202）。
 * <p>
 * 按 file_kind 分派：PDF→逐页文本（page_ref=「第N页」）；PPT→逐页幻灯片文本；
 * DOC→docx 段落 / txt·md 按长度切段（page_ref=「段N」）。
 * <b>分页流式</b>：逐页出 chunk 不整本读入内存（200 页 PDF 不 OOM，plan 坑表①）。
 * <p>
 * IMAGE/AUDIO/VIDEO/OTHER 及老格式（.ppt/.doc）<b>不解析</b>——多模态描述/转写 provider
 * 未接入，调用方按 FR-205 降级弱记忆（仅元数据+「读不懂内容」明示）。
 * <p>
 * 纯函数式无 Spring 依赖（构造即可测），解析异常上抛由 ingest service 归类 FAILED。
 */
@Slf4j
@Component
public class MemoryAssetExtractor {

    /** 单 chunk 文本上限（防单页文本爆炸拖垮 embedding/汇总）。 */
    static final int MAX_CHUNK_CHARS = 2000;
    /** DOC 纯文本分段目标长度。 */
    static final int DOC_SEGMENT_CHARS = 1000;

    /** 一条分块草稿（chunk_no 从 1 起）。 */
    public record ChunkDraft(int chunkNo, String text, String pageRef) {
    }

    /** 解析产物：分块列表（可空=无文字层/不支持模态）。 */
    public record ExtractResult(List<ChunkDraft> chunks, int totalUnits, boolean unsupported) {
        public boolean hasText() {
            return chunks != null && !chunks.isEmpty();
        }
    }

    /** 分派解析入口。unsupported=true 表示该模态/格式无解析器（走弱记忆降级）。 */
    public ExtractResult extract(Path path, String fileKind, String originalName) throws Exception {
        return switch (fileKind) {
            case MemoryAssetMemory.KIND_PDF -> extractPdf(path);
            case MemoryAssetMemory.KIND_PPT -> extractPpt(path, originalName);
            case MemoryAssetMemory.KIND_DOC -> extractDoc(path, originalName);
            default -> new ExtractResult(List.of(), 0, true);   // IMAGE/AUDIO/VIDEO/OTHER 无解析器
        };
    }

    /** PDF 逐页文本（PDFTextStripper 分页窗口，逐页出 chunk 释放）。无文字层页跳过。 */
    private ExtractResult extractPdf(Path path) throws Exception {
        List<ChunkDraft> chunks = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(path.toFile())) {
            int pages = doc.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            int no = 0;
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc);
                text = text == null ? "" : text.strip();
                if (text.isEmpty()) {
                    continue;   // 扫描件页/纯图页无文字层
                }
                chunks.add(new ChunkDraft(++no, cap(text), "第" + p + "页"));
            }
            return new ExtractResult(chunks, pages, false);
        }
    }

    /** PPT 逐页幻灯片文本（仅 .pptx；老 .ppt 需 poi-scratchpad 未引 → unsupported 弱记忆）。 */
    private ExtractResult extractPpt(Path path, String originalName) throws Exception {
        if (originalName != null && originalName.toLowerCase(java.util.Locale.ROOT).endsWith(".ppt")) {
            return new ExtractResult(List.of(), 0, true);
        }
        List<ChunkDraft> chunks = new ArrayList<>();
        try (InputStream in = Files.newInputStream(path);
             XMLSlideShowHolder holder = new XMLSlideShowHolder(in)) {
            List<XSLFSlide> slides = holder.ppt.getSlides();
            int no = 0;
            for (int i = 0; i < slides.size(); i++) {
                StringBuilder sb = new StringBuilder();
                for (XSLFShape shape : slides.get(i).getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String t = textShape.getText();
                        if (t != null && !t.isBlank()) {
                            sb.append(t.strip()).append('\n');
                        }
                    }
                }
                String text = sb.toString().strip();
                if (!text.isEmpty()) {
                    chunks.add(new ChunkDraft(++no, cap(text), "第" + (i + 1) + "页"));
                }
            }
            return new ExtractResult(chunks, slides.size(), false);
        }
    }

    /** DOC：docx 段落聚合切段；txt/md 按长度切段；老 .doc 需 poi-scratchpad 未引 → unsupported。 */
    private ExtractResult extractDoc(Path path, String originalName) throws Exception {
        String name = originalName == null ? "" : originalName.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".doc")) {
            return new ExtractResult(List.of(), 0, true);
        }
        List<String> paragraphs = new ArrayList<>();
        if (name.endsWith(".docx")) {
            try (InputStream in = Files.newInputStream(path);
                 XWPFDocument doc = new XWPFDocument(in)) {
                for (XWPFParagraph p : doc.getParagraphs()) {
                    String t = p.getText();
                    if (t != null && !t.isBlank()) {
                        paragraphs.add(t.strip());
                    }
                }
            }
        } else {   // txt / md
            paragraphs.addAll(Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .map(String::strip).filter(s -> !s.isEmpty()).toList());
        }
        return new ExtractResult(segment(paragraphs), paragraphs.size(), false);
    }

    /** 段落聚合成 ≤DOC_SEGMENT_CHARS 的段 chunk（page_ref=「段N」）。 */
    private List<ChunkDraft> segment(List<String> paragraphs) {
        List<ChunkDraft> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int no = 0;
        for (String p : paragraphs) {
            if (current.length() + p.length() > DOC_SEGMENT_CHARS && current.length() > 0) {
                chunks.add(new ChunkDraft(++no, cap(current.toString()), "段" + no));
                current.setLength(0);
            }
            current.append(p).append('\n');
        }
        if (current.length() > 0) {
            chunks.add(new ChunkDraft(++no, cap(current.toString()), "段" + no));
        }
        return chunks;
    }

    private String cap(String text) {
        String t = text.strip();
        return t.length() > MAX_CHUNK_CHARS ? t.substring(0, MAX_CHUNK_CHARS) : t;
    }

    /** try-with-resources 包装（XMLSlideShow 实现 Closeable，直接声明会暴露 POI 类型给签名）。 */
    private static final class XMLSlideShowHolder implements AutoCloseable {
        private final org.apache.poi.xslf.usermodel.XMLSlideShow ppt;

        XMLSlideShowHolder(InputStream in) throws java.io.IOException {
            this.ppt = new org.apache.poi.xslf.usermodel.XMLSlideShow(in);
        }

        @Override
        public void close() throws java.io.IOException {
            ppt.close();
        }
    }
}
