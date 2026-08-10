package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.AssetChunkCount;
import com.superprogrammer.chat.dto.FileChunkHit;
import com.superprogrammer.chat.dto.MemoryProjectEntryVO;
import com.superprogrammer.chat.dto.RecalledFileCard;
import com.superprogrammer.chat.entity.MemoryAssetMemory;
import com.superprogrammer.chat.entity.MemoryProjectEntry;
import com.superprogrammer.chat.mapper.MemoryAssetChunkMapper;
import com.superprogrammer.chat.mapper.MemoryAssetMemoryMapper;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.mapper.StoredFileMapper;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import com.superprogrammer.llm.LlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 记忆二期 P3 · Step 3（FR-203）· MemoryAssetRecallService 单测（纯 Mockito，无 MP lambda 包装器）。
 * <p>
 * 覆盖：命中卡片装配（chunk 计数/CLEANED 标记/可下载派生）、深读门控（embed 失败/维度不符降级、
 * top-k 映射带 pageRef + 文件名回查、query 截断）、空入参短路。
 */
@ExtendWith(MockitoExtension.class)
class MemoryAssetRecallServiceTest {

    @Mock
    MemoryAssetMemoryMapper memoryMapper;
    @Mock
    MemoryAssetChunkMapper chunkMapper;
    @Mock
    StoredFileMapper storedFileMapper;
    @Mock
    LlmGateway llmGateway;

    private MemoryAssetRecallService service;

    private static final Long SELF = 1L;

    @BeforeEach
    void setUp() {
        service = new MemoryAssetRecallService(memoryMapper, chunkMapper, storedFileMapper, llmGateway);
    }

    private static MemoryAssetMemory row(long id, String fileId, String name) {
        MemoryAssetMemory m = new MemoryAssetMemory();
        m.setId(id);
        m.setOwnerUserId(SELF);
        m.setFileId(fileId);
        m.setOriginalName(name);
        m.setFileKind(MemoryAssetMemory.KIND_PDF);
        m.setL1Summary("l1-" + name);
        m.setL2Detail("l2-" + name);
        m.setIngestStatus(MemoryAssetMemory.STATUS_READY);
        m.setWeakMemory(false);
        return m;
    }

    private static StoredFileEntity meta(String fileId, String status) {
        StoredFileEntity e = new StoredFileEntity();
        e.setFileId(fileId);
        e.setStatus(status);
        return e;
    }

    private static AssetChunkCount count(long memoryId, long cnt) {
        AssetChunkCount c = new AssetChunkCount();
        c.setAssetMemoryId(memoryId);
        c.setCnt(cnt);
        return c;
    }

    private static RecalledFileCard card(long memoryId, String name) {
        return RecalledFileCard.builder()
                .memoryId(memoryId).fileId("f" + memoryId).originalName(name)
                .fileKind("PDF").chunkCount(3).fileCleaned(false).downloadable(true)
                .l1("l1").build();
    }

    // ===== collectFileCards =====

    @Test
    void collect_emptyTagIds_shortCircuit() {
        assertTrue(service.collectFileCards(List.of(), SELF).isEmpty());
        assertTrue(service.collectFileCards(null, SELF).isEmpty());
        verifyNoInteractions(memoryMapper, chunkMapper, storedFileMapper);
    }

    @Test
    void collect_noHit_returnsEmpty() {
        when(memoryMapper.findReadyByTagOverlap(eq(SELF), anyList(), eq(MemoryAssetRecallService.MAX_FILE_HITS)))
                .thenReturn(List.of());
        assertTrue(service.collectFileCards(List.of(10L), SELF).isEmpty());
        verifyNoInteractions(chunkMapper, storedFileMapper);
    }

    @Test
    void collect_hit_buildsCardWithCountAndDownloadable() {
        when(memoryMapper.findReadyByTagOverlap(eq(SELF), eq(List.of(10L)), anyInt()))
                .thenReturn(List.of(row(501, "f-a", "课件A.pdf")));
        when(chunkMapper.countByMemoryIds(List.of(501L))).thenReturn(List.of(count(501, 12)));
        when(storedFileMapper.selectBatchIds(List.of("f-a"))).thenReturn(List.of(meta("f-a", StoredFileEntity.STATUS_ACTIVE)));

        List<RecalledFileCard> cards = service.collectFileCards(List.of(10L), SELF);

        assertEquals(1, cards.size());
        RecalledFileCard c = cards.get(0);
        assertEquals(501L, c.getMemoryId());
        assertEquals("f-a", c.getFileId());
        assertEquals("课件A.pdf", c.getOriginalName());
        assertEquals(12, c.getChunkCount(), "分块计数≈页数");
        assertFalse(c.isFileCleaned());
        assertTrue(c.isDownloadable(), "ACTIVE → 可下载");
        assertEquals("l1-课件A.pdf", c.getL1());
        assertEquals("l2-课件A.pdf", c.getL2());
    }

