package com.superprogrammer.knowledge.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.config.AnswerCacheProperties;
import com.superprogrammer.knowledge.dto.CacheCandidateRow;
import com.superprogrammer.knowledge.dto.CachedPayload;
import com.superprogrammer.knowledge.dto.RagQueryRow;
import com.superprogrammer.knowledge.mapper.RagAnswerCacheMapper;
import com.superprogrammer.knowledge.mapper.RagRetrievalQueryMapper;
import com.superprogrammer.knowledge.util.HashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AnswerCacheService P3/P2a/lookup/store 逻辑测（缓存非权威，失败不阻断检索）。
 * mapper mock，ObjectMapper 真实（验 JSON 序列化/反序列化契约）。
 */
@ExtendWith(MockitoExtension.class)
class AnswerCacheServiceTest {

    @Mock private RagAnswerCacheMapper answerCacheMapper;
    @Mock private RagRetrievalQueryMapper queryMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AnswerCacheService service;
    private AnswerCacheProperties props;

    @BeforeEach
    void setUp() {
        props = new AnswerCacheProperties();
        service = new AnswerCacheService(answerCacheMapper, queryMapper, props, objectMapper);
    }

    // ============================ permissionSignature（P3）============================

    @Test
    void sig_emptyScopes_isHashOfEmpty() {
        assertEquals(HashUtil.sha256(""), service.permissionSignature(List.of()));
        assertEquals(HashUtil.sha256(""), service.permissionSignature(null));
    }

    @Test
    void sig_singleAllScope() {
        String sig = service.permissionSignature(List.of(new AnswerCacheService.KbScope(1L, true, List.of())));
        assertEquals(HashUtil.sha256("1:ALL|"), sig);
    }

    @Test
    void sig_emptyDocScope() {
        String sig = service.permissionSignature(List.of(
                new AnswerCacheService.KbScope(1L, false, List.of())));
        assertEquals(HashUtil.sha256("1:EMPTY|"), sig);
    }

    @Test
    void sig_docIdsSorted() {
        // 给乱序 docIds → canonical 内升序
        String sig = service.permissionSignature(List.of(
                new AnswerCacheService.KbScope(1L, false, List.of(3L, 1L, 2L))));
        assertEquals(HashUtil.sha256("1:1,2,3|"), sig);
    }

    @Test
    void sig_kbIdsSortedAcrossScopes() {
        // 给乱序 kb → canonical 按 kbId 升序
        String sig = service.permissionSignature(List.of(
                new AnswerCacheService.KbScope(2L, true, List.of()),
                new AnswerCacheService.KbScope(1L, false, List.of(5L))));
        assertEquals(HashUtil.sha256("1:5|2:ALL|"), sig);
    }

    @Test
    void sig_orderInsensitive_toInputs() {
        // 同一可见集不同输入顺序 → 同签名（canonical 归一）
        var s1 = service.permissionSignature(List.of(
                new AnswerCacheService.KbScope(2L, true, List.of()),
                new AnswerCacheService.KbScope(1L, true, List.of())));
        var s2 = service.permissionSignature(List.of(
                new AnswerCacheService.KbScope(1L, true, List.of()),
                new AnswerCacheService.KbScope(2L, true, List.of())));
        assertEquals(s1, s2);
    }

    // ============================ lookup ============================

