package com.superprogrammer.knowledge.connector;

import com.superprogrammer.knowledge.connector.KnowledgeConnectorSpi.ExternalDoc;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentVersionVO;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentVO;
import com.superprogrammer.knowledge.entity.KnowledgeConnector;
import com.superprogrammer.knowledge.entity.KnowledgeConnectorDoc;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.service.KnowledgeDocumentService;
import com.superprogrammer.knowledge.service.KnowledgeDocumentVersionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP6 Step3：同步 worker 编排逻辑（依赖全 mock，SPI 用匿名假实现）——
 * 到期认领门/首轮新增/增量三态（变·增·源删 ISOLATED）/源删治理删除/手工删不复活/
 * 安全隔离不动/复活+失败自愈/单轮 50 动作预算。
 */
class ConnectorSyncWorkerTest {

    private final ConnectorSyncTxService tx = mock(ConnectorSyncTxService.class);
    private final ConnectorFactory factory = mock(ConnectorFactory.class);
    private final KnowledgeDocumentService documents = mock(KnowledgeDocumentService.class);
    private final KnowledgeDocumentVersionService versions = mock(KnowledgeDocumentVersionService.class);
    private final ConnectorSyncWorker worker = new ConnectorSyncWorker(tx, factory, documents, versions);

    // ============================ 夹具 ============================

    private KnowledgeConnector connector(boolean syncOnDelete) {
        KnowledgeConnector c = new KnowledgeConnector();
        c.setId(5L);
        c.setKbId(1L);
        c.setType(KnowledgeConnector.TYPE_URL_SITE);
        c.setScheduleCron("* * * * * *");   // 每秒（测试用，判定必到期）
        c.setCreatedBy(9L);
        c.setSyncOnSourceDelete(syncOnDelete);
        c.setStatus(KnowledgeConnector.STATUS_ENABLED);
        return c;
    }

    private KnowledgeConnectorDoc mapping(String externalId, String etag, Long docId, boolean manualDeleted) {
        KnowledgeConnectorDoc m = new KnowledgeConnectorDoc();
        m.setId((long) Math.abs(externalId.hashCode()));
        m.setConnectorId(5L);
        m.setExternalId(externalId);
        m.setEtag(etag);
        m.setDocId(docId);
        m.setManualDeleted(manualDeleted);
        m.setSyncedAt(OffsetDateTime.now());
        return m;
    }

    private KnowledgeDocument doc(Long id, String status, Long currentVersionId) {
        KnowledgeDocument d = new KnowledgeDocument();
        d.setId(id);
        d.setKbId(1L);
        d.setStatus(status);
        d.setCurrentVersionId(currentVersionId);
        d.setFileRef("/api/files/old-" + id);
        d.setFileHash("hash-old-" + id);
        return d;
    }

    /** 匿名假 SPI：list 返回给定条目；fetch 返回 externalId 标记字节。 */
    private KnowledgeConnectorSpi spi(List<ExternalDoc> docs) {
        return new KnowledgeConnectorSpi() {
            @Override
            public String type() {
                return "URL_SITE";
            }

            @Override
            public List<ExternalDoc> list() {
                return docs;
            }

            @Override
            public byte[] fetch(ExternalDoc doc) {
                return ("BYTES-" + doc.externalId()).getBytes();
            }
        };
    }

    private static ExternalDoc ext(String id, String etag) {
        return new ExternalDoc(id, etag, id);
    }

    private void stubRound(KnowledgeConnector c, KnowledgeConnectorSpi s, List<KnowledgeConnectorDoc> mappings) {
        when(factory.build(c)).thenReturn(s);
        when(tx.listMappings(5L)).thenReturn(mappings);
    }

    // ============================ 用例 ============================

    @Test
    void pollOnce_dueClaimGate_onlyDueConnectorSynced() {
        KnowledgeConnector due = connector(false);
        due.setId(1L);
        due.setLastSyncAt(OffsetDateTime.now().minusSeconds(30));
        KnowledgeConnector notDue = connector(false);
        notDue.setId(2L);
        notDue.setScheduleCron("0 0 4 * * *");
        notDue.setLastSyncAt(OffsetDateTime.now());
        when(tx.listEnabled()).thenReturn(List.of(due, notDue));
        when(tx.tryClaim(eq(1L), any())).thenReturn(true);
        stubRound(due, spi(List.of()), List.of());

        worker.pollOnce();

        verify(tx).tryClaim(eq(1L), any());
        verify(tx, never()).tryClaim(eq(2L), any());
        verify(factory, never()).build(notDue);
        verify(tx).finishSuccess(eq(1L), anyString());
    }

    @Test
    void pollOnce_claimRejected_skipsSync() {
        KnowledgeConnector c = connector(false);
        c.setLastSyncAt(OffsetDateTime.now().minusSeconds(30));
        when(tx.listEnabled()).thenReturn(List.of(c));
        when(tx.tryClaim(eq(5L), any())).thenReturn(false);

        worker.pollOnce();

        verify(factory, never()).build(any());
        verify(tx, never()).finishSuccess(anyLong(), anyString());
    }