    @Test
    void collect_cleanedOrMissingMeta_marksNotDownloadable() {
        when(memoryMapper.findReadyByTagOverlap(eq(SELF), anyList(), anyInt()))
                .thenReturn(List.of(row(501, "f-cleaned", "旧课件.pdf"), row(502, "f-gone", "丢失.pdf")));
        when(chunkMapper.countByMemoryIds(List.of(501L, 502L))).thenReturn(List.of(count(501, 5)));
        when(storedFileMapper.selectBatchIds(List.of("f-cleaned", "f-gone")))
                .thenReturn(List.of(meta("f-cleaned", StoredFileEntity.STATUS_CLEANED)));  // f-gone 行不存在

        List<RecalledFileCard> cards = service.collectFileCards(List.of(10L), SELF);

        assertTrue(cards.get(0).isFileCleaned(), "CLEANED 标删除");
        assertFalse(cards.get(0).isDownloadable());
        assertEquals(5, cards.get(0).getChunkCount());
        assertTrue(cards.get(1).isFileCleaned(), "meta 行缺失同样标删除");
        assertFalse(cards.get(1).isDownloadable());
        assertEquals(0, cards.get(1).getChunkCount(), "无计数行 → 0 块");
    }

    // ===== deepReadChunks =====

    @Test
    void deepRead_emptyHitsOrBlankQuery_shortCircuit() {
        assertTrue(service.deepReadChunks(List.of(), "问", SELF).isEmpty());
        assertTrue(service.deepReadChunks(List.of(card(1, "a")), "  ", SELF).isEmpty());
        verifyNoInteractions(llmGateway, chunkMapper);
    }

