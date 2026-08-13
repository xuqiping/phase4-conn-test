package com.superprogrammer.knowledge.service.internal;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredDocumentExtractorTest {

    private final StructuredDocumentExtractor extractor = new StructuredDocumentExtractor();

    @Test
    void pdfProducesOneLocatedSectionPerTextPage() throws Exception {
        ExtractedDocument document = extractor.extractPdf(new ByteArrayInputStream(twoPagePdf()));

        assertThat(document.getParserName()).isEqualTo("pdfbox");
        assertThat(document.getDocumentType()).isEqualTo("PDF");
        assertThat(document.getSections()).hasSize(2);
        assertThat(document.getSections()).extracting(Section::getOrdinal).containsExactly(0, 1);
        assertThat(document.getSections()).extracting(s -> s.getLocator().getPageStart())
                .containsExactly(1, 2);
        assertThat(document.getSections()).extracting(s -> s.getLocator().getPageEnd())
                .containsExactly(1, 2);
        assertThat(document.getSections()).allSatisfy(section -> {
            assertThat(section.getLocator().getBoundingBoxes()).isNullOrEmpty();
            assertThat(section.getLocator().getCrossPage()).isFalse();
        });
    }

    @Test
    void markdownPreservesHeadingHierarchyAndReadingOrder() {
        String markdown = """
                # 售后政策
                总则。

                ## 退款条件
                七天内可以退款。

                ## 换货条件
                商品损坏可以换货。
                """;

        ExtractedDocument document = extractor.extractMarkdown(markdown);

        assertThat(document.getDocumentType()).isEqualTo("MARKDOWN");
        assertThat(document.getSections()).extracting(Section::getTitle)
                .containsExactly("售后政策", "退款条件", "换货条件");
        assertThat(document.getSections().get(1).getTitlePath())
                .containsExactly("售后政策", "退款条件");
        assertThat(document.getSections()).extracting(s -> s.getLocator().getReadingOrder())
                .containsExactly(0, 1, 2);
    }

    @Test
    void docxPreservesHeadingHierarchy() throws Exception {
        byte[] docx;
        try (XWPFDocument word = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph h1 = word.createParagraph();
            h1.setStyle("Heading1");
            h1.createRun().setText("售后政策");
            word.createParagraph().createRun().setText("总则");
            XWPFParagraph h2 = word.createParagraph();
            h2.setStyle("Heading2");
            h2.createRun().setText("退款条件");
            word.createParagraph().createRun().setText("七天内可以退款");
            word.write(out);
            docx = out.toByteArray();
        }

        ExtractedDocument document = extractor.extractDocx(new ByteArrayInputStream(docx));

        assertThat(document.getDocumentType()).isEqualTo("DOCX");
        assertThat(document.getSections()).extracting(Section::getTitle)
                .containsExactly("售后政策", "退款条件");
        assertThat(document.getSections().get(1).getTitlePath())
                .containsExactly("售后政策", "退款条件");
        assertThat(document.getSections().get(1).getContent()).contains("七天内可以退款");
    }

    @Test
    void imageTextUsesSinglePageRegionWithoutInventedBoundingBox() {
        ExtractedDocument document = extractor.extractImageText("设备铭牌", "型号 A-100");

        Section section = document.getSections().get(0);
        assertThat(document.getDocumentType()).isEqualTo("IMAGE");
        assertThat(section.getLocator().getPageStart()).isEqualTo(1);
        assertThat(section.getLocator().getPageEnd()).isEqualTo(1);
        assertThat(section.getLocator().getRegionType()).isEqualTo("IMAGE");
        assertThat(section.getLocator().getBoundingBoxes()).isNullOrEmpty();
    }

    private byte[] twoPagePdf() throws Exception {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            addPage(pdf, "page one");
            addPage(pdf, "page two");
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private void addPage(PDDocument pdf, String text) throws Exception {
        PDPage page = new PDPage();
        pdf.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) {
            stream.beginText();
            stream.setFont(PDType1Font.HELVETICA, 12);
            stream.newLineAtOffset(72, 720);
            stream.showText(text);
            stream.endText();
        }
    }
}
