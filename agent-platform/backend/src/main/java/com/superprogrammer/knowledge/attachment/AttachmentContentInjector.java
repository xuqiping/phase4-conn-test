package com.superprogrammer.knowledge.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.knowledge.config.RagRecallProperties;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.ContentPart;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C2 附件命中注入（WP1 Step6，规格 §4.3）：附件型证据命中后，把原件真实内容追加进证据上下文。
 *
 * <p>三路分流（按 node metadata）：
 * <ol>
 *   <li>文本类（上传时已预提取）→ attachmentText 直注，零 IO 零 LLM；</li>
 *   <li>图片 → Redis 识图缓存命中直取；miss 则实时 VLM（计费归户 docOwner、
 *       2.5s 超时），结果写缓存；</li>
 *   <li>其余（PDF 等预提取失败）→ 不注入——node content 本身已是描述+关键词，召回不受影响。</li>
 * </ol>
 *
 * <p>注入块格式：{@code [附件 {originalName}] 内容：…}；超上限截断并标注「可下载原件」；
 * VLM 超时/失败降级为 {@code （原件内容暂缺）} 标注（描述仍在，答案退化为按描述作答）。
 * 注入内容拼进 evidence content → 天然计入证据预算（fitToBudget 统一裁剪）。
 *
 * <p>服务端以 docOwner 身份读原件（fileStorageService.load(fileId, createdBy, false)）——
 * 保密库场景请求者拿不到 fileRef（/asset 403），但注入不受影响：内容进上下文与证据正文
 * 同权限级别（请求者已通过 KB 检索门），原件下载仍走既有 403 兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentContentInjector {

    /** 与 DocumentParserService.loadAttachmentText 的图片后缀口径一致（识图范围）。 */
    private static final Set<String> IMAGE_SUFFIXES = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "bmp");

    private static final String DEGRADED_PLACEHOLDER = "（原件内容暂缺）";

    private static final String VISION_USER_PROMPT =
            "请详细描述这张图片的全部内容：包括图表类型、结构层次、文字标注、关键数据与结论，用于知识检索问答。";

    private final AttachmentVisionCache visionCache;
    private final FileStorageService fileStorageService;
    private final LlmGateway llmGateway;
    private final RagRecallProperties recallProps;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 生成注入块。
     *
     * @param doc          附件文档（parseOptions 读 visionModel；createdBy 作计费归户）
     * @param nodeMetadata L2 节点 metadata（attachMode/attachmentText/fileRef/mime/originalName）
     * @return 注入块文本；null=不注入（非图片且无预提取全文——仅描述作答）
     */
    public String inject(KnowledgeDocument doc, Map<String, Object> nodeMetadata) {
        String fileRef = str(nodeMetadata.get("fileRef"));
        String originalName = str(nodeMetadata.get("originalName"));
        String attachmentText = str(nodeMetadata.get("attachmentText"));
        RagRecallProperties.Attachment cfg = recallProps.getAttachment();

        // 路径1：文本类预提取全文直注（上传时已截 8000，此处按可配上限复裁）。
        // attachmentTruncated=解析侧截 8000 的透传标志——此时长度恰好相等、比长度判不出
        // 截断（Phase4 实测修复），标志与长度双口径任一命中即补标注。
        if (attachmentText != null && !attachmentText.isBlank()) {
            boolean preTruncated = Boolean.TRUE.equals(nodeMetadata.get("attachmentTruncated"));
            return textBlock(originalName, attachmentText, cfg, preTruncated);
        }

        // 路径2：图片 → 缓存/实时识图
        if (fileRef != null && IMAGE_SUFFIXES.contains(suffixOf(fileRef))) {
            return visionBlock(doc, fileRef, originalName, nodeMetadata, cfg);
        }

        // 路径3：其余（PDF 等预提取失败）→ 不注入
        return null;
    }

    // ---------------- 路径1：全文直注 ----------------

    private String textBlock(String originalName, String text, RagRecallProperties.Attachment cfg,
                             boolean preTruncated) {
        String trimmed = cap(text, cfg.getMaxInjectChars());
        boolean truncated = trimmed.length() < text.length() || preTruncated;
        String truncatedMark = truncated ? "（已截断，可下载原件查看全文）" : "";
        return "[附件 " + (originalName == null ? "" : originalName) + "] 内容：" + trimmed + truncatedMark;
    }

    // ---------------- 路径2：图片识图 ----------------

    private String visionBlock(KnowledgeDocument doc, String fileRef, String originalName,
                               Map<String, Object> nodeMetadata, RagRecallProperties.Attachment cfg) {
        String label = "[附件 " + (originalName == null ? "" : originalName) + "] 内容：";
        String visionModel = readIndexOption(doc.getParseOptions(), "visionModel");
        if (visionModel == null || visionModel.isBlank()) {
            log.warn("附件图片未配置 visionModel，降级仅描述 docId={}", doc.getId());
            return label + DEGRADED_PLACEHOLDER;
        }

        // 缓存命中 → 直取（同图同模型同提示词版本才命中）
        String cacheKey = sha256(fileRef + "|" + visionModel + "|" + cfg.getVisionPromptVersion());
        String cached = visionCache.get(cacheKey);
        if (cached != null && !cached.isBlank()) {
            return label + cap(cached, cfg.getMaxInjectChars());
        }

        // miss → 实时识图（服务端以 docOwner 身份读原件；计费归户 docOwner）
        String text;
        try {
            text = describeImage(doc, fileRef, nodeMetadata, visionModel, cfg);
        } catch (Exception e) {
            log.warn("附件图片识图失败，降级仅描述 docId={} err={}", doc.getId(), e.getMessage());
            return label + DEGRADED_PLACEHOLDER;
        }
        if (text == null || text.isBlank()) {
            log.warn("附件图片识图返回空，降级仅描述 docId={}", doc.getId());
            return label + DEGRADED_PLACEHOLDER;
        }
        // Phase4 实测修复（Bug #7）：glm-5.1 等纯文本模型被配成 visionModel 时不会报错，
        // 而是礼貌回绝「无法查看图片（链接形式）」——非空文本若直接入缓存+注入，会把回绝话术
        // 当图片描述喂给问答（实测证据块出现「抱歉，我无法查看」且被 Redis 缓存固化）。
        // 回绝判定 → 视同失败：不写缓存，降级占位。
        if (isLikelyRefusal(text)) {
            log.warn("附件图片识图疑似被模型回绝（无视觉能力/格式不符），降级仅描述 docId={} model={} head={}",
                    doc.getId(), visionModel, text.substring(0, Math.min(60, text.length())));
            return label + DEGRADED_PLACEHOLDER;
        }
        visionCache.put(cacheKey, text);
        log.info("附件图片实时识图完成 docId={} model={} chars={}", doc.getId(), visionModel, text.length());
        String trimmed = cap(text, cfg.getMaxInjectChars());
        String truncatedMark = trimmed.length() < text.length() ? "（已截断，可下载原件查看全文）" : "";
        return label + trimmed + truncatedMark;
    }

    private String describeImage(KnowledgeDocument doc, String fileRef, Map<String, Object> nodeMetadata,
                                 String visionModel, RagRecallProperties.Attachment cfg) throws Exception {
        String fileId = stripFileRef(fileRef);
        String mime = str(nodeMetadata.get("mime"));
        if (mime == null || mime.isBlank() || !mime.startsWith("image/")) {
            mime = "image/png";
        }
        byte[] bytes;
        Resource res = fileStorageService.load(fileId, doc.getCreatedBy(), false);
        try (InputStream in = res.getInputStream()) {
            bytes = in.readAllBytes();
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
                .maxTokens(cfg.getVisionMaxTokens())
                .timeoutMs(cfg.getVisionTimeoutMs())
                .build();
        return llmGateway.chat(req, doc.getCreatedBy()).getContent();
    }

    // ---------------- 工具 ----------------

    /**
     * Phase4 实测修复（Bug #7）：识别「模型没看到图」的礼貌回绝。中英双语常见话术做包含匹配，
     * 命中任一即判回绝。误伤面控制：正常识图描述不会以「无法/不能查看·访问图片」开头或包含整句。
     */
    private static boolean isLikelyRefusal(String text) {
        String t = text.replaceAll("\\s+", "");
        return t.contains("无法查看") || t.contains("无法访问") || t.contains("无法识别该图")
                || t.contains("不能查看该图") || t.contains("无法处理该图")
                || t.contains("cannotview") || t.contains("cannotaccess")
                || t.contains("unabletoview") || t.contains("unabletoaccess")
                || t.contains("can'tview") || t.contains("can'tsee");
    }

    private static String cap(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private static String suffixOf(String fileRef) {
        String name = fileRef.substring(fileRef.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase();
    }

    /** "/api/files/f1.png" → "f1.png"；无前缀原样返回。 */
    private static String stripFileRef(String fileRef) {
        String prefix = "/api/files/";
        return fileRef.startsWith(prefix) ? fileRef.substring(prefix.length()) : fileRef;
    }

    private static String str(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    /** parse_options 单 key 读取（visionModel）；null/格式错 → null。 */
    private String readIndexOption(String parseOptions, String key) {
        if (parseOptions == null || parseOptions.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(parseOptions, Map.class);
            return str(map.get(key));
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 必在 JDK——此分支不可达，兜底原文（仅损失缓存粒度）
            return input;
        }
    }
}
