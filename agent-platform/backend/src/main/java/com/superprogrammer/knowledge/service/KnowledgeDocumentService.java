package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.file.service.StoredFile;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentVO;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentUpdateRequest;
import com.superprogrammer.knowledge.dto.SheetPreviewVO;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.event.DocumentUploadedEvent;
import com.superprogrammer.knowledge.event.VisibilityInvalidationEvent;
import com.superprogrammer.knowledge.mapper.KnowledgeDocEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.knowledge.service.internal.ExcelSheetExtractor;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {

    private static final Set<String> AUTHORITY_LEVELS = Set.of(
            "OFFICIAL", "APPROVED", "REFERENCE", "UNVERIFIED");
    private static final Set<String> CONFIDENTIALITY_LEVELS = Set.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final int MAX_TAGS = 20;
    private static final int MAX_TAG_LENGTH = 64;

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeEmbeddingMapper embeddingMapper;
    private final KnowledgeDocEmbeddingMapper docEmbeddingMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final FileStorageService fileStorageService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ObjectMapper objectMapper;
    private final ExcelSheetExtractor excelExtractor;
    private final SystemSettingService systemSettingService;
    private final KnowledgeDocumentVersionService versionService;

    /** 阶段1：预读 Excel sheet 名。存文件（PREVIEW，10min TTL）+ POI 只读名，不建 knowledge_document 行。 */
    public SheetPreviewVO previewSheets(Long kbId, MultipartFile file, Long operatorId, boolean admin) {
        KnowledgeBase kb = knowledgeBaseService.ensure(kbId);
        if (!knowledgeBaseService.canWrite(kb, operatorId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权向该知识库上传文档");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传文件不能为空");
        }
        StoredFile stored = fileStorageService.store(file, operatorId,
                com.superprogrammer.file.entity.StoredFileEntity.SOURCE_PREVIEW, null,
                OffsetDateTime.now().plusMinutes(10));
        List<String> names;
        try (InputStream in = fileStorageService.load(stored.fileId(), operatorId, false).getInputStream()) {
            names = excelExtractor.sheetNames(in, systemSettingService.getExcelPreviewMaxSheets());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Excel 预读 sheet 名失败");
        }
        return SheetPreviewVO.builder()
                .tempFileRef(stored.url())
                .fileName(stored.name())
                .sheetNames(names)
                .build();
    }

    /**
     * 阶段2：上传。
     * <p>Excel picker 路径传 tempFileRef（复用阶段1 存的文件，零重传）+ selectedSheets；
     * 非 Excel 或直传走 file。tempFileRef 经 load 咽喉点强校验归属（非 owner → FORBIDDEN）。
     *
     * <p>图片/文件知识库扩展：docType（IMAGE/FILE，空=按后缀推断）+ indexMode（MANUAL/AUTO，空=AUTO）
     * + manualIndexText（MANUAL 必填，索引文本）+ visionModel（IMAGE+AUTO 必填，P2 视觉模型）。
     * 全部并入 parse_options JSON；doc.setDocType 始终写入（补历史 NULL gap）。
     */
    @Transactional
    public KnowledgeDocumentVO upload(Long kbId, MultipartFile file, String tempFileRef,
                                      List<String> selectedSheets,
                                      String docType, String indexMode, String manualIndexText,
                                      String visionModel, Long operatorId, boolean admin) {
        KnowledgeBase kb = knowledgeBaseService.ensure(kbId);
        if (!knowledgeBaseService.canWrite(kb, operatorId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权向该知识库上传文档");
        }
        boolean reuse = tempFileRef != null && !tempFileRef.isBlank();
        if (!reuse && (file == null || file.isEmpty())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传文件不能为空");
        }

        String fileRef;
        String fileHash;
        String title;
        if (reuse) {
            // 复用阶段1 文件：归属校验（注入死在咽喉点）；重算 sha256 防与 file_hash 唯一约束冲突
            String fileId = stripFileRef(tempFileRef);
            Resource res = fileStorageService.load(fileId, operatorId, false);
            fileRef = tempFileRef;
            title = deriveName(tempFileRef);
            fileHash = sha256(res);
        } else {
            StoredFile stored = fileStorageService.store(file, operatorId,
                    com.superprogrammer.file.entity.StoredFileEntity.SOURCE_KB, kbId, null);
            fileRef = stored.url();
            title = stored.name();
            try {
                fileHash = sha256(file);
            } catch (NoSuchAlgorithmException e) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件哈希计算失败");
            }
        }

        // docType：用户覆盖优先，否则按标题后缀推断（IMAGE/FILE/EXCEL/PDF/...）；始终写入（补 NULL gap）
        String resolvedDocType = resolveDocType(docType, title);
        String resolvedIndexMode = normalizeIndexMode(indexMode);
        if ("MANUAL".equals(resolvedIndexMode)) {
            if (manualIndexText == null || manualIndexText.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "MANUAL 索引方式必须提供索引文本（manualIndexText）");
            }
            if (manualIndexText.length() > 4000) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "索引文本过长（>4000 字）");
            }
        }
        if ("IMAGE".equals(resolvedDocType) && "AUTO".equals(resolvedIndexMode)
                && (visionModel == null || visionModel.isBlank())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "图片 AUTO 索引必须选择视觉模型（visionModel）");
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKbId(kbId);
        doc.setTitle(title);
        doc.setStatus("PENDING");
        doc.setDocType(resolvedDocType);
        doc.setFileRef(fileRef);
        doc.setFileHash(fileHash);
        doc.setCreatedBy(operatorId);
        doc.setParseOptions(buildParseOptions(selectedSheets, resolvedIndexMode, manualIndexText, visionModel));
        documentMapper.insert(doc);
        var initialVersion = versionService.createInitialVersion(
                doc.getId(), fileRef, fileHash, operatorId, "首次上传");
        doc.setCurrentVersionId(initialVersion.getId());
        // 解析异步触发：仅在上传事务提交后（AFTER_COMMIT）才跑，确保异步线程读得到 PENDING 行
        applicationEventPublisher.publishEvent(new DocumentUploadedEvent(doc.getId(), operatorId));
        return toVO(doc);
    }

    /**
     * 安全体系 S3 · SEC-FR-051：解除注入隔离（管理员复核通过后调用）。
     * 置回 PENDING 并显式重发解析触发事件——解析由 DocumentUploadedEvent 驱动，仅改状态不会自动跑。
     */
    public void unquarantine(Long id, Long operatorId, boolean admin) {
        KnowledgeDocument doc = ensure(id);
        if (!knowledgeBaseService.canManage(doc.getKbId(), operatorId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理该知识库文档");
        }
        if (!"QUARANTINED".equals(doc.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅隔离中的文档可解除隔离");
        }
        KnowledgeDocument upd = new KnowledgeDocument();
        upd.setId(id);
        upd.setStatus("PENDING");
        upd.setQuarantineReason("");
        documentMapper.updateById(upd);
        applicationEventPublisher.publishEvent(new DocumentUploadedEvent(id, operatorId));
    }

    /** docType 解析：显式传入非空 → 归一化校验；否则按标题后缀推断。 */
    private static String resolveDocType(String docType, String title) {
        if (docType != null && !docType.isBlank()) {
            String t = docType.trim().toUpperCase();
            return switch (t) {
                case "IMAGE", "FILE", "EXCEL", "PDF", "DOCX", "HTML", "TEXT" -> t;
                default -> docType.trim();   // 自由字符串，不强限
            };
        }
        String name = title == null ? "" : title.toLowerCase();
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".bmp")) {
            return "IMAGE";
        }
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return "EXCEL";
        }
        if (name.endsWith(".pdf")) {
            return "PDF";
        }
        if (name.endsWith(".docx") || name.endsWith(".doc")) {
            return "DOCX";
        }
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            return "HTML";
        }
        if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown")) {
            return "TEXT";
        }
        return "FILE";
    }

    private static String normalizeIndexMode(String indexMode) {
        if (indexMode == null || indexMode.isBlank()) {
            return "AUTO";
        }
        String m = indexMode.trim().toUpperCase();
        return "MANUAL".equals(m) ? "MANUAL" : "AUTO";
    }

    /** 合并 parse_options：selectedSheets（Excel）+ indexMode/manualIndexText/visionModel（图片/文件）。null 字段省略。 */
    private String buildParseOptions(List<String> selectedSheets, String indexMode,
                                     String manualIndexText, String visionModel) {
        try {
            Map<String, Object> opts = new java.util.LinkedHashMap<>();
            if (selectedSheets != null && !selectedSheets.isEmpty()) {
                opts.put("selectedSheets", selectedSheets);
            }
            opts.put("indexMode", indexMode);
            if (manualIndexText != null && !manualIndexText.isBlank()) {
                opts.put("manualIndexText", manualIndexText.trim());
            }
            if (visionModel != null && !visionModel.isBlank()) {
                opts.put("visionModel", visionModel.trim());
            }
            return objectMapper.writeValueAsString(opts);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 流式返回图片/文件原件（KB 成员可读，跨用户）。
     * canRead 校验通过后，按 admin 通道取字节（owner 咽喉点由 KB 读权限替代）。
     * IMAGE → Content-Disposition inline（浏览器内联显图）；FILE → attachment 下载（带原名）。
     */
    public ResponseEntity<org.springframework.core.io.Resource> streamAsset(Long id, Long operatorId, boolean admin) {
        KnowledgeDocument doc = ensure(id);
        if (!knowledgeBaseService.canRead(knowledgeBaseService.ensure(doc.getKbId()), operatorId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该文档");
        }
        String fileId = stripFileRef(doc.getFileRef());
        if (fileId == null || fileId.isBlank()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档无原件");
        }
        Resource res = fileStorageService.load(fileId, operatorId, admin);
        com.superprogrammer.file.entity.StoredFileEntity meta = fileStorageService.findMeta(fileId);
        String mime = meta != null && meta.getMime() != null && !meta.getMime().isBlank()
                ? meta.getMime() : fileStorageService.detectMimeType(fileId);
        String originalName = meta != null && meta.getOriginalName() != null ? meta.getOriginalName() : fileId;
        boolean isImage = "IMAGE".equals(doc.getDocType()) || (mime != null && mime.startsWith("image/"));
        String disposition = (isImage ? "inline" : "attachment; filename=\"" + originalName + "\"");
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(mime))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(res);
    }

    /** fileRef（/api/files/{fileId}）→ fileId。 */
    private static String stripFileRef(String fileRef) {
        if (fileRef == null) {
            return null;
        }
        String prefix = "/api/files/";
        int idx = fileRef.indexOf(prefix);
        return idx >= 0 ? fileRef.substring(idx + prefix.length()) : fileRef;
    }

    /** 从 fileRef 推导展示名（取最后一段 fileId 作名）。 */
    private static String deriveName(String fileRef) {
        if (fileRef == null) {
            return "file";
        }
        String fid = stripFileRef(fileRef);
        return fid == null || fid.isBlank() ? "file" : fid;
    }

    public List<KnowledgeDocumentVO> list(Long kbId, Long operatorId, boolean admin) {
        KnowledgeBase kb = knowledgeBaseService.ensure(kbId);
        if (!knowledgeBaseService.canRead(kb, operatorId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该知识库");
        }
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getKbId, kbId)
                .orderByDesc(KnowledgeDocument::getCreatedAt);
        return documentMapper.selectList(wrapper).stream()
                .map(this::toVO)
                .toList();
    }

    public KnowledgeDocumentVO get(Long id, Long operatorId, boolean admin) {
        KnowledgeDocument doc = ensure(id);
        if (!knowledgeBaseService.canRead(knowledgeBaseService.ensure(doc.getKbId()), operatorId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该文档");
        }
        return toVO(doc);
    }

    /** 更新不影响原文件内容的治理元数据；密级变更仅管理员可执行。 */
    @Transactional
    public KnowledgeDocumentVO updateMetadata(Long id, KnowledgeDocumentUpdateRequest request,
                                               Long operatorId, boolean admin) {
        KnowledgeDocument current = ensure(id);
        KnowledgeBase kb = knowledgeBaseService.ensure(current.getKbId());
        if (!knowledgeBaseService.canManage(kb, operatorId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理该文档元数据");
        }
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档元数据不能为空");
        }
        OffsetDateTime effectiveAt = request.getEffectiveAt();
        OffsetDateTime expiredAt = request.getExpiredAt();
        if (effectiveAt != null && expiredAt != null && !effectiveAt.isBefore(expiredAt)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "生效时间必须早于失效时间");
        }
        String authority = normalizeEnum(request.getAuthorityLevel(), "REFERENCE",
                AUTHORITY_LEVELS, "权威等级");
        String confidentiality = normalizeEnum(request.getConfidentialityLevel(), "INTERNAL",
                CONFIDENTIALITY_LEVELS, "密级");
        String oldConfidentiality = normalizeEnum(current.getConfidentialityLevel(), "INTERNAL",
                CONFIDENTIALITY_LEVELS, "原密级");
        if (!admin && !oldConfidentiality.equals(confidentiality)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员可以调整文档密级");
        }
        List<String> tags = normalizeTags(request.getTags());

        KnowledgeDocument update = new KnowledgeDocument();
        update.setId(id);
        update.setOwnerId(request.getOwnerId());
        update.setSourceType(trimToNull(request.getSourceType(), 64, "来源类型"));
        update.setSourceUri(trimToNull(request.getSourceUri(), 2000, "来源地址"));
        update.setSourceUpdatedAt(request.getSourceUpdatedAt());
        update.setAuthorityLevel(authority);
        update.setConfidentialityLevel(confidentiality);
        update.setTags(writeTags(tags));
        update.setEffectiveAt(effectiveAt);
        update.setExpiredAt(expiredAt);
        update.setUpdatedBy(operatorId);
        if (documentMapper.updateGovernance(update) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "文档治理信息更新冲突，请刷新后重试");
        }
        applicationEventPublisher.publishEvent(new VisibilityInvalidationEvent(current.getKbId()));

        current.setOwnerId(update.getOwnerId());
        current.setSourceType(update.getSourceType());
        current.setSourceUri(update.getSourceUri());
        current.setSourceUpdatedAt(update.getSourceUpdatedAt());
        current.setAuthorityLevel(authority);
        current.setConfidentialityLevel(confidentiality);
        current.setTags(update.getTags());
        current.setEffectiveAt(effectiveAt);
        current.setExpiredAt(expiredAt);
        return toVO(current);
    }

    /** 新版本必须上传真实文件；fileRef 与 sourceHash 均由后端生成，禁止客户端伪造。 */
    @Transactional
    public com.superprogrammer.knowledge.dto.KnowledgeDocumentVersionVO createVersion(
            Long documentId, MultipartFile file, Long expectedCurrentVersionId,
            String changeNote, Long operatorId, boolean admin) {
        KnowledgeDocument doc = ensure(documentId);
        KnowledgeBase kb = knowledgeBaseService.ensure(doc.getKbId());
        if (!knowledgeBaseService.canManage(kb, operatorId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理该文档版本");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "版本文件不能为空");
        }
        StoredFile stored = fileStorageService.store(file, operatorId,
                com.superprogrammer.file.entity.StoredFileEntity.SOURCE_KB, doc.getKbId(), null);
        String sourceHash;
        try {
            sourceHash = sha256(file);
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件哈希计算失败");
        }
        var version = versionService.createDraftVersion(documentId, expectedCurrentVersionId,
                stored.url(), sourceHash, changeNote, operatorId, admin);
        return versionService.toVO(version);
    }

    @Transactional
    public void delete(Long id, Long operatorId, boolean admin) {
        KnowledgeDocument doc = ensure(id);
        if (!knowledgeBaseService.canManage(knowledgeBaseService.ensure(doc.getKbId()), operatorId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员或知识库创建者可删除文档");
        }
        documentMapper.deleteById(id);
        // 文档删除 → 软删其全部节点 + 硬删对应向量行（emb 表无 deleted 列，本就硬删语义）。
        // 修 Gap-1：原仅软删 doc，node(deleted=0)+向量成真孤儿，且 orphan SQL 只看 node 不看 doc → 对账也漏。
        nodeMapper.delete(new LambdaQueryWrapper<KnowledgeNode>()
                .eq(KnowledgeNode::getDocumentId, id));
        embeddingMapper.deleteByDocument(id);
        docEmbeddingMapper.deleteByDocument(id);   // Phase3：清 L1 文档向量（doc 软删→L1 向量同步硬删）
        // 修 orphan 文件泄漏：删文档同步删磁盘字节 + stored_files 行
        if (doc.getFileRef() != null) {
            fileStorageService.delete(stripFileRef(doc.getFileRef()));
        }
        // 文档删除 → 该 KB 可见集缓存失效（doc_id 移除）
        applicationEventPublisher.publishEvent(new VisibilityInvalidationEvent(doc.getKbId()));
    }

    private KnowledgeDocument ensure(Long id) {
        KnowledgeDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        return doc;
    }

    private KnowledgeDocumentVO toVO(KnowledgeDocument doc) {
        String fileId = stripFileRef(doc.getFileRef());
        com.superprogrammer.file.entity.StoredFileEntity meta =
                fileId == null ? null : fileStorageService.findMeta(fileId);
        return KnowledgeDocumentVO.builder()
                .id(doc.getId())
                .kbId(doc.getKbId())
                .title(doc.getTitle())
                .docType(doc.getDocType())
                .status(doc.getStatus())
                .currentVersionId(doc.getCurrentVersionId())
                .fileRef(doc.getFileRef())
                .fileHash(doc.getFileHash())
                .mime(meta == null ? null : meta.getMime())
                .originalName(meta == null ? null : meta.getOriginalName())
                .indexMode(readIndexMode(doc.getParseOptions()))
                .parseError(doc.getParseError())
                .parseOptions(doc.getParseOptions())
                .parseWarning(doc.getParseWarning())
                .quarantineReason(doc.getQuarantineReason())
                .ownerId(doc.getOwnerId())
                .sourceType(doc.getSourceType())
                .sourceUri(doc.getSourceUri())
                .sourceUpdatedAt(doc.getSourceUpdatedAt())
                .authorityLevel(doc.getAuthorityLevel())
                .confidentialityLevel(doc.getConfidentialityLevel())
                .tags(readTags(doc.getTags()))
                .effectiveAt(doc.getEffectiveAt())
                .expiredAt(doc.getExpiredAt())
                .createdAt(doc.getCreatedAt())
                .updatedAt(doc.getUpdatedAt())
                .build();
    }

    private static String normalizeEnum(String value, String defaultValue, Set<String> allowed, String label) {
        String normalized = value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase();
        if (!allowed.contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, label + "不合法");
        }
        return normalized;
    }

    private static List<String> normalizeTags(List<String> rawTags) {
        if (rawTags == null) return List.of();
        if (rawTags.size() > MAX_TAGS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "标签数量不能超过 " + MAX_TAGS);
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : rawTags) {
            if (raw == null || raw.isBlank()) continue;
            String tag = raw.trim();
            if (tag.length() > MAX_TAG_LENGTH) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "单个标签不能超过 " + MAX_TAG_LENGTH + " 字符");
            }
            normalized.add(tag);
        }
        return List.copyOf(normalized);
    }

    private String writeTags(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "标签序列化失败");
        }
    }

    private List<String> readTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(tagsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String trimToNull(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > maxLength) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, label + "过长");
        }
        return result;
    }

    /** parse_options.indexMode 解析（VO 展示用）；null/格式错 → null。 */
    private String readIndexMode(String parseOptions) {
        if (parseOptions == null || parseOptions.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> json = objectMapper.readValue(parseOptions, Map.class);
            Object m = json.get("indexMode");
            return m == null ? null : String.valueOf(m);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String sha256(MultipartFile file) throws NoSuchAlgorithmException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(file.getBytes());
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            if (e instanceof NoSuchAlgorithmException) {
                throw (NoSuchAlgorithmException) e;
            }
            throw new RuntimeException(e);
        }
    }

    /** sha256(Resource)：tempFileRef 复用路径下文件已在存储里，重算防与 file_hash 唯一约束冲突。 */
    private static String sha256(Resource res) {
        try (InputStream in = res.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件哈希计算失败");
        }
    }
}
