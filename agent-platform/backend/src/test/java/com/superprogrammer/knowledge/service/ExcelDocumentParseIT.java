package com.superprogrammer.knowledge.service;

import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.file.service.StoredFile;
import com.superprogrammer.knowledge.AbstractIntegrationTest;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentVO;
import com.superprogrammer.knowledge.dto.SheetPreviewVO;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Excel 多 Sheet 导入集成测（设计 §7）—— 真 PG16 + 真磁盘文件 + @MockBean LLM 引擎降确定。
 *
 * <p>覆盖端到端集成边界（纯解析层内部 mechanics 已由 {@code ExcelSheetExtractorTest} 单测覆盖）：
 * <ul>
 *   <li>阶段1 preview：返回非隐藏 sheet 名，且<strong>不</strong>建 knowledge_document 行；stored_files 记 PREVIEW owner；</li>
 *   <li>阶段2 upload（picker）：tempFileRef 复用 + selectedSheets 落 parse_options JSON；</li>
 *   <li>parse 全链路：每数据行一 L0 节点（title=Sheet:X:行N，每 sheet 1 数据行即每 sheet 1 节点）；selectedSheets 过滤；</li>
 *   <li>.xls 老格式走 HSSF 同样产 Sheet 节点；非 Excel（.txt）走 Tika 回归，正文 round-trip；</li>
 *   <li>密码保护 xlsx：POI 抛 → markFailed(status=FAILED + parse_error)。</li>
 * </ul>
 * H2 跑不了 halfvec/tsvector —— @Tag("integration") 走 it profile；本特性纯解析层，不依赖 HNSW 命中。
 *
 * <p>LLM 摘要 mock：parse() 的 summarize 阶段调 {@link LlmGateway#chat}；mock 返回合法 L1 JSON，
 * section 摘要取 mock 返回值（断言只看节点 title/L2 原文，不依赖摘要内容）。
 */
class ExcelDocumentParseIT extends AbstractIntegrationTest {

    @Autowired private KnowledgeDocumentService documentService;
    @Autowired private DocumentParserService parserService;
    @Autowired private FileStorageService fileStorageService;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private LlmGateway llmGateway;
    @MockBean private com.anji.captcha.service.CaptchaService captchaService;

    private static final long KB = 9101L;
    private static final long U1 = 9101L;

    @BeforeEach
    void seed() {
        clean();
        jdbc.update("INSERT INTO users (id, username, password) OVERRIDING SYSTEM VALUE VALUES (?,?,?)", U1, "u9101", "x");
        jdbc.update("INSERT INTO knowledge_bases (id, tenant_id, name, embedding_model) VALUES (?,?,?,?)",
                KB, 1L, "excel-it-kb", "doubao");
        // LLM 摘要降确定：所有 chat() 返回合法 L1 JSON（section 摘要也用此返回，不影响 title/L2 断言）
        when(llmGateway.chat(any())).thenReturn(
                LlmResponse.builder().content("{\"summary\":\"s\",\"outline\":[],\"importantRules\":[]}").build());
    }

    @AfterEach
    void clean() {
        jdbc.update("TRUNCATE knowledge_index_jobs, knowledge_embeddings_doubao, knowledge_doc_embeddings_doubao, "
                + "knowledge_nodes, knowledge_documents, stored_files, knowledge_bases RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM users WHERE id=?", U1);
    }

    // ============================ 阶段1 / 阶段2（service 层） ============================

    @Test
    void previewSheets_returnsNames_andDoesNotCreateDocRow() {
        SheetPreviewVO vo = documentService.previewSheets(KB, multipart("multi.xlsx", xlsxBytes(
                "销售", "库存", "退货")), U1, true);

        assertThat(vo.getSheetNames()).containsExactly("销售", "库存", "退货");
        assertThat(vo.getTempFileRef()).isNotBlank();

        // preview 不建文档行
        Long docCount = jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_documents WHERE kb_id=?", Long.class, KB);
        assertThat(docCount).isZero();

        // 存储层记 PREVIEW owner（tempFileRef 归属咽喉点依据）
        String fileId = vo.getTempFileRef().substring(vo.getTempFileRef().lastIndexOf('/') + 1);
        String source = jdbc.queryForObject(
                "SELECT source FROM stored_files WHERE file_id=?", String.class, fileId);
        Long owner = jdbc.queryForObject(
                "SELECT owner_user_id FROM stored_files WHERE file_id=?", Long.class, fileId);
        assertThat(source).isEqualTo(StoredFileEntity.SOURCE_PREVIEW);
        assertThat(owner).isEqualTo(U1);
    }

    @Test
    void upload_withTempFileRef_persistsSelectedSheetsInParseOptions() {
        // 阶段1
        SheetPreviewVO preview = documentService.previewSheets(KB,
                multipart("multi.xlsx", xlsxBytes("销售", "库存", "退货")), U1, true);

        // 阶段2：复用 tempFileRef + 勾选 2 个 sheet
        KnowledgeDocumentVO vo = documentService.upload(KB, null, preview.getTempFileRef(),
                List.of("销售", "库存"), null, null, null, null, U1, true);

        assertThat(vo.getParseOptions()).contains("销售", "库存", "selectedSheets");
        // 文件复用（零重传）：fileRef 即阶段1 的 tempFileRef
        assertThat(vo.getFileRef()).isEqualTo(preview.getTempFileRef());

        String dbOptions = jdbc.queryForObject(
                "SELECT parse_options FROM knowledge_documents WHERE id=?", String.class, vo.getId());
        assertThat(dbOptions).contains("销售", "库存");
    }

    // ============================ parse() 全链路（路由 + 落库） ============================

    @Test
    void parse_multiSheetXlsx_producesL0NodePerRow() {
        StoredFile f = storeXlsx("multi.xlsx", "销售", "库存", "退货");
        long docId = insertDoc(f.url(), null);

        parserService.parse(docId, U1);

        assertThat(status(docId)).isEqualTo("EMBEDDING");
        // 每数据行一 L0 节点（title=Sheet:X:行N）；每 sheet 1 数据行 → 每 sheet 1 节点
        assertThat(l0Titles(docId)).containsExactlyInAnyOrder(
                "Sheet:销售:行2", "Sheet:库存:行2", "Sheet:退货:行2");
        List<String> e3Metadata = jdbc.queryForList(
                "SELECT metadata FROM knowledge_nodes WHERE document_id=? AND level='L2' ORDER BY id",
                String.class, docId);
        assertThat(e3Metadata).hasSize(3).allSatisfy(metadata ->
                assertThat(metadata).contains("\"granularity\":\"E3\"", "\"chunkType\":\"TABLE_ROW\"",
                        "\"sheetName\""));
    }

    @Test
    void parse_selectedSheets_filtersToChosenOnly() {
        StoredFile f = storeXlsx("multi.xlsx", "销售", "库存", "退货");
        long docId = insertDoc(f.url(), "{\"selectedSheets\":[\"库存\"]}");

        parserService.parse(docId, U1);

        assertThat(status(docId)).isEqualTo("EMBEDDING");
        assertThat(l0Titles(docId)).containsExactly("Sheet:库存:行2");
    }

    @Test
    void parse_xlsOldFormat_routesThroughHssfAndProducesSheetNode() {
        StoredFile f = fileStorageService.store(multipart("old.xls", xlsBytes("库存表")),
                U1, StoredFileEntity.SOURCE_KB, KB, null);
        long docId = insertDoc(f.url(), null);

        parserService.parse(docId, U1);

        assertThat(status(docId)).isEqualTo("EMBEDDING");
        assertThat(l0Titles(docId)).containsExactly("Sheet:库存表:行2");
    }

    @Test
    void parse_nonExcelText_routesThroughTikaAndRoundTripsContent() {
        // 非 Excel（.txt）→ isExcel=false → Tika 原路径（pdf/docx/html 同此分支，回归保护）
        StoredFile f = fileStorageService.store(
                new MockMultipartFile("file", "manual.txt", "text/plain",
                        "部署手册回归正文 hello-tika-marker-xyz".getBytes(StandardCharsets.UTF_8)),
                U1, StoredFileEntity.SOURCE_KB, KB, null);
        long docId = insertDoc(f.url(), null);

        parserService.parse(docId, U1);

        assertThat(status(docId)).isEqualTo("EMBEDDING");
        String l2Content = jdbc.queryForObject(
                "SELECT string_agg(content, chr(10)) FROM knowledge_nodes WHERE document_id=? AND level='L2'",
                String.class, docId);
        assertThat(l2Content).contains("hello-tika-marker-xyz");
    }

    @Test
    void parse_encryptedXlsx_markedFailedWithParseError() {
        StoredFile f = fileStorageService.store(multipart("secret.xlsx", encryptedXlsxBytes()),
                U1, StoredFileEntity.SOURCE_KB, KB, null);
        long docId = insertDoc(f.url(), null);

        parserService.parse(docId, U1);

        assertThat(status(docId)).isEqualTo("FAILED");
        String err = jdbc.queryForObject(
                "SELECT parse_error FROM knowledge_documents WHERE id=?", String.class, docId);
        assertThat(err).isNotBlank();
    }

    // ============================ helpers：建表 / 落库 / 断言 ============================

    private StoredFile storeXlsx(String name, String... sheets) {
        return fileStorageService.store(multipart(name, xlsxBytes(sheets)),
                U1, StoredFileEntity.SOURCE_KB, KB, null);
    }

    /** 插 PENDING 文档（created_by=U1 即文件 owner，load 咽喉点放行）。 */
    private long insertDoc(String fileRef, String parseOptions) {
        jdbc.update("INSERT INTO knowledge_documents (kb_id, title, status, file_ref, created_by, parse_options) "
                        + "VALUES (?,?,?,?,?,?)",
                KB, "excel-doc", "PENDING", fileRef, U1, parseOptions);
        return jdbc.queryForObject(
                "SELECT id FROM knowledge_documents WHERE file_ref=? ORDER BY id DESC LIMIT 1",
                Long.class, fileRef);
    }

    private String status(long docId) {
        return jdbc.queryForObject(
                "SELECT status FROM knowledge_documents WHERE id=?", String.class, docId);
    }

    private List<String> l0Titles(long docId) {
        return jdbc.queryForList(
                "SELECT title FROM knowledge_nodes WHERE document_id=? AND level='L0' ORDER BY title",
                String.class, docId);
    }

    // -------------------- helpers：POI 建工作簿 --------------------

    private MockMultipartFile multipart(String name, byte[] bytes) {
        return new MockMultipartFile("file", name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    /** 3 sheet（每 sheet 1 表头 + 1 数据行）的 xlsx 字节。 */
    private byte[] xlsxBytes(String... sheetNames) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            for (String name : sheetNames) {
                fillSheet(wb, name);
            }
            return toBytes(wb);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** .xls 老格式（HSSF）字节，证明老格式路由。 */
    private byte[] xlsBytes(String... sheetNames) {
        try (HSSFWorkbook wb = new HSSFWorkbook()) {
            for (String name : sheetNames) {
                fillSheet(wb, name);
            }
            return toBytes(wb);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void fillSheet(Workbook wb, String name) {
        Sheet sh = wb.createSheet(name);
        Row hr = sh.createRow(0);
        hr.createCell(0).setCellValue("品名");
        hr.createCell(1).setCellValue("数量");
        Row r = sh.createRow(1);
        r.createCell(0).setCellValue(name + "-item");
        r.createCell(1).setCellValue("1");
    }

    private static byte[] toBytes(Workbook wb) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** agile 加密 xlsx 字节（无密码 POI 打不开 → parse 标 FAILED）。 */
    private byte[] encryptedXlsxBytes() {
        try {
            byte[] raw = xlsxBytes("机密");
            POIFSFileSystem fs = new POIFSFileSystem();
            EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
            Encryptor enc = info.getEncryptor();
            enc.confirmPassword("pwd");
            try (OPCPackage opc = OPCPackage.open(new ByteArrayInputStream(raw))) {
                opc.save(enc.getDataStream(fs));
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                fs.writeFilesystem(out);
                return out.toByteArray();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
