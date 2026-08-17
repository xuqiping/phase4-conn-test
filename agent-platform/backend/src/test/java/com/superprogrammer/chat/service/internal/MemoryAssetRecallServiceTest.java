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
import com.superprogrammer.system.service.SystemSettingService;
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
 * <p>
 * 5x 四轮 U3 增补：向量门控（无分块过阈不出卡 / per-file ≤2 / 阈值取 system_settings /
 * 项目卡同门）。阈值默认桩返 {@link MemoryAssetRecallService#MAX_DISTANCE}（lenient，非门控用例不触发）。
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
    @Mock
    SystemSettingService systemSettingService;

    private MemoryAssetRecallService service;

    private static final Long SELF = 1L;

    @BeforeEach
    void setUp() {
        service = new MemoryAssetRecallService(memoryMapper, chunkMapper, storedFileMapper, llmGateway,
                systemSettingService);
        lenient().when(systemSettingService.getMemoryRecallFileCardMaxDistance())
                .thenReturn(MemoryAssetRecallService.MAX_DISTANCE);
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
        when(llmGateway.embed(anyString(), nullable(String.class), eq(SELF))).thenThrow(new RuntimeException("embed down"));
        assertTrue(service.deepReadChunks(List.of(card(501, "课件A.pdf")), "hooks 那页", SELF).isEmpty());
        verify(chunkMapper, never()).searchTopK(anyList(), anyString(), anyDouble(), anyInt(), anyInt());
    }

    @Test
    void deepRead_embedNullOrWrongDim_degradesToEmpty() {
        when(llmGateway.embed(anyString(), nullable(String.class), eq(SELF))).thenReturn(null);
        assertTrue(service.deepReadChunks(List.of(card(501, "课件A.pdf")), "问", SELF).isEmpty());

        when(llmGateway.embed(anyString(), nullable(String.class), eq(SELF))).thenReturn(new float[8]);
        assertTrue(service.deepReadChunks(List.of(card(501, "课件A.pdf")), "问", SELF).isEmpty());
        verify(chunkMapper, never()).searchTopK(anyList(), anyString(), anyDouble(), anyInt(), anyInt());
    }

    @Test
    void deepRead_happy_mapsHitsWithFileNameAndPageRef() {
        when(llmGateway.embed(eq("hooks 那页"), nullable(String.class), eq(SELF))).thenReturn(new float[HalfVecUtil.DIM]);
        FileChunkHit hit = new FileChunkHit();
        hit.setId(9001L);
        hit.setAssetMemoryId(501L);
        hit.setChunkNo(12);
        hit.setChunkText("useEffect 依赖数组规则");
        hit.setPageRef("第12页");
        hit.setDistance(0.21d);
        when(chunkMapper.searchTopK(eq(List.of(501L)), anyString(),
                eq(MemoryAssetRecallService.MAX_DISTANCE),
                eq(MemoryAssetRecallService.PER_FILE_TOP_K), eq(MemoryAssetRecallService.DEEP_READ_TOP_K)))
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
        when(llmGateway.embed(anyString(), nullable(String.class), eq(SELF))).thenReturn(new float[HalfVecUtil.DIM]);
        when(chunkMapper.searchTopK(anyList(), anyString(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of());
        String longQuery = "问".repeat(2000);

        service.deepReadChunks(List.of(card(501, "课件A.pdf")), longQuery, SELF);

        verify(llmGateway).embed(argThat(q -> q.length() == 1000), nullable(String.class), eq(SELF));
    }

    // ===== 5x 四轮 U3 · recallGated 向量门控 =====

    private static FileChunkHit hit(long memoryId, int chunkNo, String text, String pageRef, double dist) {
        FileChunkHit h = new FileChunkHit();
        h.setId(memoryId * 100 + chunkNo);
        h.setAssetMemoryId(memoryId);
        h.setChunkNo(chunkNo);
        h.setChunkText(text);
        h.setPageRef(pageRef);
        h.setDistance(dist);
        return h;
    }

    /** 门控核心：仅「≥1 分块过阈」的文件出卡；无分块文件（弱记忆）被门掉；卡片按最近邻排序。 */
    @Test
    void recallGated_onlyFilesWithPassingChunkEmitCards() {
        RecalledFileCard a = card(501, "无关B.pdf");
        RecalledFileCard b = card(502, "相关A.pdf");
        // rows 按 distance ASC：502 最近邻 0.1 → 501 不在 rows（全部分块不过阈）→ 只出 502 卡
        when(chunkMapper.searchTopK(eq(List.of(501L, 502L)), anyString(),
                eq(MemoryAssetRecallService.MAX_DISTANCE),
                eq(MemoryAssetRecallService.PER_FILE_TOP_K), eq(MemoryAssetRecallService.DEEP_READ_TOP_K)))
                .thenReturn(List.of(hit(502, 3, "useEffect 规则", "第3页", 0.1d), hit(502, 7, "依赖数组", "第7页", 0.3d)));

        MemoryAssetRecallService.GatedFileRecall r = service.recallGated(List.of(a, b), new float[HalfVecUtil.DIM]);

        assertEquals(List.of("相关A.pdf"), r.cards().stream().map(RecalledFileCard::getOriginalName).toList(),
                "仅过阈文件出卡（无关B 全部分块不过阈被门掉）");
        assertEquals(2, r.chunks().size(), "过阈文件的两块全注入（per-file ≤2 内）");
        assertEquals(502L, r.chunks().get(0).memoryId());
        assertEquals("第3页", r.chunks().get(0).pageRef());
    }

    /** vec null（embed 失败）→ 零卡片零分块（宁缺勿噪，拍板②）。 */
    @Test
    void recallGated_nullVec_returnsEmpty() {
        MemoryAssetRecallService.GatedFileRecall r = service.recallGated(List.of(card(501, "a.pdf")), null);
        assertTrue(r.cards().isEmpty());
        assertTrue(r.chunks().isEmpty());
        verifyNoInteractions(chunkMapper);
    }

    /** 无分块过阈（searchTopK 返空）→ 零卡片（U3 主修法：无关提问不出卡）。 */
    @Test
    void recallGated_noChunkPasses_zeroCards() {
        when(chunkMapper.searchTopK(anyList(), anyString(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(List.of());
        MemoryAssetRecallService.GatedFileRecall r = service.recallGated(
                List.of(card(501, "a.pdf"), card(502, "b.pdf")), new float[HalfVecUtil.DIM]);
        assertTrue(r.cards().isEmpty());
        assertTrue(r.chunks().isEmpty());
    }

    /** 阈值取 system_settings（memory.recall.file-card-max-distance）：管理员调 0.3 → SQL 收到 0.3。 */
    @Test
    void recallGated_thresholdFromSetting() {
        when(systemSettingService.getMemoryRecallFileCardMaxDistance()).thenReturn(0.3d);
        when(chunkMapper.searchTopK(anyList(), anyString(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(List.of(hit(501, 1, "t", "第1页", 0.2d)));

        service.recallGated(List.of(card(501, "a.pdf")), new float[HalfVecUtil.DIM]);

        verify(chunkMapper).searchTopK(anyList(), anyString(), eq(0.3d),
                eq(MemoryAssetRecallService.PER_FILE_TOP_K), eq(MemoryAssetRecallService.DEEP_READ_TOP_K));
    }

    /** per-file 窗口参数：searchTopK 收 perFileLimit=2 / limit=5（防单文件垄断 top-k）。 */
    @Test
    void recallGated_perFileWindowArgs() {
        when(chunkMapper.searchTopK(anyList(), anyString(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(List.of(hit(501, 1, "t", "第1页", 0.2d)));
        service.recallGated(List.of(card(501, "a.pdf")), new float[HalfVecUtil.DIM]);
        verify(chunkMapper).searchTopK(anyList(), anyString(), anyDouble(),
                eq(MemoryAssetRecallService.PER_FILE_TOP_K), eq(MemoryAssetRecallService.DEEP_READ_TOP_K));
    }

    // ===== 5x 四轮 U3 · gateProjectCards 项目附件卡同门 =====

    /** 项目卡按 fileId 回查 memory → 分块判距：过阈出卡，无 memory 行/不过阈剔除。 */
    @Test
    void gateProjectCards_filtersByChunkDistance() {
        RecalledFileCard pass = projectCard("f-relate", "相关课件.pdf");
        RecalledFileCard fail = projectCard("f-off", "无关课件.pdf");
        when(memoryMapper.findReadyByFileIds(List.of("f-relate", "f-off"))).thenReturn(List.of(
                row(501, "f-relate", "相关课件.pdf"), row(502, "f-off", "无关课件.pdf")));
        // 探针只判 501 过阈（per-file 1、总 ≤ 文件数）
        when(chunkMapper.searchTopK(eq(List.of(501L, 502L)), anyString(), anyDouble(), eq(1), eq(2)))
                .thenReturn(List.of(hit(501, 1, "t", "第1页", 0.2d)));

        List<RecalledFileCard> kept = service.gateProjectCards(List.of(pass, fail), new float[HalfVecUtil.DIM]);

        assertEquals(1, kept.size(), "仅过阈项目卡保留");
        assertEquals("f-relate", kept.get(0).getFileId());
    }

    /** 项目卡 fileId 无 READY memory 行（纯项目附件未入个人库）→ 无从判距 → 不展示。 */
    @Test
    void gateProjectCards_noMemoryRow_dropped() {
        when(memoryMapper.findReadyByFileIds(List.of("f-x"))).thenReturn(List.of());
        assertTrue(service.gateProjectCards(
                List.of(projectCard("f-x", "x.pdf")), new float[HalfVecUtil.DIM]).isEmpty());
        verifyNoInteractions(chunkMapper);
    }

    /** vec null（embed 失败）→ 项目卡全不展示（与个人卡同策略）。 */
    @Test
    void gateProjectCards_nullVec_allDropped() {
        assertTrue(service.gateProjectCards(List.of(projectCard("f-x", "x.pdf")), null).isEmpty());
        verifyNoInteractions(memoryMapper, chunkMapper);
    }

    private static RecalledFileCard projectCard(String fileId, String name) {
        return RecalledFileCard.builder()
                .memoryId(null).fileId(fileId).originalName(name)
                .fileKind("PDF").chunkCount(0).fileCleaned(false).downloadable(true).build();
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

    // ===== 5x 四轮 U8（C5）· recallAttachments 附件定向召回 =====

    /** READY 附件：卡必出（attached=true 置顶标）+ 开头分块免阈值注入（chunk_no 升序，非 query 近邻）。 */
    @Test
    void recallAttachments_ready_emitsAttachedCardAndHeadChunks() {
        when(memoryMapper.findByFileIdsOwned(List.of("f-a", "f-b"), SELF))
                .thenReturn(List.of(row(501, "f-a", "附件A.pdf"), row(502, "f-b", "附件B.pdf")));
        when(storedFileMapper.selectById(anyString()))
                .thenReturn(meta("f-a", StoredFileEntity.STATUS_ACTIVE), meta("f-b", StoredFileEntity.STATUS_ACTIVE));
        when(chunkMapper.headChunksPerFile(List.of(501L, 502L), MemoryAssetRecallService.ATTACH_TOP_K))
                .thenReturn(List.of(hit(501, 0, "附件A 开篇", "第1页", 0d), hit(502, 0, "附件B 开篇", "第1页", 0d)));

        MemoryAssetRecallService.AttachmentRecall r = service.recallAttachments(List.of("f-a", "f-b"), SELF);

        assertEquals(2, r.cards().size(), "两附件卡全出（免向量门）");
        assertTrue(r.cards().get(0).getAttached(), "attached=true → 前端「本附件」徽标+置顶");
        assertNull(r.cards().get(0).getAttachStatus(), "READY → attachStatus null");
        assertEquals(501L, r.cards().get(0).getMemoryId());
        assertEquals(0, r.cards().get(0).getChunkCount(), "附件卡不重复计块（分块已单独注入）");
        assertTrue(r.cards().get(0).isDownloadable());
        assertEquals(2, r.chunks().size(), "每附件开头块注入");
        assertEquals("附件A.pdf", r.chunks().get(0).fileName());
        assertEquals("附件A 开篇", r.chunks().get(0).chunkText());
        assertEquals(0d, r.chunks().get(0).distance(), "distance 占位 0（无相似度语义）");
        verifyNoInteractions(llmGateway, systemSettingService);
    }

    /** 非 READY（PROCESSING/FAILED）：仅出卡带 attachStatus 状态标，不注入分块。 */
    @Test
    void recallAttachments_notReady_cardWithStatusNoInjection() {
        MemoryAssetMemory processing = row(501, "f-a", "解析中.pdf");
        processing.setIngestStatus(MemoryAssetMemory.STATUS_PROCESSING);
        MemoryAssetMemory failed = row(502, "f-b", "失败.pdf");
        failed.setIngestStatus(MemoryAssetMemory.STATUS_FAILED);
        when(memoryMapper.findByFileIdsOwned(List.of("f-a", "f-b"), SELF))
                .thenReturn(List.of(processing, failed));
        when(storedFileMapper.selectById(anyString()))
                .thenReturn(meta("f-a", StoredFileEntity.STATUS_ACTIVE), meta("f-b", StoredFileEntity.STATUS_ACTIVE));

        MemoryAssetRecallService.AttachmentRecall r = service.recallAttachments(List.of("f-a", "f-b"), SELF);

        assertEquals(2, r.cards().size());
        assertEquals(MemoryAssetMemory.STATUS_PROCESSING, r.cards().get(0).getAttachStatus());
        assertEquals(MemoryAssetMemory.STATUS_FAILED, r.cards().get(1).getAttachStatus());
        assertTrue(r.cards().get(0).getAttached(), "非 READY 也带 attached 标（徽标+置顶仍生效）");
        assertTrue(r.chunks().isEmpty(), "无 READY 行 → 零注入");
        verifyNoInteractions(chunkMapper);
    }

    /** 空参 / fileId 无本人记忆行（未入库/他人文件）→ EMPTY 短路（归属双门第二道：mapper owner 过滤）。 */
    @Test
    void recallAttachments_emptyOrUnowned_shortCircuit() {
        assertTrue(service.recallAttachments(null, SELF).cards().isEmpty());
        assertTrue(service.recallAttachments(List.of(), SELF).cards().isEmpty());
        assertTrue(service.recallAttachments(List.of("f-a"), null).cards().isEmpty());
        when(memoryMapper.findByFileIdsOwned(List.of("f-a"), SELF)).thenReturn(List.of());
        MemoryAssetRecallService.AttachmentRecall r = service.recallAttachments(List.of("f-a"), SELF);
        assertTrue(r.cards().isEmpty());
        assertTrue(r.chunks().isEmpty());
        verify(chunkMapper, never()).headChunksPerFile(anyList(), anyInt());
        verify(storedFileMapper, never()).selectById(anyString());
    }
}
