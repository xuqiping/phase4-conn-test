package com.superprogrammer.knowledge.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredParseProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void protocolRoundTripPreservesParserIdentityHierarchyAndLocator() throws Exception {
        SectionLocator locator = SectionLocator.builder()
                .pageStart(2)
                .pageEnd(3)
                .readingOrder(4)
                .regionType("PARAGRAPH")
                .crossPage(true)
                .boundingBoxes(List.of(BoundingBox.builder()
                        .page(2)
                        .x(10.5)
                        .y(20.5)
                        .width(100.0)
                        .height(40.0)
                        .coordinateSystem("PDF_POINTS")
                        .build()))
                .build();
        Section section = Section.builder()
                .sectionId("sec-1")
                .parentSectionId("root")
                .nodeType("CLAUSE")
                .title("退款条件")
                .titlePath(List.of("售后政策", "退款条件"))
                .ordinal(4)
                .content("满足条件可退款")
                .tokenCount(8)
                .locator(locator)
                .build();
        ExtractedDocument document = ExtractedDocument.builder()
                .schemaVersion("1.0")
                .parserName("pdfbox")
                .parserVersion("3.0")
                .sourceHash("sha256-source")
                .documentType("PDF")
                .plainText(section.getContent())
                .sections(List.of(section))
                .build();

        ExtractedDocument restored = objectMapper.readValue(
                objectMapper.writeValueAsBytes(document), ExtractedDocument.class);

        assertThat(restored.getSchemaVersion()).isEqualTo("1.0");
        assertThat(restored.getParserName()).isEqualTo("pdfbox");
        assertThat(restored.getSourceHash()).isEqualTo("sha256-source");
        assertThat(restored.getSections().get(0).getTitlePath())
                .containsExactly("售后政策", "退款条件");
        assertThat(restored.getSections().get(0).getLocator().getBoundingBoxes().get(0).getPage())
                .isEqualTo(2);
    }

    @Test
    void locatorRejectsInvalidPageRange() {
        assertThatThrownBy(() -> SectionLocator.builder().pageStart(3).pageEnd(2).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page");
    }

    @Test
    void boundingBoxRejectsNegativeGeometry() {
        assertThatThrownBy(() -> BoundingBox.builder()
                .page(1).x(0.0).y(0.0).width(-1.0).height(10.0)
                .coordinateSystem("PDF_POINTS").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("width");
    }
}
