package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.entity.MemoryAssetMemory;
import com.superprogrammer.chat.mapper.MemoryAssetChunkMapper;
import com.superprogrammer.chat.mapper.MemoryAssetMemoryMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 文件 ingestion（V69 二期 P3 Step 2，FR-202/205）。
 * AC：READY 全链 / 弱记忆降级 / FAILED 可重试 / 重试上限硬卡 / 归属校验。
 */
@ExtendWith(MockitoExtension.class)
class MemoryAssetIngestServiceTest {

    @Mock private MemoryAssetMemoryMapper memoryMapper;
    @Mock private MemoryAssetChunkMapper chunkMapper;
    @Mock private MemoryAssetExtractor extractor;
    @Mock private FileStorageService fileStorageService;
    @Mock private MemoryTagResolver tagResolver;
    @Mock private LlmGateway llmGateway;
    @Mock private MemoryRoutingService routingService;
    @Mock private com.superprogrammer.chat.mapper.MemoryProjectEntryMapper projectEntryMapper;

    private MemoryAssetIngestService service;

    @BeforeEach
    void setUp() {
        service = new MemoryAssetIngestService(memoryMapper, chunkMapper, extractor,
                fileStorageService, tagResolver, llmGateway, new ObjectMapper(),
                routingService, projectEntryMapper);
    }

    private MemoryAssetMemory row(String status) {
        MemoryAssetMemory r = new MemoryAssetMemory();
        r.setId(1L);
        r.setOwnerUserId(100L);
        r.setFileId("f-abc.pdf");
        r.setFileKind(MemoryAssetMemory.KIND_PDF);
        r.setOriginalName("课件.pdf");
        r.setIngestStatus(status);
        r.setRetryCount(0);
        return r;
    }

    private void stubFileAndExtract(MemoryAssetExtractor.ExtractResult result) throws Exception {
        StoredFileEntity meta = new StoredFileEntity();
        meta.setFileId("f-abc.pdf");
        meta.setStatus(StoredFileEntity.STATUS_ACTIVE);
        when(fileStorageService.findMeta("f-abc.pdf")).thenReturn(meta);
        when(fileStorageService.loadPath("f-abc.pdf", 100L, false)).thenReturn(Path.of("x"));
        when(extractor.extract(any(), eq(MemoryAssetMemory.KIND_PDF), eq("课件.pdf"))).thenReturn(result);
    }

    // ============================ FR-202 READY 全链 ============================

    @Test
    @DisplayName("FR-202 正常链：分块落库+向量 → 一次汇总 LLM → READY+l1/l2+tag 归一")
    void processOne_happyPath() throws Exception {
        when(memoryMapper.selectById(1L)).thenReturn(row(MemoryAssetMemory.STATUS_PROCESSING));
        stubFileAndExtract(new MemoryAssetExtractor.ExtractResult(List.of(
                new MemoryAssetExtractor.ChunkDraft(1, "hooks 原理", "第1页"),
                new MemoryAssetExtractor.ChunkDraft(2, "常见坑", "第2页")), 2, false));
        when(llmGateway.embed(anyString(), anyString(), eq(100L))).thenReturn(new float[2048]);
        when(llmGateway.chat(any(), eq(100L))).thenReturn(LlmResponse.builder().content(
                "{\"l1\":\"《课件.pdf》：讲 hooks 原理与坑\",\"l2\":\"1. 原理\\n2. 坑\",\"tags\":[\"React\",\"hooks\"]}").build());
        when(tagResolver.resolve(eq(100L), eq("文件"), anyString(), anyString())).thenReturn(11L);

        service.processOne(1L);

        verify(chunkMapper).softDeleteByMemoryId(1L);
        verify(chunkMapper, times(2)).insertWithEmbedding(eq(1L), anyInt(), anyString(), anyString(), any());
        verify(llmGateway, times(1)).chat(any(), eq(100L));   // 汇总仅一次 LLM（plan 坑表②）
        verify(memoryMapper).finishIngest(eq(1L), eq(MemoryAssetMemory.STATUS_READY), isNull(),
                eq("《课件.pdf》：讲 hooks 原理与坑"), eq("1. 原理\n2. 坑"), eq(false), eq(0));
        verify(memoryMapper).updateTagIds(eq(1L), argThat(tags -> tags.size() == 2));
        // FR-204 READY 钩子：文件 l1/l2 过路由（content_type=FILE 入口）
        verify(routingService).routeAsync(argThat(input ->
                "f-abc.pdf".equals(input.fileId()) && input.userId().equals(100L)
                        && input.sourceTurnId() == null
                        && input.l1().equals("《课件.pdf》：讲 hooks 原理与坑")));
    }