    @Test
    void deepRead_embedThrows_degradesToEmpty() {
        when(llmGateway.embed(anyString(), anyString(), eq(SELF))).thenThrow(new RuntimeException("embed down"));
        assertTrue(service.deepReadChunks(List.of(card(501, "课件A.pdf")), "hooks 那页", SELF).isEmpty());
        verify(chunkMapper, never()).searchTopK(anyList(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void deepRead_embedNullOrWrongDim_degradesToEmpty() {
        when(llmGateway.embed(anyString(), anyString(), eq(SELF))).thenReturn(null);
        assertTrue(service.deepReadChunks(List.of(card(501, "课件A.pdf")), "问", SELF).isEmpty());

        when(llmGateway.embed(anyString(), anyString(), eq(SELF))).thenReturn(new float[8]);
        assertTrue(service.deepReadChunks(List.of(card(501, "课件A.pdf")), "问", SELF).isEmpty());
        verify(chunkMapper, never()).searchTopK(anyList(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void deepRead_happy_mapsHitsWithFileNameAndPageRef() {
        when(llmGateway.embed(eq("hooks 那页"), anyString(), eq(SELF))).thenReturn(new float[HalfVecUtil.DIM]);
        FileChunkHit hit = new FileChunkHit();
        hit.setId(9001L);
        hit.setAssetMemoryId(501L);
        hit.setChunkNo(12);
        hit.setChunkText("useEffect 依赖数组规则");
        hit.setPageRef("第12页");
        hit.setDistance(0.21d);
        when(chunkMapper.searchTopK(eq(List.of(501L)), anyString(),
                eq(MemoryAssetRecallService.MAX_DISTANCE), eq(MemoryAssetRecallService.DEEP_READ_TOP_K)))
                .thenReturn(List.of(hit));

        List<MemoryAssetRecallService.DeepReadChunk> chunks =
                service.deepReadChunks(List.of(card(501, "课件A.pdf")), "hooks 那页", SELF);

        assertEquals(1, chunks.size());
        assertEquals(501L, chunks.get(0).memoryId());
        assertEquals("课件A.pdf", chunks.get(0).fileName(), "文件名从卡片回查");
        assertEquals("第12页", chunks.get(0).pageRef());
        assertEquals("useEffect 依赖数组规则", chunks.get(0).chunkText());
        assertEquals(0.21d, chunks.get(0).distance());
    }

    @Test
    void deepRead_longQuery_cappedBeforeEmbed() {
        when(llmGateway.embed(anyString(), anyString(), eq(SELF))).thenReturn(new float[HalfVecUtil.DIM]);
        when(chunkMapper.searchTopK(anyList(), anyString(), anyDouble(), anyInt())).thenReturn(List.of());
        String longQuery = "问".repeat(2000);

        service.deepReadChunks(List.of(card(501, "课件A.pdf")), longQuery, SELF);

        verify(llmGateway).embed(argThat(q -> q.length() == 1000), anyString(), eq(SELF));
    }

    // ===== collectFileCardsForEntries（记忆二期 P3 扩展 · 项目收录附件下载卡片）=====

    private static MemoryProjectEntryVO fileEntry(long id, String fileId, String l1) {
        return MemoryProjectEntryVO.builder()
                .id(id).projectId(10L).authorUserId(2L).authorName("张三")
                .contentType(MemoryProjectEntry.CONTENT_TYPE_FILE).fileId(fileId)
                .l1Summary(l1).l2Detail("L2").status("ACTIVE").build();
    }

    @Test
    void collectForEntries_buildsDownloadCardFromEntry() {
        when(memoryMapper.findReadyByFileIds(List.of("f-a")))
                .thenReturn(List.of(row(501, "f-a", "课件A.pdf")));
        when(storedFileMapper.selectBatchIds(List.of("f-a")))
                .thenReturn(List.of(meta("f-a", StoredFileEntity.STATUS_ACTIVE)));

        List<RecalledFileCard> cards = service.collectFileCardsForEntries(
                List.of(fileEntry(7, "f-a", "项目蒸馏L1")));

        assertEquals(1, cards.size());
        RecalledFileCard c = cards.get(0);
        assertNull(c.getMemoryId(), "项目卡片 memoryId=null（前端不展开分块）");
        assertEquals("f-a", c.getFileId());
        assertEquals("课件A.pdf", c.getOriginalName(), "文件名/类型从 memory 行回查");
        assertEquals(MemoryAssetMemory.KIND_PDF, c.getFileKind());
        assertEquals(0, c.getChunkCount(), "项目卡片不深读/不展开分块");
        assertFalse(c.getWeakMemory());
        assertTrue(c.isDownloadable(), "ACTIVE → 可下载");
        assertEquals("项目蒸馏L1", c.getL1(), "l1 取条目蒸馏（项目上下文相关），非文件原总结");
        assertEquals("L2", c.getL2());
        verifyNoInteractions(chunkMapper, llmGateway);
    }

    @Test
    void collectForEntries_skipsTextEntriesAndNullFileId() {
        // TEXT 条目 + null/blank fileId 条目被跳过 → 无 fileIds → 不查 mapper
        MemoryProjectEntryVO textEntry = MemoryProjectEntryVO.builder()
                .id(1L).contentType(MemoryProjectEntry.CONTENT_TYPE_TEXT).l1Summary("纯文本条目").build();
        MemoryProjectEntryVO nullFile = MemoryProjectEntryVO.builder()
                .id(2L).contentType(MemoryProjectEntry.CONTENT_TYPE_FILE).fileId(null).build();
        assertTrue(service.collectFileCardsForEntries(List.of(textEntry, nullFile)).isEmpty());
        assertTrue(service.collectFileCardsForEntries(List.of()).isEmpty());
        assertTrue(service.collectFileCardsForEntries(null).isEmpty());
        verifyNoInteractions(memoryMapper, storedFileMapper, chunkMapper);
    }

    @Test
    void collectForEntries_cleanedOrMissingMeta_marksNotDownloadable() {
        when(memoryMapper.findReadyByFileIds(List.of("f-clean", "f-nomem")))
                .thenReturn(List.of(row(502, "f-clean", "旧.pdf")));  // f-nomem 无 memory 行
        when(storedFileMapper.selectBatchIds(List.of("f-clean", "f-nomem")))
                .thenReturn(List.of(meta("f-clean", StoredFileEntity.STATUS_CLEANED)));  // f-nomem meta 缺失

        List<RecalledFileCard> cards = service.collectFileCardsForEntries(List.of(
                fileEntry(7, "f-clean", "L1"), fileEntry(8, "f-nomem", "L1")));

        assertTrue(cards.get(0).isFileCleaned(), "CLEANED 标删除");
        assertFalse(cards.get(0).isDownloadable());
        assertTrue(cards.get(1).isFileCleaned(), "meta 缺失标删除");
        assertFalse(cards.get(1).isDownloadable());
        assertNull(cards.get(1).getOriginalName(), "无 memory 行 → 文件名 null（前端兜底未命名）");
    }
}
