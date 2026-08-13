package com.superprogrammer.knowledge.service.internal;

import com.superprogrammer.knowledge.util.TokenEstimator;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Excel sheet 级抽取（POI）—— 保列结构（markdown 表），宽表/超大单元格自动降级。
 *
 * <p>核心方法 {@link #extract(InputStream, Set, int, int, int, int)} 纯 POI 逻辑，喂 InputStream 即可单测。
 * 取代 Tika 对 Excel 的拍扁（sheet 名边界丢失 / 表格列结构散架），见 Excel多Sheet导入设计 §4.5。
 *
 * <p>约定：首行=表头；隐藏 sheet 跳过；selectedSheets 非空则只导选中；空 sheet（0 行）0 section。
 * 非致命降级（行截断/宽表行流/cell 截断）累积进 warnings，由调用方写 parse_warning。
 */
@Component
public class ExcelSheetExtractor {

    /**
     * 抽取 Excel。
     *
     * @param in              工作簿输入流（调用方负责关闭外层；本方法内部 close workbook）
     * @param selectedSheets  选中的 sheet 名集合；空 = 导入全部 sheet
     * @param colThreshold    列数 > 此值 → 宽表，转行流兜底
     * @param rowChunkSize    每 Section 最大行数（防超大 section）
     * @param cellMaxChars    单 cell 文本截断长度
     * @param maxRowsPerSheet 单 sheet 行数硬上限（截断防 OOM）
     */
    public ExcelExtractResult extract(InputStream in, Set<String> selectedSheets,
                                      int colThreshold, int rowChunkSize, int cellMaxChars,
                                      int maxRowsPerSheet) {
        List<Section> sections = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        try (Workbook wb = WorkbookFactory.create(in)) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sh = wb.getSheetAt(i);
                if (wb.isSheetHidden(i)) {
                    continue;
                }
                if (!selectedSheets.isEmpty() && !selectedSheets.contains(sh.getSheetName())) {
                    continue;
                }
                sheetToSections(sh, colThreshold, rowChunkSize, cellMaxChars, maxRowsPerSheet,
                        sections, warnings, plain);
            }
        } catch (Exception e) {
            throw new RuntimeException("Excel 抽取失败: " + e.getMessage(), e);
        }
        ExtractedDocument doc = ExtractedDocument.builder()
                .plainText(plain.toString())
                .sections(sections)
                .build();
        return new ExcelExtractResult(doc, warnings);
    }

    /** 预读 sheet 名（不读行）。跳过隐藏 sheet，上限 maxSheets 防恶意巨多 sheet 文件。 */
    public List<String> sheetNames(InputStream in, int maxSheets) {
        List<String> names = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            for (int i = 0; i < wb.getNumberOfSheets() && names.size() < maxSheets; i++) {
                if (wb.isSheetHidden(i)) {
                    continue;
                }
                names.add(wb.getSheetName(i));
            }
        } catch (Exception e) {
            throw new RuntimeException("Excel 预读 sheet 名失败: " + e.getMessage(), e);
        }
        return names;
    }

    private void sheetToSections(Sheet sh, int colThreshold, int rowChunkSize, int cellMaxChars,
                                 int maxRowsPerSheet, List<Section> sections, List<String> warnings,
                                 StringBuilder plain) {
        String sheetTitle = "Sheet:" + sh.getSheetName();
        int total = sh.getLastRowNum() + 1;
        if (total <= 0 || (sh.getRow(0) == null)) {
            return;   // 空 sheet → 0 section
        }
        int cols = firstRowCols(sh);
        if (total > maxRowsPerSheet) {
            warnings.add("sheet " + sh.getSheetName() + " 行数 " + total
                    + " > 上限 " + maxRowsPerSheet + "，已截断");
            total = maxRowsPerSheet;
        }
        boolean wide = cols > colThreshold;
        List<String> header = rowCells(sh.getRow(0), cols, cellMaxChars);   // 表头列名，每行 section 复用保自洽
        // 每个数据行 → 1 个 section（V41）：FAQ/Q&A 召回友好。早期「整 sheet/整行块 → 1 section」会让整张表
        // 共用一句笼统 L0 摘要向量，细粒度提问（如「漏水找谁」）命中不到、过不了软拒答阈。
        // rowChunkSize 在「每行模式」下不再按行块合并，保留参数与系统设置一致，供未来分组策略复用。
        for (int r = 1; r < total; r++) {
            Row row = sh.getRow(r);
            if (row == null) {
                continue;
            }
            List<String> cells = rowCells(row, cols, cellMaxChars);
            if (cells.stream().allMatch(String::isBlank)) {
                continue;   // 空行跳过
            }
            StringBuilder body = wide
                    ? renderKeyValueRow(header, cells)
                    : renderMarkdownRow(header, cells);
            if (body.length() == 0) {
                continue;
            }
            String title = sheetTitle + ":行" + (r + 1);   // 行号 1-based（表头=行1）
            String content = body.toString();
            int ordinal = sections.size();
            int rowNumber = r + 1;
            Section s = Section.builder()
                    .sectionId("excel-row-" + ordinal)
                    .nodeType("TABLE_ROW")
                    .title(title)
                    .titlePath(List.of(sheetTitle, title))
                    .ordinal(ordinal)
                    .content(content)
                    .tokenCount(TokenEstimator.estimate(content))
                    .locator(SectionLocator.builder()
                            .sheetName(sh.getSheetName())
                            .rowStart(rowNumber)
                            .rowEnd(rowNumber)
                            .cellStart("A" + rowNumber)
                            .cellEnd(colLetter(Math.max(0, cols - 1)) + rowNumber)
                            .readingOrder(ordinal)
                            .regionType("TABLE_ROW")
                            .crossPage(false)
                            .build())
                    .build();
            sections.add(s);
            plain.append(title).append('\n').append(content).append("\n\n");
        }
    }

    /** 单行 markdown 表：表头 + 分隔 + 该行（自洽、列名可见，便于 LLM 摘要与向量命中）。 */
    private StringBuilder renderMarkdownRow(List<String> header, List<String> cells) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", header)).append(" |\n");
        sb.append("|").append("---|".repeat(Math.max(1, header.size()))).append("\n");
        sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
        return sb;
    }

    /** 宽表键值行流：每非空列「列名=值」（列名空回退列字母），保留全部列值不丢。 */
    private StringBuilder renderKeyValueRow(List<String> header, List<String> cells) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(header.size(), cells.size());
        for (int c = 0; c < n; c++) {
            String v = cells.get(c);
            if (v == null || v.isEmpty()) {
                continue;
            }
            String key = (c < header.size() && !header.get(c).isEmpty()) ? header.get(c) : colLetter(c);
            sb.append(key).append('=').append(v).append('\n');
        }
        return sb;
    }

    private List<String> rowCells(Row row, int cols, int cellMaxChars) {
        List<String> cells = new ArrayList<>(cols);
        for (int c = 0; c < cols; c++) {
            String v = row == null ? "" : cellValue(row.getCell(c), cellMaxChars);
            cells.add(v == null ? "" : v);
        }
        return cells;
    }

    private int firstRowCols(Sheet sh) {
        Row r = sh.getRow(0);
        return r == null ? 0 : r.getLastCellNum();
    }

    private String cellValue(Cell cell, int cellMaxChars) {
        if (cell == null) {
            return "";
        }
        String raw;
        try {
            raw = switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                        ? cell.getLocalDateTimeCellValue().toString()
                        : trimNumber(cell.getNumericCellValue());
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case FORMULA -> cachedFormulaValue(cell);
                default -> "";
            };
        } catch (Exception e) {
            return "";
        }
        if (raw == null) {
            return "";
        }
        raw = raw.replace("|", "\\|").replace("\n", " ").replace("\r", " ").trim();
        if (raw.length() > cellMaxChars) {
            raw = raw.substring(0, cellMaxChars) + "…";
        }
        return raw;
    }

    /** 公式取缓存值（无缓存取空）—— 不重算（设计非目标）。 */
    private String cachedFormulaValue(Cell cell) {
        try {
            CellType ct = cell.getCachedFormulaResultType();
            return switch (ct) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> trimNumber(cell.getNumericCellValue());
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                default -> "";
            };
        } catch (Exception e) {
            return "";
        }
    }

    private String trimNumber(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    /** 列号→字母（A, B, ..., Z, AA, ...）。 */
    private String colLetter(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        do {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }
}