    @Test
    @DisplayName("FR-202 单 chunk embed 失败 → null 向量降级不阻断 READY")
    void processOne_embedFailureDegrades() throws Exception {
        when(memoryMapper.selectById(1L)).thenReturn(row(MemoryAssetMemory.STATUS_PROCESSING));
        stubFileAndExtract(new MemoryAssetExtractor.ExtractResult(List.of(
                new MemoryAssetExtractor.ChunkDraft(1, "t1", "第1页")), 1, false));
        when(llmGateway.embed(anyString(), anyString(), eq(100L))).thenThrow(new RuntimeException("embed down"));
        when(llmGateway.chat(any(), eq(100L))).thenReturn(LlmResponse.builder().content(
                "{\"l1\":\"l1\",\"l2\":\"l2\",\"tags\":[]}").build());

        service.processOne(1L);

        verify(chunkMapper).insertWithEmbedding(eq(1L), eq(1), eq("t1"), eq("第1页"), isNull());
        verify(memoryMapper).finishIngest(eq(1L), eq(MemoryAssetMemory.STATUS_READY), isNull(),
                eq("l1"), eq("l2"), eq(false), eq(0));
    }

    // ============================ FR-205 降级 ============================

    @Test
    @DisplayName("FR-205 无解析器模态 → 弱记忆 READY + 「读不懂内容」明示，不调 LLM")
    void processOne_unsupported_weakMemory() throws Exception {
        when(memoryMapper.selectById(1L)).thenReturn(row(MemoryAssetMemory.STATUS_PROCESSING));
        stubFileAndExtract(new MemoryAssetExtractor.ExtractResult(List.of(), 0, true));

        service.processOne(1L);

        verify(memoryMapper).finishIngest(eq(1L), eq(MemoryAssetMemory.STATUS_READY), isNull(),
                argThat(l1 -> l1.contains("读不懂内容")), isNull(), eq(true), eq(0));
        verifyNoInteractions(llmGateway, chunkMapper);
        verify(routingService, never()).routeAsync(any());   // FR-204：弱记忆不路由（无内容蒸馏是噪声）
    }

    @Test
    @DisplayName("FR-205 扫描件全文无文字层 → 弱记忆 READY（unsupported=false 但 hasText=false）")
    void processOne_noText_weakMemory() throws Exception {
        when(memoryMapper.selectById(1L)).thenReturn(row(MemoryAssetMemory.STATUS_PROCESSING));
        stubFileAndExtract(new MemoryAssetExtractor.ExtractResult(List.of(), 5, false));

        service.processOne(1L);

        verify(memoryMapper).finishIngest(eq(1L), eq(MemoryAssetMemory.STATUS_READY), isNull(),
                argThat(l1 -> l1.contains("读不懂内容")), isNull(), eq(true), eq(0));
    }

    @Test
    @DisplayName("FR-202 原文件已删除（meta 缺失/CLEANED）→ FAILED 固定话术")
    void processOne_fileGone_failed() {
        when(memoryMapper.selectById(1L)).thenReturn(row(MemoryAssetMemory.STATUS_PROCESSING));
        when(fileStorageService.findMeta("f-abc.pdf")).thenReturn(null);

        service.processOne(1L);

        verify(memoryMapper).finishIngest(eq(1L), eq(MemoryAssetMemory.STATUS_FAILED),
                eq("原文件已删除或不可读"), isNull(), isNull(), eq(false), eq(0));
    }

    @Test
    @DisplayName("FR-202 汇总 LLM 失败 → FAILED retry_count+1 可重试（固定话术不透传异常）")
    void processOne_llmFailure_failedRetryable() throws Exception {
        when(memoryMapper.selectById(1L)).thenReturn(row(MemoryAssetMemory.STATUS_PROCESSING));
        stubFileAndExtract(new MemoryAssetExtractor.ExtractResult(List.of(
                new MemoryAssetExtractor.ChunkDraft(1, "t1", "第1页")), 1, false));
        when(llmGateway.embed(anyString(), anyString(), eq(100L))).thenReturn(new float[2048]);
        when(llmGateway.chat(any(), eq(100L))).thenThrow(new RuntimeException("LLM 超时内部细节"));

        service.processOne(1L);

        verify(memoryMapper).finishIngest(eq(1L), eq(MemoryAssetMemory.STATUS_FAILED),
                eq("解析失败，可在文件记忆中重试"), isNull(), isNull(), eq(false), eq(1));
    }

