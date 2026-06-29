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
     */
    @Transactional
    public KnowledgeDocumentVO upload(Long kbId, MultipartFile file, String tempFileRef,
                                      List<String> selectedSheets, Long operatorId, boolean admin) {
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

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKbId(kbId);
        doc.setTitle(title);
        doc.setStatus("PENDING");
        doc.setFileRef(fileRef);
        doc.setFileHash(fileHash);
        doc.setCreatedBy(operatorId);
        if (selectedSheets != null && !selectedSheets.isEmpty()) {
            try {
                doc.setParseOptions(objectMapper.writeValueAsString(
                        Map.of("selectedSheets", selectedSheets)));
            } catch (Exception ignored) {
                // 序列化失败则不记 parse_options（= 导全部 sheet），不阻断上传
            }
        }
        documentMapper.insert(doc);
        // 解析异步触发：仅在上传事务提交后（AFTER_COMMIT）才跑，确保异步线程读得到 PENDING 行
        applicationEventPublisher.publishEvent(new DocumentUploadedEvent(doc.getId(), operatorId));
        return toVO(doc);
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
        return KnowledgeDocumentVO.builder()
                .id(doc.getId())
                .kbId(doc.getKbId())
                .title(doc.getTitle())
                .docType(doc.getDocType())
                .status(doc.getStatus())
                .fileRef(doc.getFileRef())
                .fileHash(doc.getFileHash())
                .parseError(doc.getParseError())
                .parseOptions(doc.getParseOptions())
                .parseWarning(doc.getParseWarning())
                .createdAt(doc.getCreatedAt())
                .updatedAt(doc.getUpdatedAt())
                .build();
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
