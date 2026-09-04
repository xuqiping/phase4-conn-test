package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentVersionVO;
import com.superprogrammer.knowledge.event.DocumentUploadedEvent;
import com.superprogrammer.knowledge.event.VisibilityInvalidationEvent;
import com.superprogrammer.knowledge.mapper.KnowledgeDocEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentVersionMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeImageEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** Canonical Document 版本状态机；版本内容不可变，只通过指针和状态完成生效、替代与撤销。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentVersionService {
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ApplicationEventPublisher eventPublisher;
    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeEmbeddingMapper embeddingMapper;
    private final KnowledgeDocEmbeddingMapper docEmbeddingMapper;
    private final KnowledgeImageEmbeddingMapper imageEmbeddingMapper;

    @Transactional
    public KnowledgeDocumentVersion createInitialVersion(Long documentId, String fileRef, String sourceHash,
                                                          Long operatorId, String changeNote) {
        KnowledgeDocument doc = lockDocument(documentId);
        if (doc.getCurrentVersionId() != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "文档已存在初始版本");
        }
        KnowledgeDocumentVersion version = build(documentId, 1, null, fileRef, sourceHash,
                changeNote, "EFFECTIVE", operatorId);
        versionMapper.insert(version);
        if (documentMapper.moveCurrentVersion(documentId, version.getId(), null, operatorId) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "文档版本冲突，请刷新后重试");
        }
        return version;
    }

    @Transactional
    public KnowledgeDocumentVersion createDraftVersion(Long documentId, Long parentVersionId,
                                                        String fileRef, String sourceHash, String changeNote,
                                                        Long operatorId, boolean admin) {
        KnowledgeDocument doc = lockAndManage(documentId, operatorId, admin);
        if (!Objects.equals(doc.getCurrentVersionId(), parentVersionId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "文档版本冲突，请刷新后重试");
        }
        int next = versionMapper.nextVersionNo(documentId);
        KnowledgeDocumentVersion version = build(documentId, next, parentVersionId, fileRef, sourceHash,
                changeNote, "DRAFT", operatorId);
        versionMapper.insert(version);
        return version;
    }

    @Transactional
    public void activate(Long documentId, Long versionId, Long expectedCurrentVersionId,
                         Long operatorId, boolean admin) {
        KnowledgeDocument doc = lockAndManage(documentId, operatorId, admin);
        if (!Objects.equals(doc.getCurrentVersionId(), expectedCurrentVersionId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "文档版本冲突，请刷新后重试");
        }
        KnowledgeDocumentVersion target = versionMapper.selectById(versionId);
        if (target == null || !Objects.equals(target.getDocumentId(), documentId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档版本不存在");
        }
        versionMapper.archiveEffective(documentId, versionId);
        if (versionMapper.markEffective(versionId, operatorId) != 1
                || documentMapper.moveCurrentVersion(documentId, versionId, expectedCurrentVersionId, operatorId) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "文档版本冲突，请刷新后重试");
        }
        // Phase4 实测修复（Bug #5）：生效只移指针不动内容——doc.fileRef 永远停在首版，
        // 旧节点/向量继续被召回，新版本字节从未入索引，且卡壳状态（如重启残留 EMBEDDING）
        // 永不恢复。补齐「生效即换内容」：切 fileRef/hash → 置 PENDING → 清旧节点+三池向量
        // （writeNodes 不清旧，复用解析管线前必须先清，与 ConnectorSyncTxService.resetDocForResync
        // 同构）→ 事务内发 DocumentUploadedEvent（AFTER_COMMIT 触发完整解析管线重跑）。
        documentMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, documentId)
                .set(KnowledgeDocument::getFileRef, target.getFileRef())
                .set(KnowledgeDocument::getFileHash, target.getSourceHash())
                .set(KnowledgeDocument::getStatus, "PENDING")
                .set(KnowledgeDocument::getParseError, null));
        nodeMapper.delete(new LambdaQueryWrapper<KnowledgeNode>()
                .eq(KnowledgeNode::getDocumentId, documentId));
        embeddingMapper.deleteByDocument(documentId);
        docEmbeddingMapper.deleteByDocument(documentId);
        imageEmbeddingMapper.deleteByDocument(documentId);
        eventPublisher.publishEvent(new DocumentUploadedEvent(documentId, operatorId));
        eventPublisher.publishEvent(new VisibilityInvalidationEvent(doc.getKbId()));
        log.info("版本生效 → 重解析 docId={} versionId={} versionNo={}",
                documentId, versionId, target.getVersionNo());
    }

    @Transactional
    public void revoke(Long documentId, Long versionId, Long operatorId, boolean admin) {
        KnowledgeDocument doc = lockAndManage(documentId, operatorId, admin);
        KnowledgeDocumentVersion target = versionMapper.selectById(versionId);
        if (target == null || !Objects.equals(target.getDocumentId(), documentId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档版本不存在");
        }
        if (versionMapper.revoke(versionId, operatorId) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "文档版本已被其他操作修改");
        }
        if (Objects.equals(doc.getCurrentVersionId(), versionId)
                && documentMapper.moveCurrentVersion(documentId, null, versionId, operatorId) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "文档版本冲突，请刷新后重试");
        }
        eventPublisher.publishEvent(new VisibilityInvalidationEvent(doc.getKbId()));
    }

    public List<KnowledgeDocumentVersion> listHistory(Long documentId, Long operatorId, boolean admin) {
        KnowledgeDocument doc = documentMapper.selectById(documentId);
        if (doc == null) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        KnowledgeBase kb = knowledgeBaseService.ensure(doc.getKbId());
        if (!knowledgeBaseService.canRead(kb, operatorId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该文档历史版本");
        }
        return versionMapper.listByDocument(documentId);
    }

    public List<KnowledgeDocumentVersionVO> listHistoryVO(Long documentId, Long operatorId, boolean admin) {
        return listHistory(documentId, operatorId, admin).stream().map(this::toVO).toList();
    }

    public KnowledgeDocumentVersionVO toVO(KnowledgeDocumentVersion version) {
        return KnowledgeDocumentVersionVO.builder()
                .id(version.getId()).documentId(version.getDocumentId()).versionNo(version.getVersionNo())
                .parentVersionId(version.getParentVersionId()).sourceHash(version.getSourceHash())
                .fileRef(version.getFileRef()).changeNote(version.getChangeNote()).status(version.getStatus())
                .effectiveAt(version.getEffectiveAt()).revokedAt(version.getRevokedAt())
                .replacedByVersionId(version.getReplacedByVersionId()).createdAt(version.getCreatedAt()).build();
    }

    private KnowledgeDocument lockAndManage(Long documentId, Long operatorId, boolean admin) {
        KnowledgeDocument doc = lockDocument(documentId);
        KnowledgeBase kb = knowledgeBaseService.ensure(doc.getKbId());
        if (!knowledgeBaseService.canManage(kb, operatorId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理该文档版本");
        }
        return doc;
    }

    private KnowledgeDocument lockDocument(Long documentId) {
        KnowledgeDocument doc = documentMapper.selectByIdForUpdate(documentId);
        if (doc == null) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        return doc;
    }

    private KnowledgeDocumentVersion build(Long documentId, int versionNo, Long parentVersionId,
                                            String fileRef, String sourceHash, String changeNote,
                                            String status, Long operatorId) {
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setDocumentId(documentId); version.setVersionNo(versionNo);
        version.setParentVersionId(parentVersionId); version.setFileRef(fileRef);
        version.setSourceHash(sourceHash); version.setContentHash(sourceHash);
        version.setChangeNote(changeNote); version.setStatus(status);
        version.setCreatedBy(operatorId); version.setCreatedAt(OffsetDateTime.now());
        if ("EFFECTIVE".equals(status)) version.setEffectiveAt(OffsetDateTime.now());
        return version;
    }
}