    @Test
    @DisplayName("FR-202 非 PROCESSING 行不处理（worker 重复认领幂等）")
    void processOne_notProcessing_skips() {
        when(memoryMapper.selectById(1L)).thenReturn(row(MemoryAssetMemory.STATUS_READY));
        service.processOne(1L);
        verify(memoryMapper, never()).finishIngest(any(), any(), any(), any(), any(), anyBoolean(), anyInt());
    }

    // ============================ FR-202 手动重试 ============================

    @Test
    @DisplayName("FR-202 重试：非本人 → NOT_FOUND（IDOR 咽喉）")
    void retry_notOwner_forbidden() {
        MemoryAssetMemory r = row(MemoryAssetMemory.STATUS_FAILED);
        r.setOwnerUserId(200L);
        when(memoryMapper.selectById(1L)).thenReturn(r);
        assertThrows(BusinessException.class, () -> service.retry(1L, 100L));
    }

    @Test
    @DisplayName("FR-202 重试：非 FAILED → BAD_REQUEST；超上限 → BAD_REQUEST")
    void retry_wrongStatusOrOverLimit() {
        when(memoryMapper.selectById(1L)).thenReturn(row(MemoryAssetMemory.STATUS_READY));
        assertThrows(BusinessException.class, () -> service.retry(1L, 100L));

        MemoryAssetMemory r = row(MemoryAssetMemory.STATUS_FAILED);
        r.setRetryCount(MemoryAssetIngestService.MAX_RETRY);
        when(memoryMapper.selectById(1L)).thenReturn(r);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.retry(1L, 100L));
        assertTrue(ex.getMessage().contains("重试上限"));
    }

    @Test
    @DisplayName("FR-202 重试：条件 UPDATE 影响 0 行（并发重复触发）→ 409")
    void retry_concurrent_conflict() {
        when(memoryMapper.selectById(1L)).thenReturn(row(MemoryAssetMemory.STATUS_FAILED));
        when(memoryMapper.requeue(1L)).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.retry(1L, 100L));
    }

    @Test
    @DisplayName("FR-202 重试：合法 → 置回 PROCESSING")
    void retry_ok() {
        when(memoryMapper.selectById(1L)).thenReturn(row(MemoryAssetMemory.STATUS_FAILED));
        when(memoryMapper.requeue(1L)).thenReturn(1);
        assertDoesNotThrow(() -> service.retry(1L, 100L));
        verify(memoryMapper).requeue(1L);
    }

    // ============================ FR-204 删除闭环 ============================

    @Test
    @DisplayName("FR-204 删除：非本人 → NOT_FOUND（IDOR 咽喉），不动任何数据")
    void delete_notOwner_forbidden() {
        MemoryAssetMemory r = row(MemoryAssetMemory.STATUS_READY);
        r.setOwnerUserId(200L);
        when(memoryMapper.selectById(1L)).thenReturn(r);
        assertThrows(BusinessException.class, () -> service.delete(1L, 100L));
        verify(chunkMapper, never()).softDeleteByMemoryId(any());
        verify(projectEntryMapper, never()).softDeleteFileEntries(any());
        verify(fileStorageService, never()).delete(any());
    }

    @Test
    @DisplayName("FR-204 删除：本人 → 分块/记忆软删 + 项目 FILE 条目失效 + 原文件硬删")
    void delete_owner_fullChain() {
        when(memoryMapper.selectById(1L)).thenReturn(row(MemoryAssetMemory.STATUS_READY));
        when(projectEntryMapper.softDeleteFileEntries("f-abc.pdf")).thenReturn(2);

        service.delete(1L, 100L);

        verify(chunkMapper).softDeleteByMemoryId(1L);
        verify(memoryMapper).deleteById(1L);
        verify(projectEntryMapper).softDeleteFileEntries("f-abc.pdf");  // 项目条目标失效
        verify(fileStorageService).delete("f-abc.pdf");
    }

    @Test
    @DisplayName("FR-204 删除：fileId 为空 → 跳过条目失效与文件删除")
    void delete_noFileId_skipsFileSide() {
        MemoryAssetMemory r = row(MemoryAssetMemory.STATUS_FAILED);
        r.setFileId(null);
        when(memoryMapper.selectById(1L)).thenReturn(r);

        service.delete(1L, 100L);

        verify(memoryMapper).deleteById(1L);
        verify(projectEntryMapper, never()).softDeleteFileEntries(any());
        verify(fileStorageService, never()).delete(any());
    }
}
