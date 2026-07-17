package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.file.service.StoredFile;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentVO;
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

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {

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
        // 解析异步触发：仅在上传事务提交后（AFTER_COMMIT）才跑，确保异步线程读得到 PENDING 行
        applicationEventPublisher.publishEvent(new DocumentUploadedEvent(doc.getId(), operatorId));
        return toVO(doc);
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
                .fileRef(doc.getFileRef())
                .fileHash(doc.getFileHash())
                .mime(meta == null ? null : meta.getMime())
                .originalName(meta == null ? null : meta.getOriginalName())
                .indexMode(readIndexMode(doc.getParseOptions()))
                .parseError(doc.getParseError())
                .parseOptions(doc.getParseOptions())
                .parseWarning(doc.getParseWarning())
                .createdAt(doc.getCreatedAt())
                .updatedAt(doc.getUpdatedAt())
                .build();
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
