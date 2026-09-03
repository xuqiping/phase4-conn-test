package com.superprogrammer.knowledge.relation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.dto.KnowledgeRelationRequest;
import com.superprogrammer.knowledge.dto.KnowledgeRelationVO;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeDocumentRelation;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentRelationMapper;
import com.superprogrammer.knowledge.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C1 文档关联边管理（规格 §3.1）。建边/删边 = canManage；列表 = canRead（成员可见关联，
 * 帮助理解「🔗 关联带出」证据来源）。检索侧消费见 RelationGraphPostProcessor（Step2）。
 *
 * <p>悬挂边：文档被逻辑删后边不级联删（避免删除文档的治理动作悄悄改检索行为图）；
 * 本服务 listByDoc 过滤悬挂端，检索侧 step6.5 按文档存在性 JOIN 自然过滤。</p>
 */
@Service
@RequiredArgsConstructor
public class DocumentRelationService {

    private static final Long TENANT_ID = 1L;

    private final KnowledgeDocumentRelationMapper relationMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseService knowledgeBaseService;

    /** 建边。挡：无 canManage / 非法类型 / 自环 / 跨库 / 文档不存在 / 同向重复 / 语义等价反向重复。 */
    public KnowledgeRelationVO create(KnowledgeRelationRequest req, Long userId, boolean admin) {
        KnowledgeBase kb = knowledgeBaseService.ensure(req.getKbId());
        if (!knowledgeBaseService.canManage(kb, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理该知识库的文档关联");
        }
        String type = normalizeType(req.getRelationType());
        if (req.getDocId().equals(req.getRelatedDocId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能与文档自身建立关联");
        }
        KnowledgeDocument doc = requireDocInKb(req.getDocId(), req.getKbId());
        KnowledgeDocument related = requireDocInKb(req.getRelatedDocId(), req.getKbId());

        if (countEdges(req.getKbId(), req.getDocId(), req.getRelatedDocId(), type) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "关联已存在");
        }
        // 语义等价去重：MUST_CITE(B→A) 与 MUST_BE_CITED(A→B) 含义相同，唯一约束拦不住方向颠倒，服务层拦
        String reverseEquivalent = KnowledgeDocumentRelation.equivalentReverseType(type);
        if (countEdges(req.getKbId(), req.getRelatedDocId(), req.getDocId(), reverseEquivalent) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "等价关联已存在（方向相反的「" + reverseEquivalent + "」边），请勿重复建立");
        }

        KnowledgeDocumentRelation edge = new KnowledgeDocumentRelation();
        edge.setTenantId(TENANT_ID);
        edge.setKbId(req.getKbId());
        edge.setDocId(req.getDocId());
        edge.setRelatedDocId(req.getRelatedDocId());
        edge.setRelationType(type);
        edge.setNote(req.getNote());
        edge.setCreatedBy(userId);
        relationMapper.insert(edge);
        return toVo(edge, "OUT", related.getTitle());
    }

    /** 删边（硬删，对齐 knowledge_permissions 撤销惯例）。仅 canManage。 */
    public void delete(Long id, Long userId, boolean admin) {
        KnowledgeDocumentRelation edge = relationMapper.selectById(id);
        if (edge == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "关联不存在或已删除");
        }
        KnowledgeBase kb = knowledgeBaseService.ensure(edge.getKbId());
        if (!knowledgeBaseService.canManage(kb, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理该知识库的文档关联");
        }
        relationMapper.deleteById(id);
    }

