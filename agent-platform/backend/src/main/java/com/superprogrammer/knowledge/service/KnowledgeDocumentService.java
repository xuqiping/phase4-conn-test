package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.file.service.StoredFile;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentVO;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.event.DocumentUploadedEvent;
import com.superprogrammer.knowledge.event.VisibilityInvalidationEvent;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final FileStorageService fileStorageService;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public KnowledgeDocumentVO upload(Long kbId, MultipartFile file, Long operatorId, boolean admin) {
        KnowledgeBase kb = knowledgeBaseService.ensure(kbId);
        if (!knowledgeBaseService.canWrite(kb, operatorId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权向该知识库上传文档");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传文件不能为空");
        }

        StoredFile stored = fileStorageService.store(file);
        String fileHash;
        try {
            fileHash = sha256(file);
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件哈希计算失败");
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKbId(kbId);
        doc.setTitle(stored.name());
        doc.setStatus("PENDING");
        doc.setFileRef(stored.url());
        doc.setFileHash(fileHash);
        doc.setCreatedBy(operatorId);
        documentMapper.insert(doc);
        // 解析异步触发：仅在上传事务提交后（AFTER_COMMIT）才跑，确保异步线程读得到 PENDING 行
        applicationEventPublisher.publishEvent(new DocumentUploadedEvent(doc.getId(), operatorId));
        return toVO(doc);
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
}
