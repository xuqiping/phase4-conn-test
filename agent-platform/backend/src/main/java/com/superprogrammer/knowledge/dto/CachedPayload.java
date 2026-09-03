package com.superprogrammer.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 答案缓存命中 payload（存 {@code rag_answer_cache.answer} 列 JSON）。
 *
 * <p>两入口复用同一载体：
 * <ul>
 *   <li>{@code retrieve()} 填 {@link #answer}（完整生成答案）；{@link #systemPrompt} 留空。</li>
 *   <li>{@code retrieveEvidence()} 填 {@link #systemPrompt}（证据上下文块）；{@link #answer} 留空。</li>
 * </ul>
 * {@link #citations} / {@link #injectedIndexes} 两入口都填（命中时直接回放，无需重载 evidence 内容）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CachedPayload {

    /** retrieve() 生成的完整答案（含 inline [n] 引用）；retrieveEvidence() 留空。 */
    private String answer;

    /** retrieveEvidence() 的证据上下文 SYSTEM prompt；retrieve() 留空。 */
    private String systemPrompt;

    /** 引用列表（与 RagRetrieveVO.CitationVO 同构，命中时回放）。 */
    private List<CitationRef> citations;

    /** 注入证据的编号集合（post-gen CitationChecker 校验用）。 */
    private List<Integer> injectedIndexes;

    /** 缓存命中 payload 内嵌的引用项（{index, documentId, title, nodeId}，与 CitationVO 同构）。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CitationRef {
        private Integer index;
        private Long documentId;
        private String title;
        private Long nodeId;
        /** IMAGE/FILE 回显（缓存命中回放时保留，P3 聊天 [n] 渲染用）。 */
        private String docType;
        private String fileRef;
        private String mime;
        private String originalName;
        /** 14x#3：引用来自保密库且请求者非 owner/admin（前端隐藏下载入口；旧缓存条目缺字段反序列化=false）。 */
        private boolean confidential;
        /** C2：附件型文档（📎 徽标；旧缓存条目缺字段反序列化=false）。 */
        private boolean attachment;
    }
}
