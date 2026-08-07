package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.billing.context.BillingContext;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.service.internal.BatchLlmResult;
import com.superprogrammer.knowledge.service.internal.ExcelExtractResult;
import com.superprogrammer.knowledge.service.internal.ExcelSheetExtractor;
import com.superprogrammer.knowledge.service.internal.ExtractedDocument;
import com.superprogrammer.knowledge.service.internal.L1Metadata;
import com.superprogrammer.knowledge.service.internal.Section;
import com.superprogrammer.knowledge.util.TokenEstimator;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.ContentPart;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档解析（v6 §3/§6，阶段2 第1项）：
 * Tika 抽正文 → 切 section → 按 KB.summaryStrategy 生成 L0 章节摘要 + L1 文档元数据 →
 * 调 {@link KnowledgeNodeWriter} 单事务落 nodes(ACTIVE) + index_jobs(PENDING)。
 *
 * 本类非 @Transactional：LLM 调用秒级，不可占着 DB 事务。仅状态标记 + 落库（writer）涉及 DB。
 * 失败统一 catch → markFailed(status=FAILED + parse_error)，不静默吞。
 *
 * 触发：DocumentParseListener（@TransactionalEventListener AFTER_COMMIT + @Async）→ 本类 parse()。
 * 状态机：PENDING → PARSING → SUMMARIZING → EMBEDDING（待后续 worker）｜任一异常 → FAILED。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParserService {

    private static final String CHAT_MODEL = "doubao-seed-2.0-code";
    private static final String DEFAULT_STRATEGY = "PER_SECTION";

    /** 图片 AUTO 索引：视觉模型识图提示词。要求提取可见文字+图表结构，输出简洁中文供检索向量化。 */
    private static final String VISION_USER_PROMPT = """
            请识别并详细描述这张图片的内容，用于知识库检索索引。
            提取所有可见文字（OCR）、图表/流程图结构、关键信息与主题。
            用简洁中文输出一段可被检索的描述文本，不要输出 markdown 代码块或多余解释。""";

    private static final int SEC_MIN_TOKENS = 200;
    private static final int SEC_MAX_TOKENS = 800;
    private static final int HYBRID_TOP = 10;
    private static final int L1_DOC_CLAMP_CHARS = 12000;
    private static final int SECTION_CLAMP_CHARS = 4000;

    /** markdown 标题行（# ~ ######）。用于 section 切分。 */
    private static final Pattern HEADING_LINE = Pattern.compile("#{1,6}\\s+(.+)");

    private static final String L1_SYSTEM = """
            你是企业知识库的文档摘要助手。只输出 JSON 对象，不要 markdown 代码块、不要多余解释。""";
    private static final String L1_USER_TEMPLATE = """
            文档标题：%s

            文档内容（已截断）：
            %s

            请输出 JSON：{"summary":"不超过200字的文档级摘要","outline":["章节要点1","章节要点2"],"importantRules":["关键规则1"]}""";
    private static final String BATCH_USER_TEMPLATE = """
            文档标题：%s

            文档内容（已截断）：
            %s

            请输出 JSON：{"summary":"不超过200字的文档级摘要","outline":["章节要点1"],"importantRules":["关键规则1"],"sections":[{"title":"章节标题","abstract":"该章节一句话摘要"}]}""";
    private static final String SECTION_SYSTEM = """
            你是一句话摘要助手。只输出概括句本身，不要前缀、引号或解释，不超过60字。""";
    private static final String SECTION_USER_TEMPLATE = """
            章节标题：%s

            章节内容：
            %s

            一句话概括：""";

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final FileStorageService fileStorageService;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final KnowledgeNodeWriter knowledgeNodeWriter;
    private final ExcelSheetExtractor excelExtractor;
    private final SystemSettingService systemSettingService;

    /** 监听器入口。宽 catch 有意：LlmGateway 抛裸 RuntimeException、Tika 抛 IOException/TikaException，均汇入 markFailed。 */
    public void parse(Long documentId, Long operatorId) {
        KnowledgeDocument doc = documentMapper.selectById(documentId);
        if (doc == null) {
            log.warn("待解析文档不存在 docId={}", documentId);
            return;
        }
        KnowledgeBase kb = knowledgeBaseService.ensure(doc.getKbId());
        String strategy = normalizeStrategyRead(kb.getSummaryStrategy());
        // 计费归户：异步解析线程显式种 operatorId（操作者），兜底 TaskDecorator 传播——
        // 全链 extractImageByVision/chatJson 的 gateway.chat(req) 自动归户计费，免深透传 userId。
        BillingContext.set(operatorId);
        try {
            updateStatus(documentId, "PARSING", operatorId);
            ExtractedDocument extracted = extract(doc);
            persistParseWarning(documentId, doc.getParseWarning(), operatorId);
            updateStatus(documentId, "SUMMARIZING", operatorId);
            SummaryResult result = switch (strategy) {
                case "BATCH" -> summarizeBatch(doc, extracted);
                case "HYBRID" -> summarizeHybrid(doc, extracted);
                default -> summarizePerSection(doc, extracted);
            };
            String l1Json = serializeL1(result.l1());
            knowledgeNodeWriter.writeNodes(doc, operatorId, extracted, l1Json, result.abstracts(),
                    buildNodeMetadata(doc));
            log.info("文档解析完成 docId={} strategy={} sections={}",
                    documentId, strategy, extracted.getSections().size());
        } catch (Exception e) {
            log.error("文档解析失败 docId={}: {}", documentId, e.getMessage(), e);
            markFailed(documentId, operatorId, truncate(e.getMessage(), 1900));
        } finally {
            BillingContext.clear();
        }
    }

    // -------------------- 抽取 + 切分 --------------------

    /** 分流：IMAGE/FILE 走专用分支；Excel(.xlsx/.xls) 走 POI；其余走 Tika。 */
    private ExtractedDocument extract(KnowledgeDocument doc) {
        doc.setParseWarning(null);   // 每次解析重置；Excel 路径按需回填降级告警
        String dt = doc.getDocType();
        if ("IMAGE".equals(dt)) {
            return extractImage(doc);
        }
        if ("FILE".equals(dt)) {
            return extractFile(doc);
        }
        return isExcel(doc) ? extractExcel(doc) : extractTika(doc);
    }

    /**
     * 图片抽取：MANUAL → 单 section 用用户手填索引文本；AUTO → 视觉模型识图生成文本。
     * 原件字节由 IndexJobWorker 跳过 D5 清理保留，回显经 /asset 端点。
     */
    private ExtractedDocument extractImage(KnowledgeDocument doc) {
        String mode = readIndexOption(doc.getParseOptions(), "indexMode");
        if ("MANUAL".equalsIgnoreCase(mode)) {
            return manualExtracted(doc, readIndexOption(doc.getParseOptions(), "manualIndexText"));
        }
        return extractImageByVision(doc);
    }

    /**
     * 图片 AUTO 索引（v6 阶段2）：读原件字节 base64 → 视觉 LlmRequest（image+text parts）→
     * 模型识图生成文本 → 单 section（复用 manualExtracted）。visionModel 从 parse_options 读。
     */
    private ExtractedDocument extractImageByVision(KnowledgeDocument doc) {
        String visionModel = readIndexOption(doc.getParseOptions(), "visionModel");
        if (visionModel == null || visionModel.isBlank()) {
            throw new RuntimeException("图片 AUTO 索引需指定 visionModel docId=" + doc.getId());
        }
        String fileId = stripFileRef(doc.getFileRef());
        if (fileId == null || fileId.isBlank()) {
            throw new RuntimeException("文档无 file_ref，无法读取图片 docId=" + doc.getId());
        }
        com.superprogrammer.file.entity.StoredFileEntity meta = fileStorageService.findMeta(fileId);
        String mime = meta != null && meta.getMime() != null ? meta.getMime() : "image/png";

        byte[] bytes;
        Resource res = fileStorageService.load(fileId, doc.getCreatedBy(), false);
        try (InputStream in = res.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("读取图片字节失败 docId=" + doc.getId() + ": " + e.getMessage(), e);
        }
        String base64 = Base64.getEncoder().encodeToString(bytes);

        List<ContentPart> parts = List.of(
                ContentPart.builder().type("image").data(base64).mediaType(mime).build(),
                ContentPart.builder().type("text").text(VISION_USER_PROMPT).build());
        LlmMessage userMsg = LlmMessage.builder().role("user").content("").parts(parts).build();
        LlmRequest req = LlmRequest.builder()
                .model(visionModel)
                .messages(List.of(userMsg))
                .temperature(0.3)
                .maxTokens(1024)
                .build();
        String text;
        try {
            text = llmGateway.chat(req).getContent();
        } catch (Exception e) {
            throw new RuntimeException("视觉模型识图失败 docId=" + doc.getId()
                    + " model=" + visionModel + ": " + e.getMessage(), e);
        }
        if (text == null || text.isBlank()) {
            throw new RuntimeException("视觉模型返回空内容 docId=" + doc.getId());
        }
        log.info("视觉模型识图完成 docId={} model={} chars={}", doc.getId(), visionModel, text.length());
        return manualExtracted(doc, text);
    }

    /**
     * 文件抽取：MANUAL → 单 section 用用户手填索引文本；AUTO → Tika 自动抽文本（pdf/docx/html/txt 等）。
     * 原件字节保留供下载（IndexJobWorker 跳过 D5 清理）。
     */
    private ExtractedDocument extractFile(KnowledgeDocument doc) {
        String mode = readIndexOption(doc.getParseOptions(), "indexMode");
        if ("MANUAL".equalsIgnoreCase(mode)) {
            return manualExtracted(doc, readIndexOption(doc.getParseOptions(), "manualIndexText"));
        }
        return extractTika(doc);
    }

    /** MANUAL 索引：用户手填文本 → 单 section（title=文档标题，content=手填文本）。 */
    private ExtractedDocument manualExtracted(KnowledgeDocument doc, String manualText) {
        if (manualText == null || manualText.isBlank()) {
            throw new RuntimeException("MANUAL 索引文本为空 docId=" + doc.getId());
        }
        String title = (doc.getTitle() == null || doc.getTitle().isBlank()) ? "手动索引文档" : doc.getTitle();
        Section s = Section.builder()
                .title(title).content(manualText).tokenCount(TokenEstimator.estimate(manualText)).build();
        return ExtractedDocument.builder().plainText(manualText).sections(List.of(s)).build();
    }

    /** parse_options 单 key 读取（indexMode/manualIndexText/visionModel）；null/格式错 → null。 */
    private String readIndexOption(String parseOptions, String key) {
        if (parseOptions == null || parseOptions.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> json = objectMapper.readValue(parseOptions, Map.class);
            Object v = json.get(key);
            return v == null ? null : String.valueOf(v);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 节点 metadata JSON：IMAGE/FILE 注入 {fileRef,mime,originalName} 供检索回显；
     * 其余 docType 返回 "{}"（原行为）。mime/originalName 从 stored_files 登记行读。
     */
    private String buildNodeMetadata(KnowledgeDocument doc) {
        String dt = doc.getDocType();
        if (!"IMAGE".equals(dt) && !"FILE".equals(dt)) {
            return "{}";
        }
        String fileId = stripFileRef(doc.getFileRef());
        com.superprogrammer.file.entity.StoredFileEntity meta =
                fileId == null ? null : fileStorageService.findMeta(fileId);
        try {
            Map<String, Object> m = new HashMap<>();
            m.put("fileRef", doc.getFileRef());
            if (meta != null) {
                if (meta.getMime() != null) {
                    m.put("mime", meta.getMime());
                }
                if (meta.getOriginalName() != null) {
                    m.put("originalName", meta.getOriginalName());
                }
            }
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static boolean isExcel(KnowledgeDocument doc) {
        String ref = doc.getFileRef() == null ? "" : doc.getFileRef().toLowerCase();
        return ref.endsWith(".xlsx") || ref.endsWith(".xls");
    }

    /** Excel：POI sheet 级抽取。selectedSheets 从 parse_options 解析；降级告警写回 doc.parseWarning。 */
    private ExtractedDocument extractExcel(KnowledgeDocument doc) {
        String fileId = stripFileRef(doc.getFileRef());
        if (fileId == null || fileId.isBlank()) {
            throw new RuntimeException("文档无 file_ref，无法读取原文 docId=" + doc.getId());
        }
        Set<String> selected = readSelectedSheets(doc.getParseOptions());
        Resource res = fileStorageService.load(fileId, doc.getCreatedBy(), false);
        ExcelExtractResult result;
        try (InputStream in = res.getInputStream()) {
            result = excelExtractor.extract(in, selected,
                    systemSettingService.getExcelColThreshold(),
                    systemSettingService.getExcelRowChunkSize(),
                    systemSettingService.getExcelCellMaxChars(),
                    systemSettingService.getExcelMaxRowsPerSheet());
        } catch (Exception e) {
            throw new RuntimeException("Excel 抽取失败 docId=" + doc.getId() + ": " + e.getMessage(), e);
        }
        if (!result.warnings().isEmpty()) {
            doc.setParseWarning(String.join("；", result.warnings()));
        }
        return result.document();
    }

    /** parse_options.selectedSheets 解析；null/空/格式错 → 空集（= 导全部 sheet）。 */
    private Set<String> readSelectedSheets(String parseOptions) {
        if (parseOptions == null || parseOptions.isBlank()) {
            return Set.of();
        }
        try {
            Map<?, ?> json = objectMapper.readValue(parseOptions, Map.class);
            Object sel = json.get("selectedSheets");
            if (sel instanceof List<?> list) {
                Set<String> set = new HashSet<>();
                for (Object o : list) {
                    if (o != null) set.add(String.valueOf(o));
                }
                return set;
            }
        } catch (Exception ignored) {
            // 容错：格式错当未选（导全部）
        }
        return Set.of();
    }

    private ExtractedDocument extractTika(KnowledgeDocument doc) {
        String fileId = stripFileRef(doc.getFileRef());
        if (fileId == null || fileId.isBlank()) {
            throw new RuntimeException("文档无 file_ref，无法读取原文 docId=" + doc.getId());
        }
        // 走 load 咽喉点：文档 owner（createdBy）即文件 owner，非 admin 解析场景
        Resource res = fileStorageService.load(fileId, doc.getCreatedBy(), false);
        String text;
        try (InputStream in = res.getInputStream()) {
            Tika tika = new Tika();   // 非线程安全，每次新建
            text = tika.parseToString(in);
        } catch (Exception e) {
            throw new RuntimeException("Tika 抽取失败 docId=" + doc.getId() + ": " + e.getMessage(), e);
        }
        if (text == null || text.isBlank()) {
            log.warn("Tika 抽出空文本 docId={}", doc.getId());
        }
        return ExtractedDocument.builder()
                .plainText(text)
                .sections(split(text))
                .build();
    }

    /** section 切分：标题感知优先，无标题退化按大小累积；超长段再切；过小段合并。 */
    private List<Section> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // Pass 1：按标题感知分块
        List<String[]> blocks = new ArrayList<>();   // {title, body}
        String currentTitle = null;
        StringBuilder body = new StringBuilder();
        for (String para : text.split("(?:\\r?\\n){2,}")) {
            String p = para.strip();
            if (p.isEmpty()) {
                continue;
            }
            Matcher hm = HEADING_LINE.matcher(p);
            if (hm.lookingAt()) {
                if (body.length() > 0 || currentTitle != null) {
                    blocks.add(new String[]{currentTitle, body.toString()});
                }
                currentTitle = hm.group(1).strip();
                body.setLength(0);
                int nl = p.indexOf('\n');
                if (nl >= 0) {
                    body.append(p.substring(nl + 1).strip());
                }
            } else {
                if (body.length() > 0) {
                    body.append("\n\n");
                }
                body.append(p);
            }
        }
        if (body.length() > 0 || currentTitle != null) {
            blocks.add(new String[]{currentTitle, body.toString()});
        }

        // 无标题 → 退化按大小切
        if (blocks.isEmpty()) {
            for (String chunk : partitionBySize(text, SEC_MAX_TOKENS * 4)) {
                blocks.add(new String[]{null, chunk});
            }
        }

        List<Section> sections = new ArrayList<>();
        int idx = 1;
        for (String[] b : blocks) {
            String title = (b[0] == null || b[0].isBlank()) ? ("段落 " + idx) : b[0];
            String content = b[1] == null ? "" : b[1].strip();
            idx++;
            if (content.isEmpty()) {
                continue;
            }
            for (String chunk : partitionBySize(content, SEC_MAX_TOKENS * 4)) {
                sections.add(Section.builder()
                        .title(title)
                        .content(chunk)
                        .tokenCount(TokenEstimator.estimate(chunk))
                        .build());
            }
        }
        return mergeTiny(sections);
    }

    private List<Section> mergeTiny(List<Section> in) {
        if (in.size() <= 1) {
            return in;
        }
        List<Section> out = new ArrayList<>();
        for (Section s : in) {
            if (!out.isEmpty() && s.getTokenCount() < SEC_MIN_TOKENS) {
                Section prev = out.get(out.size() - 1);
                prev.setContent(prev.getContent() + "\n\n" + s.getContent());
                prev.setTokenCount(TokenEstimator.estimate(prev.getContent()));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /** 按段落边界累积到 maxChars，超长单段硬切。 */
    private List<String> partitionBySize(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        StringBuilder buf = new StringBuilder();
        for (String para : text.split("(?:\\r?\\n){2,}")) {
            String p = para.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (buf.length() + p.length() + 2 > maxChars && buf.length() > 0) {
                out.add(buf.toString());
                buf.setLength(0);
            }
            if (p.length() > maxChars) {
                if (buf.length() > 0) {
                    out.add(buf.toString());
                    buf.setLength(0);
                }
                for (int s = 0; s < p.length(); s += maxChars) {
                    out.add(p.substring(s, Math.min(p.length(), s + maxChars)));
                }
            } else {
                if (buf.length() > 0) {
                    buf.append("\n\n");
                }
                buf.append(p);
            }
        }
        if (buf.length() > 0) {
            out.add(buf.toString());
        }
        return out;
    }

    // -------------------- LLM 摘要（3 模式） --------------------

    private SummaryResult summarizePerSection(KnowledgeDocument doc, ExtractedDocument ex) {
        String text = clamp(ex.getPlainText(), L1_DOC_CLAMP_CHARS);
        L1Metadata l1 = parseL1(chatJson(L1_SYSTEM, String.format(L1_USER_TEMPLATE, title(doc), text), 1024), ex);
        List<String> abstracts = new ArrayList<>();
        for (Section s : ex.getSections()) {
            abstracts.add(perSectionAbstract(s));
        }
        return new SummaryResult(l1, abstracts);
    }

    private SummaryResult summarizeBatch(KnowledgeDocument doc, ExtractedDocument ex) {
        String text = clamp(ex.getPlainText(), L1_DOC_CLAMP_CHARS);
        BatchLlmResult parsed = parseBatch(
                chatJson(L1_SYSTEM, String.format(BATCH_USER_TEMPLATE, title(doc), text), 2048));

        L1Metadata l1;
        Map<String, String> byTitle = new HashMap<>();
        if (parsed != null) {
            l1 = L1Metadata.builder()
                    .summary(parsed.getSummary())
                    .outline(parsed.getOutline())
                    .importantRules(parsed.getImportantRules())
                    .build();
            if (parsed.getSections() != null) {
                for (BatchLlmResult.BatchSection bs : parsed.getSections()) {
                    if (bs != null && bs.getTitle() != null) {
                        byTitle.put(norm(bs.getTitle()), bs.getAbstractText());
                    }
                }
            }
        } else {
            l1 = degradedL1(ex);
        }

        List<String> abstracts = new ArrayList<>();
        for (Section s : ex.getSections()) {
            String a = byTitle.get(norm(s.getTitle()));
            if (a == null) {
                for (Map.Entry<String, String> e : byTitle.entrySet()) {
                    String st = s.getTitle();
                    if (st != null && (st.contains(e.getKey()) || e.getKey().contains(st))) {
                        a = e.getValue();
                        break;
                    }
                }
            }
            abstracts.add((a == null || a.isBlank()) ? null : a.trim());
        }
        return new SummaryResult(l1, abstracts);
    }

    private SummaryResult summarizeHybrid(KnowledgeDocument doc, ExtractedDocument ex) {
        String text = clamp(ex.getPlainText(), L1_DOC_CLAMP_CHARS);
        L1Metadata l1 = parseL1(chatJson(L1_SYSTEM, String.format(L1_USER_TEMPLATE, title(doc), text), 1024), ex);
        List<Section> secs = ex.getSections();
        List<String> abstracts = new ArrayList<>(Collections.nCopies(secs.size(), null));
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < secs.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt(i -> -secs.get(i).getTokenCount()));
        int n = Math.min(HYBRID_TOP, order.size());
        for (int k = 0; k < n; k++) {
            int i = order.get(k);
            abstracts.set(i, perSectionAbstract(secs.get(i)));
        }
        return new SummaryResult(l1, abstracts);
    }

    /** 单 section 摘要：失败返回 null（writer 兜底），不拖垮整文档。 */
    private String perSectionAbstract(Section section) {
        try {
            String body = clamp(section.getContent(), SECTION_CLAMP_CHARS);
            String out = chatJson(SECTION_SYSTEM, String.format(SECTION_USER_TEMPLATE, section.getTitle(), body), 120);
            return out == null || out.isBlank() ? null : out.trim();
        } catch (Exception e) {
            log.warn("单 section 摘要失败 title={}: {}", section.getTitle(), e.getMessage());
            return null;
        }
    }

    private String chatJson(String system, String user, int maxTokens) {
        LlmRequest req = LlmRequest.builder()
                .model(CHAT_MODEL)
                .messages(List.of(
                        LlmMessage.builder().role("system").content(system).build(),
                        LlmMessage.builder().role("user").content(user).build()))
                .temperature(0.3)
                .maxTokens(maxTokens)
                .stream(false)
                .build();
        return llmGateway.chat(req).getContent();
    }

    // -------------------- JSON 解析（容错） --------------------

    private L1Metadata parseL1(String json, ExtractedDocument fallback) {
        try {
            L1Metadata l1 = objectMapper.readValue(stripFence(json), L1Metadata.class);
            return l1 == null ? degradedL1(fallback) : l1;
        } catch (Exception e) {
            log.warn("L1 JSON 解析失败，降级: {}", e.getMessage());
            return degradedL1(fallback);
        }
    }

    private BatchLlmResult parseBatch(String json) {
        try {
            return objectMapper.readValue(stripFence(json), BatchLlmResult.class);
        } catch (Exception e) {
            log.warn("BATCH JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private L1Metadata degradedL1(ExtractedDocument ex) {
        String t = ex == null || ex.getPlainText() == null ? "" : ex.getPlainText();
        return L1Metadata.builder()
                .summary(firstChars(t, 200))
                .outline(List.of())
                .importantRules(List.of())
                .build();
    }

    private String serializeL1(L1Metadata l1) {
        try {
            return objectMapper.writeValueAsString(l1);
        } catch (Exception e) {
            throw new RuntimeException("L1 序列化失败: " + e.getMessage(), e);
        }
    }

    // -------------------- 状态标记 --------------------

    private void updateStatus(Long docId, String status, Long operatorId) {
        LambdaUpdateWrapper<KnowledgeDocument> uw = new LambdaUpdateWrapper<>();
        uw.eq(KnowledgeDocument::getId, docId)
                .set(KnowledgeDocument::getStatus, status)
                .set(KnowledgeDocument::getUpdatedBy, operatorId);
        documentMapper.update(null, uw);
    }

    private void markFailed(Long docId, Long operatorId, String error) {
        try {
            LambdaUpdateWrapper<KnowledgeDocument> uw = new LambdaUpdateWrapper<>();
            uw.eq(KnowledgeDocument::getId, docId)
                    .set(KnowledgeDocument::getStatus, "FAILED")
                    .set(KnowledgeDocument::getParseError, error)
                    .set(KnowledgeDocument::getUpdatedBy, operatorId);
            documentMapper.update(null, uw);
        } catch (Exception e) {
            log.error("标记 FAILED 失败 docId={}: {}", docId, e.getMessage());
        }
    }

    /** 持久化非致命解析告警（Excel 截断/降级）；null 则清空（V39 parse_warning）。 */
    private void persistParseWarning(Long docId, String warning, Long operatorId) {
        if (warning == null) {
            warning = "";
        }
        try {
            LambdaUpdateWrapper<KnowledgeDocument> uw = new LambdaUpdateWrapper<>();
            uw.eq(KnowledgeDocument::getId, docId)
                    .set(KnowledgeDocument::getParseWarning, warning.isEmpty() ? null : warning)
                    .set(KnowledgeDocument::getUpdatedBy, operatorId);
            documentMapper.update(null, uw);
        } catch (Exception e) {
            log.error("持久化 parse_warning 失败 docId={}: {}", docId, e.getMessage());
        }
    }

    // -------------------- 小工具 --------------------

    private String normalizeStrategyRead(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return DEFAULT_STRATEGY;
        }
        String upper = strategy.trim().toUpperCase();
        return upper.equals("BATCH") || upper.equals("HYBRID") ? upper : DEFAULT_STRATEGY;
    }

    private String stripFileRef(String fileRef) {
        if (fileRef == null) {
            return null;
        }
        String f = fileRef.trim();
        String prefix = "/api/files/";
        return f.startsWith(prefix) ? f.substring(prefix.length()) : f;
    }

    private String title(KnowledgeDocument doc) {
        return doc.getTitle() == null || doc.getTitle().isBlank() ? "未命名文档" : doc.getTitle();
    }

    private String clamp(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    private String firstChars(String text, int n) {
        String c = clamp(text, n);
        return c == null ? "" : c;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("\\s+", "");
    }

    private String stripFence(String json) {
        if (json == null) {
            return "";
        }
        String j = json.trim();
        if (j.startsWith("```")) {
            int nl = j.indexOf('\n');
            if (nl > 0) {
                j = j.substring(nl + 1);
            }
            int end = j.lastIndexOf("```");
            if (end >= 0) {
                j = j.substring(0, end);
            }
            j = j.trim();
        }
        return j;
    }

    /** 摘要产物：L1 + 与 extracted.sections 对齐的 L0 摘要列表（null=待 writer 兜底）。 */
    private record SummaryResult(L1Metadata l1, List<String> abstracts) {
    }
}
