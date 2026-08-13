package com.superprogrammer.knowledge.service.internal;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExcelSheetExtractor 单元测 —— 内存建 xlsx（POI XSSF），验「每行一个 section」（FAQ/Q&A 召回友好）、
 * sheet 选择/宽表兜底/空隐藏跳过/行上限截断告警。
 *
 * <p>粒度约定（V41）：每个数据行 → 1 个 section（标题 Sheet:{name}:行N，内容=表头+该行，自洽且列名可见）。
 * 取代早期「整 sheet/整行块 → 1 section」——后者整张 FAQ 表共用一句笼统摘要向量，提问细粒度命中不到。
 * 纯解析层（Section 产出），不触 DB/H2/halfvec，故单元测即可（设计 §7）。
 */
class ExcelSheetExtractorTest {

    private final ExcelSheetExtractor extractor = new ExcelSheetExtractor();

    /** 默认配置：col 阈 10 / 行块 200（保留参数，每行模式不再按行块合并）/ cell 截 200 / 行上限 5000。 */
    private static final int COL = 10, CHUNK = 200, CELL = 200, MAX_ROWS = 5000;

    @Test
    void eachDataRowProducesItsOwnSection() {
        Workbook wb = new XSSFWorkbook();
        sheet(wb, "销售", new String[]{"品名", "销量"},
                new String[]{"苹果", "10"}, new String[]{"梨", "20"});
        sheet(wb, "库存", new String[]{"品名", "库存"}, new String[]{"苹果", "100"});

        List<Section> sections = extractor.extract(asStream(wb), Set.of(), COL, CHUNK, CELL, MAX_ROWS)
                .document().getSections();

        // 3 数据行 = 3 section，每行一个；标题带行号
        assertThat(sections).extracting(Section::getTitle).containsExactly(
                "Sheet:销售:行2", "Sheet:销售:行3", "Sheet:库存:行2");
        // 单行 markdown 表：含表头分隔行 + 该行值
        assertThat(sections.get(0).getContent()).contains("|", "品名", "苹果", "10");
        assertThat(sections.get(1).getContent()).contains("梨", "20").doesNotContain("苹果");
    }

    @Test
    void eachDataRowCarriesStableSheetAndCellLocator() {
        Workbook wb = new XSSFWorkbook();
        sheet(wb, "销售", new String[]{"品名", "销量"},
                new String[]{"苹果", "10"}, new String[]{"梨", "20"});

        List<Section> sections = extractor.extract(asStream(wb), Set.of(), COL, CHUNK, CELL, MAX_ROWS)
                .document().getSections();

        assertThat(sections).extracting(Section::getOrdinal).containsExactly(0, 1);
        assertThat(sections).extracting(s -> s.getLocator().getReadingOrder()).containsExactly(0, 1);
        assertThat(sections.get(0).getLocator()).satisfies(locator -> {
            assertThat(locator.getSheetName()).isEqualTo("销售");
            assertThat(locator.getRowStart()).isEqualTo(2);
            assertThat(locator.getRowEnd()).isEqualTo(2);
            assertThat(locator.getCellStart()).isEqualTo("A2");
            assertThat(locator.getCellEnd()).isEqualTo("B2");
        });
    }

    @Test
    void selectedSheetsFiltersToChosenOnly() {
        Workbook wb = new XSSFWorkbook();
        sheet(wb, "销售", new String[]{"a"}, new String[]{"1"});
        sheet(wb, "库存", new String[]{"b"}, new String[]{"2"});
        sheet(wb, "退货", new String[]{"c"}, new String[]{"3"});

        List<Section> sections = extractor.extract(asStream(wb),
                Set.of("库存"), COL, CHUNK, CELL, MAX_ROWS).document().getSections();

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getTitle()).isEqualTo("Sheet:库存:行2");
    }

    @Test
    void wideTableFallsBackToKeyValueStream() {
        Workbook wb = new XSSFWorkbook();
        String[] headers = new String[15];   // 15 列 > 阈 10 → 键值行流（非 markdown 表）
        for (int i = 0; i < headers.length; i++) headers[i] = "c" + i;
        sheet(wb, "宽表", headers, headers);  // 1 数据行（值=列名）

        String content = extractor.extract(asStream(wb), Set.of(), COL, CHUNK, CELL, MAX_ROWS)
                .document().getSections().get(0).getContent();

        assertThat(content).doesNotContain("---|");        // 非 markdown 表
        assertThat(content).contains("c0=", "c0");         // 键值行流：列名=值
    }

    @Test
    void emptyAndHiddenSheetsSkipped() {
        Workbook wb = new XSSFWorkbook();
        sheet(wb, "有数据", new String[]{"x"}, new String[]{"1"});
        wb.createSheet("空表");                               // 0 行
        Sheet hidden = wb.createSheet("隐藏表");
        hidden.createRow(0).createCell(0).setCellValue("h");
        wb.setSheetHidden(wb.getSheetIndex(hidden), true);

        List<Section> sections = extractor.extract(asStream(wb), Set.of(), COL, CHUNK, CELL, MAX_ROWS)
                .document().getSections();

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).getTitle()).isEqualTo("Sheet:有数据:行2");
    }

    @Test
    void headerOnlySheetProducesNoSection() {
        Workbook wb = new XSSFWorkbook();
        sheet(wb, "仅表头", new String[]{"k", "v"});   // 仅表头行，0 数据行

        List<Section> sections = extractor.extract(asStream(wb), Set.of(), COL, CHUNK, CELL, MAX_ROWS)
                .document().getSections();

        assertThat(sections).isEmpty();
    }

    @Test
    void blankDataRowSkipped() {
        Workbook wb = new XSSFWorkbook();
        sheet(wb, "带空行", new String[]{"k"},
                new String[]{"有值"}, new String[]{""}, new String[]{"也有值"});

        List<Section> sections = extractor.extract(asStream(wb), Set.of(), COL, CHUNK, CELL, MAX_ROWS)
                .document().getSections();

        // 中间空行跳过 → 2 section
        assertThat(sections).extracting(Section::getTitle)
                .containsExactly("Sheet:带空行:行2", "Sheet:带空行:行4");
    }

    @Test
    void rowCapProducesTruncationWarning() {
        Workbook wb = new XSSFWorkbook();
        Sheet sh = wb.createSheet("大表");
        Row hr = sh.createRow(0);
        hr.createCell(0).setCellValue("k");
        for (int i = 1; i <= 12; i++) {                       // 12 行 > 上限 5 → 截断
            Row r = sh.createRow(i);
            r.createCell(0).setCellValue("v" + i);
        }

        List<String> warnings = extractor.extract(asStream(wb), Set.of(), COL, CHUNK, CELL, 5).warnings();

        assertThat(warnings).anyMatch(w -> w.contains("大表") && w.contains("截断"));
    }

    // -------------------- helpers：内存建 xlsx --------------------

    private void sheet(Workbook wb, String name, String[] headers, String[]... rows) {
        Sheet sh = wb.createSheet(name);
        Row hr = sh.createRow(0);
        for (int c = 0; c < headers.length; c++) hr.createCell(c).setCellValue(headers[c]);
        for (int r = 0; r < rows.length; r++) {
            Row row = sh.createRow(r + 1);
            for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
        }
    }

    private ByteArrayInputStream asStream(Workbook wb) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