    @Test
    void firstRound_newDocsUploadedThroughUploadPipeline() {
        KnowledgeConnector c = connector(false);
        stubRound(c, spi(List.of(ext("a.md", "e-a"), ext("b.pdf", "e-b"), ext("c.txt", "e-c"))), List.of());
        when(documents.upload(eq(1L), any(MultipartFile.class), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), eq(9L), anyBoolean()))
                .thenReturn(KnowledgeDocumentVO.builder().id(101L).build())
                .thenReturn(KnowledgeDocumentVO.builder().id(102L).build())
                .thenReturn(KnowledgeDocumentVO.builder().id(103L).build());

        worker.syncConnector(c);

        ArgumentCaptor<MultipartFile> file = ArgumentCaptor.forClass(MultipartFile.class);
        verify(documents, times(3)).upload(eq(1L), file.capture(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), eq(9L), eq(false));
        assertEquals("b.pdf", file.getAllValues().get(1).getOriginalFilename());
        assertEquals("application/pdf", file.getAllValues().get(1).getContentType());
        verify(tx).insertMapping(5L, "a.md", "e-a", 101L);
        verify(tx).insertMapping(5L, "b.pdf", "e-b", 102L);
        verify(tx).finishSuccess(eq(5L), org.mockito.ArgumentMatchers.contains("新增3"));
    }

    @Test
    void incrementalRound_changeAddIsolate() {
        KnowledgeConnector c = connector(false);   // syncOnSourceDelete=false → ISOLATED
        KnowledgeConnectorDoc mA = mapping("a.md", "e1", 100L, false);
        KnowledgeConnectorDoc mKeep = mapping("keep.md", "k", 300L, false);
        KnowledgeConnectorDoc mGone = mapping("gone.md", "g", 200L, false);
        stubRound(c, spi(List.of(ext("a.md", "e2"), ext("keep.md", "k"), ext("new.md", "e-n"))),
                List.of(mA, mKeep, mGone));
        when(tx.getDocument(100L)).thenReturn(doc(100L, "INDEXED", 10L));
        when(tx.getDocument(300L)).thenReturn(doc(300L, "INDEXED", 30L));
        when(tx.getDocument(200L)).thenReturn(doc(200L, "INDEXED", 20L));
        when(documents.upload(eq(1L), any(MultipartFile.class), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), eq(9L), eq(false)))
                .thenReturn(KnowledgeDocumentVO.builder().id(104L).build());
        when(documents.createVersion(eq(100L), any(MultipartFile.class), eq(10L), anyString(),
                eq(9L), eq(false)))
                .thenReturn(KnowledgeDocumentVersionVO.builder()
                        .id(11L).fileRef("/api/files/f11").sourceHash("h2").build());

        worker.syncConnector(c);

        // 变更：版本+1 → 激活 → 换 fileRef 清旧节点重解析 → 账本 etag 前移
        verify(documents).createVersion(eq(100L), any(MultipartFile.class), eq(10L),
                org.mockito.ArgumentMatchers.contains("连接器同步"), eq(9L), eq(false));
        verify(versions).activate(100L, 11L, 10L, 9L, false);
        verify(tx).resetDocForResync(100L, "/api/files/f11", "h2", 9L);
        verify(tx).touchMapping(mA.getId(), "e2");
        // 新增：走 upload 管线 + 建账本行
        verify(tx).insertMapping(5L, "new.md", "e-n", 104L);
        // 源删（默认）：ISOLATED 隔离，不硬删
        verify(tx).isolateDoc(eq(200L), org.mockito.ArgumentMatchers.startsWith(
                ConnectorSyncTxService.ISOLATE_REASON_PREFIX));
        verify(documents, never()).delete(anyLong(), anyLong(), anyBoolean());
        // 未变：零动作
        verify(tx, never()).touchMapping(eq(mKeep.getId()), any());
        verify(tx).finishSuccess(eq(5L), org.mockito.ArgumentMatchers.contains("新增1"));
        verify(tx).finishSuccess(eq(5L), org.mockito.ArgumentMatchers.contains("更新1"));
        verify(tx).finishSuccess(eq(5L), org.mockito.ArgumentMatchers.contains("隔离1"));
    }

    @Test
    void sourceGone_syncOnDelete_governedDeleteChain() {
        KnowledgeConnector c = connector(true);
        KnowledgeConnectorDoc mGone = mapping("gone.md", "g", 200L, false);
        stubRound(c, spi(List.of()), List.of(mGone));
        when(tx.getDocument(200L)).thenReturn(doc(200L, "INDEXED", 20L));

        worker.syncConnector(c);

        verify(documents).delete(200L, 9L, false);   // 既有治理删除链
        verify(tx).removeMapping(mGone.getId());     // 账本行删——源端再现按全新文档
        verify(tx, never()).isolateDoc(anyLong(), anyString());
    }

    @Test
    void manualDeletedAndUserDeletedDoc_neverResurrect() {
        KnowledgeConnector c = connector(false);
        KnowledgeConnectorDoc mManual = mapping("old.md", "e", 400L, true);    // 手工删除标记
        KnowledgeConnectorDoc mUserGone = mapping("bye.md", "e", 500L, false); // 本地文档已被用户删
        stubRound(c, spi(List.of(ext("old.md", "e2"), ext("bye.md", "e2"))), List.of(mManual, mUserGone));
        when(tx.getDocument(500L)).thenReturn(null);

        worker.syncConnector(c);

        verify(documents, never()).upload(anyLong(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyLong(), anyBoolean());
        verify(documents, never()).createVersion(anyLong(), any(), any(), any(), anyLong(), anyBoolean());
        verify(tx).markManualDeleted(mUserGone.getId());   // 用户删 → 账本标记防复活
        verify(tx, never()).markManualDeleted(mManual.getId());
        verify(tx, never()).reviveDoc(anyLong(), anyLong());
    }

    @Test
    void securityQuarantinedDoc_neverTouchedByConnector() {
        KnowledgeConnector c = connector(false);
        KnowledgeConnectorDoc m = mapping("x.md", "e", 700L, false);
        stubRound(c, spi(List.of(ext("x.md", "e2"))), List.of(m));
        KnowledgeDocument quarantined = doc(700L, "QUARANTINED", 70L);
        quarantined.setQuarantineReason("检测到提示注入特征: ignore previous instructions");
        when(tx.getDocument(700L)).thenReturn(quarantined);

        worker.syncConnector(c);

        verify(documents, never()).createVersion(anyLong(), any(), any(), any(), anyLong(), anyBoolean());
        verify(tx, never()).resetDocForResync(anyLong(), any(), any(), anyLong());
        verify(tx, never()).reviveDoc(anyLong(), anyLong());
        verify(tx, never()).finishSuccess(anyLong(), anyString());
        verify(tx).recordError(eq(5L), org.mockito.ArgumentMatchers.contains("安全隔离"));
    }

    @Test
    void isolatedReviveOnReturn_andFailedRetry() {
        KnowledgeConnector c = connector(false);
        KnowledgeConnectorDoc mBack = mapping("back.md", "k", 600L, false);
        KnowledgeConnectorDoc mFailed = mapping("fail.md", "f", 650L, false);
        stubRound(c, spi(List.of(ext("back.md", "k"), ext("fail.md", "f"))), List.of(mBack, mFailed));
        KnowledgeDocument isolated = doc(600L, "QUARANTINED", 60L);
        isolated.setQuarantineReason(ConnectorSyncTxService.ISOLATE_REASON_PREFIX + ": http://x/back.md");
        when(tx.getDocument(600L)).thenReturn(isolated);
        when(tx.getDocument(650L)).thenReturn(doc(650L, "FAILED", 65L));

        worker.syncConnector(c);

        // ISOLATED + 源恢复 + etag 未变 → 复活重解析（无抓取无新版本）
        verify(tx).reviveDoc(600L, 9L);
        verify(tx).touchMapping(mBack.getId(), "k");
        // FAILED + etag 未变 → 同文件重解析自愈
        verify(tx).resetDocForResync(650L, "/api/files/old-650", "hash-old-650", 9L);
        verify(tx).touchMapping(mFailed.getId(), "f");
        verify(documents, never()).upload(anyLong(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyLong(), anyBoolean());
        verify(tx).finishSuccess(eq(5L), org.mockito.ArgumentMatchers.contains("复活1"));
        verify(tx).finishSuccess(eq(5L), org.mockito.ArgumentMatchers.contains("重试1"));
    }

    @Test
    void roundBudget_fiftyActionsCap() {
        KnowledgeConnector c = connector(false);
        List<ExternalDoc> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(ext(String.format("f%02d.md", i), "e" + i));
        }
        stubRound(c, spi(many), List.of());
        when(documents.upload(eq(1L), any(MultipartFile.class), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), eq(9L), anyBoolean()))
                .thenReturn(KnowledgeDocumentVO.builder().id(1000L).build());

        worker.syncConnector(c);

        verify(documents, times(ConnectorSyncWorker.MAX_ACTIONS_PER_ROUND))
                .upload(eq(1L), any(MultipartFile.class), isNull(), isNull(), isNull(),
                        isNull(), isNull(), isNull(), isNull(), eq(9L), eq(false));
        verify(tx, times(ConnectorSyncWorker.MAX_ACTIONS_PER_ROUND)).insertMapping(anyLong(),
                anyString(), anyString(), anyLong());
    }

    @Test
    void listFailure_recordErrorForStreak() {
        KnowledgeConnector c = connector(false);
        when(factory.build(c)).thenThrow(new RuntimeException("连接失败 http://user:secret@evil"));

        worker.syncConnector(c);

        // 脱敏：URL userinfo 剥除后入账
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(tx).recordError(eq(5L), msg.capture());
        assertFalse(msg.getValue().contains("user:secret"));
        assertTrue(msg.getValue().contains("***@"));
        verify(tx, never()).finishSuccess(anyLong(), anyString());
    }
}