    @Test
    void lookup_disabled_returnsEmpty() {
        props.setEnabled(false);
        Optional<CachedPayload> r = service.lookup("[0.1]", 1L, "sig", protocol());
        assertTrue(r.isEmpty());
        verify(answerCacheMapper, never()).searchCandidates(anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void lookup_simBelowThreshold_breaksNoVerify() {
        props.setEnabled(true);
        CacheCandidateRow c = candidate(0.5, "sig", "[1]", "[\"h\"]", null);  // distance 0.5 → sim 0.5 < 0.9 阈
        stubCandidates(c);

        Optional<CachedPayload> r = service.lookup("[0.1]", 1L, "sig", protocol());

        assertTrue(r.isEmpty());
        verify(queryMapper, never()).reverifyNode(anyLong());   // sim 不足直接 break，不验 P2a
    }

    @Test
    void lookup_permissionSigMismatch_continues() {
        props.setEnabled(true);
        CacheCandidateRow c = candidate(0.05, "DIFFERENT_SIG", "[1]", "[\"h\"]", null);  // sim 0.95 够阈，但签名不匹配
        stubCandidates(c);

        assertTrue(service.lookup("[0.1]", 1L, "EXPECTED_SIG", protocol()).isEmpty());
        verify(queryMapper, never()).reverifyNode(anyLong());   // P3 失败 continue，不进 P2a
    }

    @Test
    void lookup_nodeIdsEmpty_continues() {
        props.setEnabled(true);
        CacheCandidateRow c = candidate(0.05, "SIG", "[]", "[]", null);
        stubCandidates(c);

        assertTrue(service.lookup("[0.1]", 1L, "SIG", protocol()).isEmpty());
        verify(answerCacheMapper, never()).bumpUsage(anyLong());
    }

    @Test
    void lookup_p2aHashMismatch_continues() {
        props.setEnabled(true);
        CacheCandidateRow c = candidate(0.05, "SIG", "[1]", "[\"stale\"]", null);
        stubCandidates(c);
        when(queryMapper.reverifyNode(1L)).thenReturn(hashRow("current"));  // 现值与缓存 hash 不符

        assertTrue(service.lookup("[0.1]", 1L, "SIG", protocol()).isEmpty());
        verify(answerCacheMapper, never()).bumpUsage(anyLong());
    }

    @Test
    void lookup_hit_bumpsUsageAndReturnsPayload() throws Exception {
        props.setEnabled(true);
        CachedPayload payload = new CachedPayload();
        payload.setAnswer("cached answer");
        payload.setInjectedIndexes(List.of(1));
        CacheCandidateRow c = candidate(0.05, "SIG", "[1]", "[\"h\"]", objectMapper.writeValueAsString(payload));
        stubCandidates(c);
        when(queryMapper.reverifyNode(1L)).thenReturn(hashRow("h"));

        Optional<CachedPayload> r = service.lookup("[0.1]", 1L, "SIG", protocol());

        assertTrue(r.isPresent());
        assertEquals("cached answer", r.get().getAnswer());
        verify(answerCacheMapper).bumpUsage(c.getId());
    }

    @Test
    void lookup_exceptionSwallowed_returnsEmpty() {
        props.setEnabled(true);
        when(answerCacheMapper.searchCandidates(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("redis down"));

        assertTrue(service.lookup("[0.1]", 1L, "SIG", protocol()).isEmpty());  // 缓存失败不抛
    }

    // ============================ store ============================

    @Test
    void store_disabled_isNoOp() {
        props.setEnabled(false);
        service.store("q", "[0.1]", 1L, List.of(1L), "sig",
                payload(), List.of(1L), List.of("h"), 0.9, protocol());
        verify(answerCacheMapper, never()).insert(any(), anyString());
    }

    @Test
    void store_emptyNodes_isNoOp() {
        props.setEnabled(true);
        service.store("q", "[0.1]", 1L, List.of(1L), "sig",
                payload(), List.of(), List.of(), 0.9, protocol());
        verify(answerCacheMapper, never()).insert(any(), anyString());
    }

    @Test
    void store_nullPayload_isNoOp() {
        props.setEnabled(true);
        service.store("q", "[0.1]", 1L, List.of(1L), "sig",
                null, List.of(1L), List.of("h"), 0.9, protocol());
        verify(answerCacheMapper, never()).insert(any(), anyString());
    }

    @Test
    void store_valid_insertsRow() {
        props.setEnabled(true);
        props.setTtlDays(7);
        service.store("q", "[0.1]", 1L, List.of(1L), "sig",
                payload(), List.of(1L), List.of("h"), 0.9, protocol());
        verify(answerCacheMapper).insert(argThat(row ->
                "embed-a".equals(row.getKeyEmbeddingModel())
                        && "rank-v1".equals(row.getRankingConfigVersion())
                        && "pipe-v1".equals(row.getPipelineVersion())
                        && "prompt-v1".equals(row.getPromptVersion())
                        && "snap-v1".equals(row.getKnowledgeSnapshot())), eq("[0.1]"));
    }

    @Test
    void store_insertFailure_swallowed() {
        props.setEnabled(true);
        doThrow(new RuntimeException("db down")).when(answerCacheMapper).insert(any(), anyString());

        assertDoesNotThrow(() -> service.store("q", "[0.1]", 1L, List.of(1L), "sig",
                payload(), List.of(1L), List.of("h"), 0.9, protocol()));  // 写失败不抛
    }

    // ============================ helpers ============================

    private CachedPayload payload() {
        CachedPayload p = new CachedPayload();
        p.setAnswer("a");
        p.setInjectedIndexes(List.of(1));
        return p;
    }

    private CacheCandidateRow candidate(double distance, String sig, String nodeIdsJson,
                                        String hashesJson, String answerJson) {
        CacheCandidateRow c = new CacheCandidateRow();
        c.setId(99L);
        c.setCosineDistance(distance);
        c.setPermissionSignature(sig);
        c.setProvenanceNodeIds(nodeIdsJson);
        c.setEvidenceHashes(hashesJson);
        c.setAnswer(answerJson);   // null = parsePayload 返 null → continue
        return c;
    }

    private RagQueryRow.HashVerifyRow hashRow(String nodeHash) {
        RagQueryRow.HashVerifyRow hv = new RagQueryRow.HashVerifyRow();
        hv.setNodeHash(nodeHash);
        return hv;
    }

    private AnswerCacheService.CacheProtocol protocol() {
        return new AnswerCacheService.CacheProtocol(
                "embed-a", "rank-v1", "pipe-v1", "prompt-v1", "snap-v1");
    }

    private void stubCandidates(CacheCandidateRow candidate) {
        when(answerCacheMapper.searchCandidates(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of(candidate));
    }
}
