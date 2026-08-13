package com.superprogrammer.knowledge.chunk;

import com.superprogrammer.knowledge.service.internal.Section;
import com.superprogrammer.knowledge.service.internal.SectionLocator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkFactoryTest {

    private final ChunkFactory factory = ChunkFactory.defaults();

    @Test
    void normalDocumentBuildsBoundedC2ChunksOnParagraphBoundaries() {
        List<String> paragraphs = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> "第" + i + "段。" + "这是完整事实描述。".repeat(55))
                .toList();
        Section section = section("SECTION", String.join("\n\n", paragraphs));

        List<ChunkDraft> chunks = factory.chunk(section);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.granularity()).isEqualTo("C2");
            assertThat(chunk.tokenCount()).isLessThanOrEqualTo(600);
            assertThat(chunk.content()).doesNotEndWith("这是完整事实");
            assertThat(chunk.sourceSectionId()).isEqualTo("sec-1");
            assertThat(chunk.locator().getPageStart()).isEqualTo(2);
        });
        assertThat(chunks).extracting(ChunkDraft::ordinal)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks.get(0).previousOrdinal()).isNull();
        assertThat(chunks.get(0).nextOrdinal()).isEqualTo(1);
        assertThat(chunks.get(chunks.size() - 1).nextOrdinal()).isNull();
    }

    @Test
    void normalDocumentCarriesParagraphOverlapWithoutBreakingParagraphs() {
        List<String> paragraphs = java.util.stream.IntStream.range(0, 14)
                .mapToObj(i -> "唯一段落" + i + "：" + "内容。".repeat(70))
                .toList();

        List<ChunkDraft> chunks = factory.chunk(section("SECTION", String.join("\n\n", paragraphs)));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(paragraphs.stream().filter(paragraph ->
                chunks.get(0).content().contains(paragraph)
                        && chunks.get(1).content().contains(paragraph))).isNotEmpty();
    }

    @Test
    void clauseAndFaqRemainAtomicC2Units() {
        Section clause = section("CLAUSE", "第十条 申请人满足全部条件时可以退款，但恶意损坏除外。\n（一）提交凭证；\n（二）七日内申请。");
        Section faq = section("FAQ", "问：如何退款？\n答：在订单页面提交退款申请并上传凭证。");

        assertThat(factory.chunk(clause)).singleElement().satisfies(chunk -> {
            assertThat(chunk.granularity()).isEqualTo("C2");
            assertThat(chunk.chunkType()).isEqualTo("CLAUSE");
            assertThat(chunk.content()).contains("第十条", "（一）", "（二）");
        });
        assertThat(factory.chunk(faq)).singleElement().satisfies(chunk -> {
            assertThat(chunk.granularity()).isEqualTo("C2");
            assertThat(chunk.chunkType()).isEqualTo("FAQ");
            assertThat(chunk.content()).contains("问：", "答：");
        });
    }

    @Test
    void tableRowAndVisualRegionBecomePreciseE3Units() {
        Section table = section("TABLE_ROW", "| 品名 | 数量 |\n|---|---|\n| 苹果 | 10 |");
        table.setLocator(SectionLocator.builder().sheetName("销售").rowStart(2).rowEnd(2)
                .cellStart("A2").cellEnd("B2").readingOrder(0).build());
        Section image = section("IMAGE", "设备铭牌：型号 A-100，额定电压 220V");
        image.setLocator(SectionLocator.builder().pageStart(1).pageEnd(1)
                .readingOrder(0).regionType("IMAGE").build());

        assertThat(factory.chunk(table)).singleElement().satisfies(chunk -> {
            assertThat(chunk.granularity()).isEqualTo("E3");
            assertThat(chunk.chunkType()).isEqualTo("TABLE_ROW");
            assertThat(chunk.locator().getCellStart()).isEqualTo("A2");
        });
        assertThat(factory.chunk(image)).singleElement().satisfies(chunk -> {
            assertThat(chunk.granularity()).isEqualTo("E3");
            assertThat(chunk.chunkType()).isEqualTo("VISUAL_REGION");
            assertThat(chunk.locator().getPageStart()).isEqualTo(1);
        });
    }

    @Test
    void pdfPageUsesC2AndPreservesPageLocator() {
        Section page = section("PAGE", "第二页的完整正文。");

        assertThat(factory.chunk(page)).singleElement().satisfies(chunk -> {
            assertThat(chunk.granularity()).isEqualTo("C2");
            assertThat(chunk.chunkType()).isEqualTo("PDF_PAGE");
            assertThat(chunk.locator().getPageStart()).isEqualTo(2);
        });
    }

    @Test
    void listAndProcedureRemainAtomicC2Units() {
        Section list = section("LIST", "1. 安装依赖\n2. 配置数据库\n3. 启动服务");
        Section procedure = section("PROCEDURE", "步骤一：断电。\n步骤二：检查接线。\n步骤三：重新上电。");

        assertThat(factory.chunk(list)).singleElement().satisfies(chunk -> {
            assertThat(chunk.granularity()).isEqualTo("C2");
            assertThat(chunk.chunkType()).isEqualTo("LIST");
            assertThat(chunk.content()).contains("1.", "2.", "3.");
        });
        assertThat(factory.chunk(procedure)).singleElement().satisfies(chunk -> {
            assertThat(chunk.granularity()).isEqualTo("C2");
            assertThat(chunk.chunkType()).isEqualTo("PROCEDURE");
            assertThat(chunk.content()).contains("步骤一", "步骤二", "步骤三");
        });
    }

    private Section section(String nodeType, String content) {
        return Section.builder()
                .sectionId("sec-1")
                .nodeType(nodeType)
                .title("部署说明")
                .titlePath(List.of("运维手册", "部署说明"))
                .ordinal(1)
                .content(content)
                .locator(SectionLocator.builder().pageStart(2).pageEnd(2).readingOrder(1).build())
                .build();
    }
}