    /**
     * 单文档视角的边列表（出边 + 入边）。入边 relationType 保持原始存储值，
     * direction=IN 提示前端按反向语义展示（如入边 MUST_BE_CITED = 「对方随本档出场」）。
     */
    public List<KnowledgeRelationVO> listByDoc(Long kbId, Long docId, Long userId, boolean admin) {
        KnowledgeBase kb = knowledgeBaseService.ensure(kbId);
        if (!knowledgeBaseService.canRead(kb, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该知识库");
        }
        requireDocInKb(docId, kbId);

        List<KnowledgeDocumentRelation> outEdges = relationMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentRelation>()
                        .eq(KnowledgeDocumentRelation::getKbId, kbId)
                        .eq(KnowledgeDocumentRelation::getDocId, docId)
                        .orderByDesc(KnowledgeDocumentRelation::getCreatedAt));
        List<KnowledgeDocumentRelation> inEdges = relationMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentRelation>()
                        .eq(KnowledgeDocumentRelation::getKbId, kbId)
                        .eq(KnowledgeDocumentRelation::getRelatedDocId, docId)
                        .orderByDesc(KnowledgeDocumentRelation::getCreatedAt));

        // 批量取另一端标题（一次查询，防 N+1）；查不到 = 另一端已删 → 悬挂边过滤不返回
        Set<Long> otherIds = new LinkedHashSet<>();
        outEdges.forEach(e -> otherIds.add(e.getRelatedDocId()));
        inEdges.forEach(e -> otherIds.add(e.getDocId()));
        Map<Long, String> titles = new HashMap<>();
        if (!otherIds.isEmpty()) {
            documentMapper.selectBatchIds(otherIds)
                    .forEach(d -> titles.put(d.getId(), d.getTitle()));
        }

        List<KnowledgeRelationVO> result = new ArrayList<>(outEdges.size() + inEdges.size());
        for (KnowledgeDocumentRelation e : outEdges) {
            String title = titles.get(e.getRelatedDocId());
            if (title != null) {
                result.add(toVo(e, "OUT", title));
            }
        }
        for (KnowledgeDocumentRelation e : inEdges) {
            String title = titles.get(e.getDocId());
            if (title != null) {
                result.add(toVo(e, "IN", title));
            }
        }
        return result;
    }

    private long countEdges(Long kbId, Long docId, Long relatedDocId, String type) {
        return relationMapper.selectCount(new LambdaQueryWrapper<KnowledgeDocumentRelation>()
                .eq(KnowledgeDocumentRelation::getKbId, kbId)
                .eq(KnowledgeDocumentRelation::getDocId, docId)
                .eq(KnowledgeDocumentRelation::getRelatedDocId, relatedDocId)
                .eq(KnowledgeDocumentRelation::getRelationType, type));
    }

    private KnowledgeDocument requireDocInKb(Long docId, Long kbId) {
        KnowledgeDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在：id=" + docId);
        }
        if (!kbId.equals(doc.getKbId())) {
            // 关联仅限同库（首版边界）：跨库边涉及跨库权限矩阵与保密库穿透
            throw new BusinessException(ErrorCode.BAD_REQUEST, "关联仅限同一知识库内的文档");
        }
        return doc;
    }

    private static String normalizeType(String type) {
        if (type == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "relationType 不能为空");
        }
        String t = type.trim();
        switch (t) {
            case KnowledgeDocumentRelation.TYPE_MUST_CITE:
            case KnowledgeDocumentRelation.TYPE_MAY_CITE:
            case KnowledgeDocumentRelation.TYPE_MUST_BE_CITED:
            case KnowledgeDocumentRelation.TYPE_MAY_BE_CITED:
                return t;
            default:
                throw new BusinessException(ErrorCode.BAD_REQUEST, "未知关系类型: " + type);
        }
    }

    private static KnowledgeRelationVO toVo(KnowledgeDocumentRelation e, String direction, String otherTitle) {
        return KnowledgeRelationVO.builder()
                .id(e.getId())
                .kbId(e.getKbId())
                .direction(direction)
                .relationType(e.getRelationType())
                .otherDocId("OUT".equals(direction) ? e.getRelatedDocId() : e.getDocId())
                .otherDocTitle(otherTitle)
                .note(e.getNote())
                .createdBy(e.getCreatedBy())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
