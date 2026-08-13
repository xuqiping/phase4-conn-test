package com.superprogrammer.knowledge.service.internal;

import com.superprogrammer.knowledge.util.TokenEstimator;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PDF/Markdown 的结构化抽取器；只输出能够从源文件可靠恢复的定位信息。 */
@Component
public class StructuredDocumentExtractor {

    static final int MAX_PDF_PAGES = 2000;
    static final int MAX_DOCUMENT_CHARS = 20_000_000;
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

    public ExtractedDocument extractPdf(InputStream input) {
        List<Section> sections = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        try (PDDocument pdf = PDDocument.load(input)) {
            int pageCount = pdf.getNumberOfPages();
            if (pageCount > MAX_PDF_PAGES) {
                throw new IllegalArgumentException("PDF page count exceeds limit: " + pageCount);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(pdf);
                text = text == null ? "" : text.strip();
                if (text.isEmpty()) {
                    continue;
                }
                ensureCharacterLimit(plain.length() + text.length());
                int ordinal = sections.size();
                String title = "第" + page + "页";
                sections.add(Section.builder()
                        .sectionId("pdf-page-" + page)
                        .nodeType("PAGE")
                        .title(title)
                        .titlePath(List.of(title))
                        .ordinal(ordinal)
                        .content(text)
                        .tokenCount(TokenEstimator.estimate(text))
                        .locator(SectionLocator.builder()
                                .pageStart(page)
                                .pageEnd(page)
                                .readingOrder(ordinal)
                                .regionType("PAGE_TEXT")
                                .crossPage(false)
                                .build())
                        .build());
                if (plain.length() > 0) {
                    plain.append("\n\n");
                }
                plain.append(text);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("PDF structured extraction failed: " + e.getMessage(), e);
        }
        return ExtractedDocument.builder()
                .schemaVersion("1.0")
                .parserName("pdfbox")
                .parserVersion("2")
                .documentType("PDF")
                .plainText(plain.toString())
                .sections(sections)
                .build();
    }

    public ExtractedDocument extractMarkdown(String markdown) {
        String source = markdown == null ? "" : markdown;
        ensureCharacterLimit(source.length());
        List<Section> sections = new ArrayList<>();
        List<String> hierarchy = new ArrayList<>();
        String currentTitle = null;
        List<String> currentPath = List.of();
        StringBuilder body = new StringBuilder();
        for (String line : source.split("\\R", -1)) {
            Matcher heading = MARKDOWN_HEADING.matcher(line.strip());
            if (heading.matches()) {
                appendMarkdownSection(sections, currentTitle, currentPath, body);
                int level = heading.group(1).length();
                String title = heading.group(2).strip();
                while (hierarchy.size() >= level) {
                    hierarchy.remove(hierarchy.size() - 1);
                }
                while (hierarchy.size() < level - 1) {
                    hierarchy.add("");
                }
                hierarchy.add(title);
                currentTitle = title;
                currentPath = hierarchy.stream().filter(s -> !s.isBlank()).toList();
                body.setLength(0);
            } else if (currentTitle != null) {
                body.append(line).append('\n');
            }
        }
        appendMarkdownSection(sections, currentTitle, currentPath, body);
        return ExtractedDocument.builder()
                .schemaVersion("1.0")
                .parserName("markdown-heading")
                .parserVersion("1")
                .documentType("MARKDOWN")
                .plainText(source)
                .sections(sections)
                .build();
    }

    public ExtractedDocument extractDocx(InputStream input) {
        List<Section> sections = new ArrayList<>();
        List<String> hierarchy = new ArrayList<>();
        String currentTitle = null;
        List<String> currentPath = List.of();
        StringBuilder body = new StringBuilder();
        int characters = 0;
        try (XWPFDocument word = new XWPFDocument(input)) {
            for (XWPFParagraph paragraph : word.getParagraphs()) {
                String text = paragraph.getText() == null ? "" : paragraph.getText().strip();
                if (text.isEmpty()) {
                    continue;
                }
                characters += text.length();
                ensureCharacterLimit(characters);
                int headingLevel = headingLevel(paragraph.getStyle());
                if (headingLevel > 0) {
                    appendDocxSection(sections, currentTitle, currentPath, body);
                    while (hierarchy.size() >= headingLevel) {
                        hierarchy.remove(hierarchy.size() - 1);
                    }
                    while (hierarchy.size() < headingLevel - 1) {
                        hierarchy.add("");
                    }
                    hierarchy.add(text);
                    currentTitle = text;
                    currentPath = hierarchy.stream().filter(s -> !s.isBlank()).toList();
                    body.setLength(0);
                } else if (currentTitle != null) {
                    body.append(text).append('\n');
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("DOCX structured extraction failed: " + e.getMessage(), e);
        }
        appendDocxSection(sections, currentTitle, currentPath, body);
        String plain = sections.stream().map(Section::getContent)
                .filter(s -> s != null && !s.isBlank()).reduce((a, b) -> a + "\n\n" + b).orElse("");
        return ExtractedDocument.builder()
                .schemaVersion("1.0")
                .parserName("apache-poi-xwpf")
                .parserVersion("5.2")
                .documentType("DOCX")
                .plainText(plain)
                .sections(sections)
                .build();
    }

    public ExtractedDocument extractImageText(String title, String text) {
        String safeTitle = title == null || title.isBlank() ? "图片" : title;
        String content = text == null ? "" : text;
        Section section = Section.builder()
                .sectionId("image-region-0")
                .nodeType("IMAGE")
                .title(safeTitle)
                .titlePath(List.of(safeTitle))
                .ordinal(0)
                .content(content)
                .tokenCount(TokenEstimator.estimate(content))
                .locator(SectionLocator.builder()
                        .pageStart(1)
                        .pageEnd(1)
                        .readingOrder(0)
                        .regionType("IMAGE")
                        .crossPage(false)
                        .build())
                .build();
        return ExtractedDocument.builder()
                .schemaVersion("1.0")
                .parserName("image-description")
                .parserVersion("1")
                .documentType("IMAGE")
                .plainText(content)
                .sections(List.of(section))
                .build();
    }

    private void appendMarkdownSection(List<Section> sections, String title,
                                       List<String> titlePath, StringBuilder body) {
        if (title == null) {
            return;
        }
        String content = body.toString().strip();
        int ordinal = sections.size();
        sections.add(Section.builder()
                .sectionId("markdown-section-" + ordinal)
                .nodeType("SECTION")
                .title(title)
                .titlePath(new ArrayList<>(titlePath))
                .ordinal(ordinal)
                .content(content)
                .tokenCount(TokenEstimator.estimate(content))
                .locator(SectionLocator.builder()
                        .readingOrder(ordinal)
                        .regionType("SECTION")
                        .crossPage(false)
                        .build())
                .build());
    }

    private void appendDocxSection(List<Section> sections, String title,
                                   List<String> titlePath, StringBuilder body) {
        if (title == null) {
            return;
        }
        String content = body.toString().strip();
        int ordinal = sections.size();
        sections.add(Section.builder()
                .sectionId("docx-section-" + ordinal)
                .nodeType("SECTION")
                .title(title)
                .titlePath(new ArrayList<>(titlePath))
                .ordinal(ordinal)
                .content(content)
                .tokenCount(TokenEstimator.estimate(content))
                .locator(SectionLocator.builder()
                        .readingOrder(ordinal)
                        .regionType("SECTION")
                        .crossPage(false)
                        .build())
                .build());
    }

    private int headingLevel(String style) {
        if (style == null) {
            return 0;
        }
        Matcher matcher = Pattern.compile("(?i)heading\\s*([1-6])").matcher(style);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private void ensureCharacterLimit(int characters) {
        if (characters > MAX_DOCUMENT_CHARS) {
            throw new IllegalArgumentException("document character count exceeds limit: " + characters);
        }
    }
}
