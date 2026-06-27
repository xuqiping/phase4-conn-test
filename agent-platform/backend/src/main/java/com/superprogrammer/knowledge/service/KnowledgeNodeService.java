package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.dto.KnowledgeNodeVO;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.knowledge.util.JiebaTokenizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识节点查询（目录树，必做收口 #4）。
 * 当前仅读：文档下节点 flat 列表（L0 摘要 + L2 原文子节点，前端按 parentId 建树）。
 * 写入（解析落库）由 {@link com.superprogrammer.knowledge.service.KnowledgeNodeWriter} 负责。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeNodeService {

    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 文档下节点列表（目录树）。canRead 门（doc 所属 KB）。
     * 排序：id 升序（parser 按 section 顺序写，L0 先于其 L2 子节点）→ 前端按 parentId 重建层级。
     * deleted 已由 @TableLogic 自动滤。
     */
    public List<KnowledgeNodeVO> listByDocument(Long docId, Long operatorId, boolean admin) {
        KnowledgeDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        if (!knowledgeBaseService.canRead(knowledgeBaseService.ensure(doc.getKbId()), operatorId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该文档");
        }
        LambdaQueryWrapper<KnowledgeNode> w = new LambdaQueryWrapper<>();
        w.eq(KnowledgeNode::getDocumentId, docId)
                .orderByAsc(KnowledgeNode::getId);
        return nodeMapper.selectList(w).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 回填 content_tokens（Phase2 V35 迁移后存量节点 content_tokens IS NULL）。
     * 对 ACTIVE 节点逐条 jieba 分词 UPDATE。bm25HitsJieba 在未回填时空命中（优雅降级），
     * smoke-kb 必须回填才能验词法兜底。返回更新条数。
     */
    public int backfillContentTokens() {
        LambdaQueryWrapper<KnowledgeNode> w = new LambdaQueryWrapper<>();
        w.isNull(KnowledgeNode::getContentTokens)
                .eq(KnowledgeNode::getStatus, "ACTIVE")
                .select(KnowledgeNode::getId, KnowledgeNode::getContent);
        List<KnowledgeNode> nodes = nodeMapper.selectList(w);
        int updated = 0;
        for (KnowledgeNode n : nodes) {
            KnowledgeNode up = new KnowledgeNode();
            up.setId(n.getId());
            up.setContentTokens(JiebaTokenizer.tokenize(n.getContent()));
            nodeMapper.updateById(up);
            updated++;
        }
        return updated;
    }

    private KnowledgeNodeVO toVO(KnowledgeNode n) {
        return KnowledgeNodeVO.builder()
                .id(n.getId())
                .parentId(n.getParentId())
                .documentId(n.getDocumentId())
                .level(n.getLevel())
                .nodeType(n.getNodeType())
                .title(n.getTitle())
                .content(n.getContent())
                .tokenCount(n.getTokenCount())
                .status(n.getStatus())
                .build();
    }
}
