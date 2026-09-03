package com.superprogrammer.knowledge.relation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.dto.KnowledgeRelationRequest;
import com.superprogrammer.knowledge.dto.KnowledgeRelationSuggestionVO;
import com.superprogrammer.knowledge.dto.RelationSuggestionAdoptRequest;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeDocumentRelationSuggestion;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentRelationSuggestionMapper;
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
 * C1 关联建议的人工裁决入口（规格 §3.3）：列表/采纳/忽略，仅 canManage。
 * 采纳不直接写边表——委托 {@link DocumentRelationService#create} 复用六路校验
 * （权限/类型/自环/同库/存在性/同向+语义等价去重），建议流不能绕过建边不变式。
 *
 * <p>建议行不物理删：ADOPTED/IGNORED 状态位占住 uq_kdrs(kb,a,b)，
 * worker 据此不重提（用户已裁决的对不再打扰）。</p>
 */
@Service
@RequiredArgsConstructor
public class RelationSuggestionService {

    private final KnowledgeDocumentRelationSuggestionMapper suggestionMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentRelationService documentRelationService;

    /** 某库 PENDING 建议（共召回次数降序）。悬挂建议（任一端已删）过滤不返回。 */
    public List<KnowledgeRelationSuggestionVO> listByKb(Long kbId, Long userId, boolean admin) {
        KnowledgeBase kb = knowledgeBaseService.ensure(kbId);
        if (!knowledgeBaseService.canManage(kb, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理该知识库的关联建议");
        }
        List<KnowledgeDocumentRelationSuggestion> rows = suggestionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentRelationSuggestion>()
                        .eq(KnowledgeDocumentRelationSuggestion::getKbId, kbId)
                        .eq(KnowledgeDocumentRelationSuggestion::getStatus,
                                KnowledgeDocumentRelationSuggestion.STATUS_PENDING)
                        .orderByDesc(KnowledgeDocumentRelationSuggestion::getCoRecallCount)
                        .orderByDesc(KnowledgeDocumentRelationSuggestion::getLastSeenAt));
        if (rows.isEmpty()) {
            return List.of();
        }

        // 批量标题（一次查询防 N+1）；任一端查不到 = 文档已删 → 悬挂建议过滤
        Set<Long> docIds = new LinkedHashSet<>();
        rows.forEach(r -> {
            docIds.add(r.getDocIdA());
            docIds.add(r.getDocIdB());
        });
        Map<Long, String> titles = new HashMap<>();
        documentMapper.selectBatchIds(docIds).forEach(d -> titles.put(d.getId(), d.getTitle()));

        List<KnowledgeRelationSuggestionVO> result = new ArrayList<>(rows.size());
        for (KnowledgeDocumentRelationSuggestion r : rows) {
            String titleA = titles.get(r.getDocIdA());
            String titleB = titles.get(r.getDocIdB());
            if (titleA == null || titleB == null) {
                continue;
            }
            result.add(KnowledgeRelationSuggestionVO.builder()
                    .id(r.getId())
                    .kbId(r.getKbId())
                    .docIdA(r.getDocIdA())
                    .docIdB(r.getDocIdB())
                    .docTitleA(titleA)
                    .docTitleB(titleB)
                    .coRecallCount(r.getCoRecallCount())
                    .sampleQueryHash(r.getSampleQueryHash())
                    .status(r.getStatus())
                    .lastSeenAt(r.getLastSeenAt())
                    .createdAt(r.getCreatedAt())
                    .build());
        }
        return result;
    }

    /**
     * 采纳：按用户指定的方向/类型建边（复用建边全校验），建议置 ADOPTED。
     * 边已存在（建议生成后用户手动建过）= 采纳目的已达成，同样置 ADOPTED 不报错。
     */
    public void adopt(Long suggestionId, RelationSuggestionAdoptRequest req, Long userId, boolean admin) {
        KnowledgeDocumentRelationSuggestion s = requirePending(suggestionId);
        KnowledgeBase kb = knowledgeBaseService.ensure(s.getKbId());
        if (!knowledgeBaseService.canManage(kb, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理该知识库的关联建议");
        }

        Long fromDocId = req.getFromDocId() != null ? req.getFromDocId() : s.getDocIdA();
        Long toDocId;
        if (fromDocId.equals(s.getDocIdA())) {
            toDocId = s.getDocIdB();
        } else if (fromDocId.equals(s.getDocIdB())) {
            toDocId = s.getDocIdA();
        } else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "fromDocId 必须是建议两端的文档 id");
        }

        KnowledgeRelationRequest edgeReq = new KnowledgeRelationRequest();
        edgeReq.setKbId(s.getKbId());
        edgeReq.setDocId(fromDocId);
        edgeReq.setRelatedDocId(toDocId);
        edgeReq.setRelationType(req.getRelationType());
        edgeReq.setNote(req.getNote());
        try {
            documentRelationService.create(edgeReq, userId, admin);
        } catch (BusinessException e) {
            // 建边被「已存在」（同向或语义等价反向）挡 = 目标态已达成，按采纳成功收口
            if (e.getCode() == ErrorCode.BAD_REQUEST.getCode()
                    && e.getMessage() != null && e.getMessage().contains("已存在")) {
                mark(s, KnowledgeDocumentRelationSuggestion.STATUS_ADOPTED);
                return;
            }
            throw e;
        }
        mark(s, KnowledgeDocumentRelationSuggestion.STATUS_ADOPTED);
    }

    /** 忽略：置 IGNORED，不建边；worker 不再重提该对。 */
    public void ignore(Long suggestionId, Long userId, boolean admin) {
        KnowledgeDocumentRelationSuggestion s = requirePending(suggestionId);
        KnowledgeBase kb = knowledgeBaseService.ensure(s.getKbId());
        if (!knowledgeBaseService.canManage(kb, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权管理该知识库的关联建议");
        }
        mark(s, KnowledgeDocumentRelationSuggestion.STATUS_IGNORED);
    }

    private KnowledgeDocumentRelationSuggestion requirePending(Long id) {
        KnowledgeDocumentRelationSuggestion s = suggestionMapper.selectById(id);
        if (s == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "建议不存在");
        }
        if (!KnowledgeDocumentRelationSuggestion.STATUS_PENDING.equals(s.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "建议已处理（" + s.getStatus() + "），无需重复操作");
        }
        return s;
    }

    private void mark(KnowledgeDocumentRelationSuggestion s, String status) {
        s.setStatus(status);
        suggestionMapper.updateById(s);
    }
}
