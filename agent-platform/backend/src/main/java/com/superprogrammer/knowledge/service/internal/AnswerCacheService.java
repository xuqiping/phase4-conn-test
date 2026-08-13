package com.superprogrammer.knowledge.service.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.config.AnswerCacheProperties;
import com.superprogrammer.knowledge.dto.CacheCandidateRow;
import com.superprogrammer.knowledge.dto.CachedPayload;
import com.superprogrammer.knowledge.dto.RagQueryRow;
import com.superprogrammer.knowledge.entity.RagAnswerCache;
import com.superprogrammer.knowledge.mapper.RagAnswerCacheMapper;
import com.superprogrammer.knowledge.mapper.RagRetrievalQueryMapper;
import com.superprogrammer.knowledge.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 跨会话语义答案缓存（v6 §8.9a，阶段4-B）。
 *
 * <p>per-user + 懒失效：无主动 purge（{@code VisibilityInvalidationEvent} 不挂钩），
 * 全靠 P2/P3 校验链在命中时把关：
 * <ul>
 *   <li><b>P3 permission_signature</b> = sha256(canonical(visible_set + kb_scope))。
 *       grant/revoke 改可见集 → 签名变 → 旧缓存签名不匹配 → 自动 miss。</li>
 *   <li><b>P2a evidence hash 复校</b>：命中候选的 provenance node 现值 content_hash
 *       逐一比对该缓存 evidence_hashes；doc 删/重传（{@code KnowledgeDocumentService} 无 update，仅 delete+reupload）
 *       改 node content_hash 或节点 CASCADE 删除 → 复校失败 → miss。</li>
 * </ul>
 * <b>P2b（doc_id⊆visible_set）冗余</b>：P3 签名对完整可见集 doc-id 集做无损 sha256，
 * 签名匹配即可见集 byte-identical → 缓存时可见的 evidence doc 必仍可见，故 P2b 由 P3 蕴含，不单列。
 *
 * <p>缓存写失败 / parse 失败均不阻断检索（catch + warn，缓存为优化非正确性依赖）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerCacheService {

    private static final Long TENANT_ID = 1L;
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final RagAnswerCacheMapper answerCacheMapper;
    private final RagRetrievalQueryMapper queryMapper;
    private final AnswerCacheProperties props;
    private final ObjectMapper objectMapper;

    /** 单 KB 可见集快照（供签名 + 与 retrieve/retrieveEvidence 的 VisibleSet 桥接）。 */
    public record KbScope(Long kbId, boolean allDocs, List<Long> docIds) {
    }

    /** 一次缓存读写必须携带的完整、不可变协议。 */
    public record CacheProtocol(String embeddingModel, String rankingConfigVersion,
                                String pipelineVersion, String promptVersion,
                                String knowledgeSnapshot) {
    }

    public CacheProtocol protocol(List<Long> kbIds, String embeddingModel, String rankingConfigVersion) {
        List<Long> normalizedKbIds = kbIds == null ? List.of() : kbIds.stream().sorted().toList();
        String snapshot = normalizedKbIds.isEmpty() ? HashUtil.sha256("")
                : queryMapper.computeKnowledgeSnapshot(normalizedKbIds);
        return new CacheProtocol(required(embeddingModel), required(rankingConfigVersion),
                required(props.getPipelineVersion()), required(props.getPromptVersion()), required(snapshot));
    }

    // ============================ 签名 ============================

    /**
     * P3：permission_signature = sha256(canonical(per-kb visible) )。
     * canonical：按 kbId 升序，每 kb 为 "{kbId}:ALL" 或 "{kbId}:{sorted docIds 逗号}"，竖线分隔。
     * 对完整可见集无损 → 签名匹配即可见集完全相同（P2b 蕴含）。
     */
    public String permissionSignature(List<KbScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return HashUtil.sha256("");
        }
        StringBuilder sb = new StringBuilder();
        scopes.stream()
                .sorted(Comparator.comparing(KbScope::kbId))
                .forEach(s -> {
                    sb.append(s.kbId()).append(':');
                    if (s.allDocs()) {
                        sb.append("ALL");
                    } else if (s.docIds() == null || s.docIds().isEmpty()) {
                        sb.append("EMPTY");
                    } else {
                        sb.append(s.docIds().stream().sorted().map(String::valueOf)
                                .collect(Collectors.joining(",")));
                    }
                    sb.append('|');
                });
        return HashUtil.sha256(sb.toString());
    }

    // ============================ 查 + 验 ============================

    /**
     * step2 命中查找：HNSW 近邻（per-user）→ 按 sim 降序逐个 P3/P2a 验，首个通过即返回 payload。
     *
     * @param qHalf  query halfvec 字面量（B4：调用方已 embed，复用，不重嵌）
     * @param userId scope_user_id（per-user 强制）
     * @param sig    调用方按当前可见集算的 permission_signature（P3 比对）
     * @return 命中 → CachedPayload（反序列化自 answer 列）；miss → empty
     */
    public Optional<CachedPayload> lookup(String qHalf, Long userId, String sig, CacheProtocol protocol) {
        if (!props.isEnabled()) {
            return Optional.empty();
        }
        try {
            List<CacheCandidateRow> candidates = answerCacheMapper.searchCandidates(
                    userId, qHalf, protocol.embeddingModel(), protocol.rankingConfigVersion(),
                    protocol.pipelineVersion(), protocol.promptVersion(), protocol.knowledgeSnapshot(), props.getTopN());
            for (CacheCandidateRow c : candidates) {   // distance 升序 = sim 降序
                double distance = c.getCosineDistance() == null ? 2.0 : c.getCosineDistance();
                double sim = 1.0 - distance;
                if (sim < props.getSimThreshold()) {
                    break;   // 已按 distance 升序，后续更差，无需再验
                }
                // P3：权限签名必须 byte-identical（可见集未变）
                if (c.getPermissionSignature() == null || !c.getPermissionSignature().equals(sig)) {
                    continue;
                }
                // P2a：evidence node content_hash 逐一复校（doc 删/重传 → hash 变/null → miss）
                List<Long> nodeIds = parseLongList(c.getProvenanceNodeIds());
                List<String> hashes = parseStrList(c.getEvidenceHashes());
                if (nodeIds.isEmpty() || nodeIds.size() != hashes.size()) {
                    continue;
                }
                if (!verifyNodeHashes(nodeIds, hashes)) {
                    continue;   // 证据漂移，该候选作废，继续下一个近邻
                }
                // 命中
                answerCacheMapper.bumpUsage(c.getId());
                CachedPayload payload = parsePayload(c.getAnswer());
                if (payload == null) {
                    continue;
                }
                return Optional.of(payload);
            }
        } catch (Exception e) {
            log.warn("answer_cache 查找失败（降级 miss，不影响检索）: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /** P2a：每个 node 现值 content_hash == 缓存 hash（任一失配/缺失 → false）。 */
    private boolean verifyNodeHashes(List<Long> nodeIds, List<String> hashes) {
        for (int i = 0; i < nodeIds.size(); i++) {
            RagQueryRow.HashVerifyRow hv = queryMapper.reverifyNode(nodeIds.get(i));
            if (hv == null || hv.getNodeHash() == null || !hv.getNodeHash().equals(hashes.get(i))) {
                return false;
            }
        }
        return true;
    }

    // ============================ 写 ============================

    /**
     * 缓存写入（仅 SUPPORTED 调；A2 abstain 不写）。gate 关 → no-op。
     * 写失败不抛（缓存为优化，绝不阻断检索主链路）。
     *
     * @param query      原始 query（query_canonical，调试/清理用）
     * @param qHalf      query halfvec 字面量（key_embedding）
     * @param userId     scope_user_id
     * @param kbIds      本次检索 KB 集
     * @param sig        permission_signature（与 lookup 同算法）
     * @param payload    CachedPayload（answer 或 systemPrompt + citations + injectedIndexes）
     * @param nodeIds    provenance_node_ids（P2a 锚点，注入证据的 nodeId）
     * @param hashes     evidence_hashes（与 nodeIds 平行，P2a 比对值 = node 现值 content_hash）
     * @param confidence 命中相似度（观测）
     * @param embedModel key_embedding_model
     */
    public void store(String query, String qHalf, Long userId, List<Long> kbIds, String sig,
                      CachedPayload payload, List<Long> nodeIds, List<String> hashes,
                      double confidence, CacheProtocol protocol) {
        if (!props.isEnabled() || nodeIds == null || nodeIds.isEmpty() || payload == null) {
            return;
        }
        try {
            RagAnswerCache c = new RagAnswerCache();
            c.setTenantId(TENANT_ID);
            c.setScopeUserId(userId);
            c.setKbIds(objectMapper.writeValueAsString(kbIds == null ? List.of() : kbIds));
            c.setQueryCanonical(query);
            c.setKeyEmbeddingModel(protocol.embeddingModel());
            c.setRankingConfigVersion(protocol.rankingConfigVersion());
            c.setPipelineVersion(protocol.pipelineVersion());
            c.setPromptVersion(protocol.promptVersion());
            c.setKnowledgeSnapshot(protocol.knowledgeSnapshot());
            c.setAnswer(objectMapper.writeValueAsString(payload));
            c.setProvenanceNodeIds(objectMapper.writeValueAsString(nodeIds));
            c.setEvidenceHashes(objectMapper.writeValueAsString(hashes));
            c.setPermissionSignature(sig);
            c.setConfidence((float) confidence);
            c.setUsageCount(0);
            c.setDecayAt(OffsetDateTime.now().plusDays(props.getTtlDays()));
            c.setStatus(STATUS_ACTIVE);
            answerCacheMapper.insert(c, qHalf);
        } catch (Exception e) {
            log.warn("写 rag_answer_cache 失败（不影响检索结果）: {}", e.getMessage());
        }
    }

    // ============================ 解析小工具 ============================

    private CachedPayload parsePayload(String answerJson) {
        if (answerJson == null || answerJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(answerJson, CachedPayload.class);
        } catch (Exception e) {
            log.warn("CachedPayload 反序列化失败（跳过该候选）: {}", e.getMessage());
            return null;
        }
    }

    private List<Long> parseLongList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseStrList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("答案缓存版本协议字段不能为空");
        }
        return value.trim();
    }
}
