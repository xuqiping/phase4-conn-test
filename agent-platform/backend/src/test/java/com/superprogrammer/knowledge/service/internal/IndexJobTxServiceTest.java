package com.superprogrammer.knowledge.service.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeIndexJob;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.mapper.KnowledgeDocEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeIndexJobMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import com.superprogrammer.knowledge.util.L1EmbedText;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * IndexJobTxService 不变式测：I2 完成前复校 / failJob DEAD vs PENDING 退避 / claim 状态机 / doc→INDEXED。
 * 通过 LambdaUpdateWrapper.getSqlSet() 断言 SET 子句含 'DEAD'/'PENDING'（须先 init MP TableInfo 填 lambda 缓存）。
 */
@ExtendWith(MockitoExtension.class)
class IndexJobTxServiceTest {

    @Mock private KnowledgeIndexJobMapper indexJobMapper;
    @Mock private KnowledgeEmbeddingMapper embeddingMapper;
    @Mock private KnowledgeDocEmbeddingMapper docEmbeddingMapper;
    @Mock private KnowledgeNodeMapper nodeMapper;
    @Mock private KnowledgeDocumentMapper documentMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private IndexJobTxService service;

    /** 填充 MP lambda 缓存，使 LambdaUpdateWrapper.getSqlSet() 能把 SFunction 解析为列名。 */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(cfg, "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeIndexJob.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocument.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new IndexJobTxService(indexJobMapper, embeddingMapper, docEmbeddingMapper,
                nodeMapper, documentMapper, objectMapper);
    }

    // ============================ failJob 退避 ============================

    @Test
    void failJob_attemptBelowMax_setsPending() {
        when(indexJobMapper.selectById(1L)).thenReturn(job(1, 5));  // attempt=1, max=5
        when(indexJobMapper.update(isNull(), any())).thenReturn(1);

        service.failJob(1L, "err");

        LambdaUpdateWrapper<KnowledgeIndexJob> w = captureUpdateWrapper();
        assertTrue(w.getParamNameValuePairs().containsValue("PENDING"),
                "attempt<max 须置 PENDING，实际参数: " + w.getParamNameValuePairs());
        assertTrue(w.getSqlSet().contains("locked_until"), "须设 locked_until 退避列: " + w.getSqlSet());
    }

    @Test
    void failJob_attemptReachesMax_setsDead() {
        when(indexJobMapper.selectById(1L)).thenReturn(job(5, 5));  // attempt=5 ≥ max=5 → DEAD
        when(indexJobMapper.update(isNull(), any())).thenReturn(1);

        service.failJob(1L, "err");

        LambdaUpdateWrapper<KnowledgeIndexJob> w = captureUpdateWrapper();
        assertTrue(w.getParamNameValuePairs().containsValue("DEAD"),
                "attempt≥max 须置 DEAD，实际参数: " + w.getParamNameValuePairs());
    }

    @Test
    void failJob_nullMaxDefaults5() {
        when(indexJobMapper.selectById(1L)).thenReturn(job(3, null));  // max null → 默认 5
        when(indexJobMapper.update(isNull(), any())).thenReturn(1);

        service.failJob(1L, "err");

        LambdaUpdateWrapper<KnowledgeIndexJob> w = captureUpdateWrapper();
        assertTrue(w.getParamNameValuePairs().containsValue("PENDING"));  // attempt 3 < 默认 5
    }

    @Test
    void failJob_unknownJob_skipped() {
        when(indexJobMapper.selectById(999L)).thenReturn(null);

        service.failJob(999L, "err");

        verify(indexJobMapper, never()).update(isNull(), any());
    }

    // ============================ completeUpsert I2 ============================

    @Test
    void complete_nodeNull_voidsNoUpsert() {
        when(nodeMapper.selectById(10L)).thenReturn(null);
        when(indexJobMapper.update(isNull(), any())).thenReturn(1);

        service.completeUpsert(1L, 10L, 99L, 7L, "model", "[0.1]", "hash");

        verify(embeddingMapper, never()).upsert(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void complete_nodeInactive_voidsNoUpsert() {
        KnowledgeNode n = node("hash", "ARCHIVED");
        when(nodeMapper.selectById(10L)).thenReturn(n);
        when(indexJobMapper.update(isNull(), any())).thenReturn(1);

        service.completeUpsert(1L, 10L, 99L, 7L, "model", "[0.1]", "hash");

        verify(embeddingMapper, never()).upsert(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void complete_hashMismatch_voidsNoUpsert() {
        KnowledgeNode n = node("different-hash", "ACTIVE");  // node hash ≠ 传入 hash
        when(nodeMapper.selectById(10L)).thenReturn(n);
        when(indexJobMapper.update(isNull(), any())).thenReturn(1);

        service.completeUpsert(1L, 10L, 99L, 7L, "model", "[0.1]", "hash");

        verify(embeddingMapper, never()).upsert(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void complete_success_upsertsAndMarksIndexed() {
        KnowledgeNode n = node("hash", "ACTIVE");
        n.setId(10L);
        when(nodeMapper.selectById(10L)).thenReturn(n);
        when(indexJobMapper.update(isNull(), any())).thenReturn(1);
        when(indexJobMapper.countPendingRunningByDoc(99L)).thenReturn(0L);  // 文档全完成
        KnowledgeDocument doc = new KnowledgeDocument();   // markDocIndexedIfDone 转换时读 doc 取 fileRef
        doc.setId(99L);
        when(documentMapper.selectById(99L)).thenReturn(doc);
        when(documentMapper.update(isNull(), any())).thenReturn(1);

        service.completeUpsert(1L, 10L, 99L, 7L, "doubao-embedding-vision", "[0.1]", "hash");

        verify(embeddingMapper).upsert(eq(10L), eq(1L), eq(7L), eq("L0"),
                eq("doubao-embedding-vision"), eq("[0.1]"), eq("hash"), eq("__phase1_placeholder__"));
        verify(documentMapper).update(isNull(), any());  // markDocIndexedIfDone → INDEXED
    }

    @Test
    void complete_docHasPendingJobs_skipsIndexed() {
        KnowledgeNode n = node("hash", "ACTIVE");
        n.setId(10L);
        when(nodeMapper.selectById(10L)).thenReturn(n);
        when(indexJobMapper.update(isNull(), any())).thenReturn(1);
        when(indexJobMapper.countPendingRunningByDoc(99L)).thenReturn(2L);  // 仍有 PENDING/RUNNING

        service.completeUpsert(1L, 10L, 99L, 7L, "model", "[0.1]", "hash");

        verify(embeddingMapper).upsert(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(documentMapper, never()).update(isNull(), any());  // 不置 INDEXED
    }

    // ============================ claimBatch 状态机 ============================

    @Test
    void claim_emptyReturnsEmpty() {
        when(indexJobMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.claimBatch(8).isEmpty());
        verify(indexJobMapper, never()).updateById(any());
    }

    @Test
    void claim_mutatesToRunningAndAttempts() {
        KnowledgeIndexJob a = job(0, 5);
        KnowledgeIndexJob b = job(2, 5);
        when(indexJobMapper.selectList(any())).thenReturn(List.of(a, b));
        when(indexJobMapper.updateById(any())).thenReturn(1);

        List<KnowledgeIndexJob> claimed = service.claimBatch(8);

        assertEquals(2, claimed.size());
        assertEquals("RUNNING", a.getStatus());
        assertEquals("RUNNING", b.getStatus());
        assertEquals(1, a.getAttempt());   // 0+1
        assertEquals(3, b.getAttempt());   // 2+1
        assertNotNull(a.getLockedUntil());
        verify(indexJobMapper, times(2)).updateById(any());
    }

    // ============================ voidJob ============================

    @Test
    void voidJob_setsFailed() {
        when(indexJobMapper.update(isNull(), any())).thenReturn(1);

        service.voidJob(1L, "reason");

        LambdaUpdateWrapper<KnowledgeIndexJob> w = captureUpdateWrapper();
        assertTrue(w.getParamNameValuePairs().containsValue("FAILED"));
        assertTrue(w.getSqlSet().contains("locked_until"));  // 置 null 列
    }

    // ============================ completeUpsertL1（Phase3）============================

    @Test
    void completeL1_success_upsertsAndMarksIndexed() {
        String l1Json = "{\"summary\":\"安装\"}";
        String hash = L1EmbedText.hashOfJson(l1Json, objectMapper);
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(99L);
        doc.setL1Metadata(l1Json);
        when(documentMapper.selectById(99L)).thenReturn(doc);
        when(indexJobMapper.update(isNull(), any())).thenReturn(1);
        when(indexJobMapper.countPendingRunningByDoc(99L)).thenReturn(0L);  // 文档全完成
        when(documentMapper.update(isNull(), any())).thenReturn(1);

        service.completeUpsertL1(1L, 99L, 7L, "doubao", "[0.1]", hash);

        verify(docEmbeddingMapper).upsert(eq(99L), eq(1L), eq(7L), eq("doubao"), eq("[0.1]"), eq(hash));
        verify(documentMapper).update(isNull(), any());   // markDocIndexedIfDone → INDEXED
    }

    @Test
    void completeL1_hashMismatch_voidsNoUpsert() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(99L);
        doc.setL1Metadata("{\"summary\":\"changed\"}");
        when(documentMapper.selectById(99L)).thenReturn(doc);
        when(indexJobMapper.update(isNull(), any())).thenReturn(1);   // voidJob 的 update

        service.completeUpsertL1(1L, 99L, 7L, "doubao", "[0.1]", "stale-hash");

        verify(docEmbeddingMapper, never()).upsert(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void completeL1_docNull_voidsNoUpsert() {
        when(documentMapper.selectById(99L)).thenReturn(null);
        when(indexJobMapper.update(isNull(), any())).thenReturn(1);

        service.completeUpsertL1(1L, 99L, 7L, "doubao", "[0.1]", "hash");

        verify(docEmbeddingMapper, never()).upsert(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    // ============================ helpers ============================

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<KnowledgeIndexJob> captureUpdateWrapper() {
        ArgumentCaptor<LambdaUpdateWrapper<KnowledgeIndexJob>> c =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(indexJobMapper, atLeastOnce()).update(isNull(), c.capture());
        return c.getValue();
    }

    private KnowledgeIndexJob job(int attempt, Integer max) {
        KnowledgeIndexJob j = new KnowledgeIndexJob();
        j.setId(1L);
        j.setAttempt(attempt);
        j.setMaxAttempt(max);
        return j;
    }

    private KnowledgeNode node(String contentHash, String status) {
        KnowledgeNode n = new KnowledgeNode();
        n.setContentHash(contentHash);
        n.setStatus(status);
        return n;
    }
}
