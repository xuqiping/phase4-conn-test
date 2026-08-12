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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 记忆二期 P3 · Step 3（FR-203）· 文件记忆召回 + 深读。
 * <p>
 * <b>命中</b>：{@link #collectFileCards} —— 本人 READY 文件记忆按 tag_ids ∩ ③选中标签重叠命中
 * （文件 tag 归一进个人标签库，②③ 标签流天然复用，无需独立选择器）；批次查 chunk 计数 +
 * stored_files 状态（CLEANED/行缺失 → 「原文件已删除」标记，总结仍可召回）。
 * <p>
 * <b>深读</b>：{@link #deepReadChunks} —— query embed 后在命中记忆的分块里取 cosine top-5 带 page_ref。
 * <b>深读门控（偏离 plan 记录）</b>：plan 原文「reflect 判需深读 → chunks top-5」；实现复用
 * ③ 标签选择的相关性判定（tag 命中 = LLM 已判相关）+ 向量相似度阈值 {@link #MAX_DISTANCE}
 * 过滤分块噪声（无分块过阈值 = 等效 reflect 不通过不深读），<b>不新增 reflect LLM 调用</b>，
 * 守 pipeline「最多 2 次 LLM」预算（embed 不计 chat LLM）。
 * <p>
 * <b>降级</b>：embed 失败/维度不符 → 不深读返空（卡片仍在）；其余异常抛给 pipeline ⑥.5 try-catch 兜。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryAssetRecallService {

    /** 单次召回文件记忆命中上限（卡片块防爆 token）。 */
    static final int MAX_FILE_HITS = 10;
    /** 深读分块 top-k（plan 坑表⑥：≤5）。 */
    static final int DEEP_READ_TOP_K = 5;
    /** 深读 cosine 距离阈值（越小越相关；≤0.5 才注入，无关提问不触发深读）。 */
    static final double MAX_DISTANCE = 0.5d;
    /** query embed 输入截断（防爆 token）。 */
    private static final int QUERY_CAP = 1000;

    private final MemoryAssetMemoryMapper memoryMapper;
    private final MemoryAssetChunkMapper chunkMapper;
    private final StoredFileMapper storedFileMapper;
    private final LlmGateway llmGateway;

    /**
     * 命中本人 READY 文件记忆 → 文件卡片（含 chunk 计数 + 原文件存续标记）。
     *
     * @param selectedTagIds ③ 选中标签 id 集（空 → 无命中）
     * @param userId         召回者（文件记忆个人域，恒只查本人）
     */
    public List<RecalledFileCard> collectFileCards(List<Long> selectedTagIds, Long userId) {
        if (selectedTagIds == null || selectedTagIds.isEmpty() || userId == null) {
            return List.of();
        }
        List<MemoryAssetMemory> rows = memoryMapper.findReadyByTagOverlap(userId, selectedTagIds, MAX_FILE_HITS);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> memoryIds = rows.stream().map(MemoryAssetMemory::getId).filter(Objects::nonNull).toList();
        Map<Long, Long> chunkCounts = chunkMapper.countByMemoryIds(memoryIds).stream()
                .collect(Collectors.toMap(AssetChunkCount::getAssetMemoryId, AssetChunkCount::getCnt, (a, b) -> a));
        List<String> fileIds = rows.stream().map(MemoryAssetMemory::getFileId)
                .filter(Objects::nonNull).distinct().toList();
        Map<String, StoredFileEntity> metas = fileIds.isEmpty() ? Map.of()
                : storedFileMapper.selectBatchIds(fileIds).stream()
                        .collect(Collectors.toMap(StoredFileEntity::getFileId, Function.identity(), (a, b) -> a));
        return rows.stream().map(row -> {
            StoredFileEntity meta = row.getFileId() == null ? null : metas.get(row.getFileId());
            boolean cleaned = meta == null || !StoredFileEntity.STATUS_ACTIVE.equals(meta.getStatus());
            return RecalledFileCard.builder()
                    .memoryId(row.getId())
                    .fileId(row.getFileId())
                    .originalName(row.getOriginalName())
                    .fileKind(row.getFileKind())
                    .chunkCount(chunkCounts.getOrDefault(row.getId(), 0L).intValue())
                    .weakMemory(row.getWeakMemory())
                    .fileCleaned(cleaned)
                    .downloadable(!cleaned)
                    .l1(row.getL1Summary())
                    .l2(row.getL2Detail())
                    .build();
        }).toList();
    }

    /**
     * 项目收录的附件（FILE 条目）→ 下载卡片（记忆二期 P3 扩展：项目上下文召回的课件须可下载）。
     * <p>
     * 与 {@link #collectFileCards} 个人文件卡片的差异：
     * <ul>
     *   <li><b>跨用户</b>：按 file_id 回查 memory_asset_memories 元数据（非 owner），下载鉴权走
     *       {@code MemoryFileEntryAccessGrantor}「成员可读」咽喉——只取文件名/类型，不分块、不深读
     *       （分块浏览仅作者本人 owner 域）。</li>
     *   <li><b>memoryId=null</b>：卡片不挂个人记忆 id → 前端不渲染「展开分块」（{@code listAttachmentChunks}
     *       是 owner-only），仅下载按钮生效。</li>
     *   <li><b>l1/l2 取条目蒸馏</b>：用项目条目的 L1/L2（项目上下文相关），非文件原总结。</li>
     * </ul>
     * 调用方须先过读权（条目本身经 {@code collectActiveEntries} 已限 ACTIVE 成员项目）。
     *
     * @param entries 召回装配的项目条目（取 contentType=FILE 者）
     * @return 项目附件下载卡片（memoryId=null、chunkCount=0）
     */
    public List<RecalledFileCard> collectFileCardsForEntries(List<MemoryProjectEntryVO> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<String> fileIds = entries.stream()
                .filter(e -> MemoryProjectEntry.CONTENT_TYPE_FILE.equals(e.getContentType()))
                .map(MemoryProjectEntryVO::getFileId)
                .filter(s -> s != null && !s.isBlank())
                .distinct().toList();
        if (fileIds.isEmpty()) {
            return List.of();
        }
        Map<String, MemoryAssetMemory> memByFile = memoryMapper.findReadyByFileIds(fileIds).stream()
                .filter(m -> m.getFileId() != null)
                .collect(Collectors.toMap(MemoryAssetMemory::getFileId, m -> m, (a, b) -> a));
        Map<String, StoredFileEntity> metaByFile = storedFileMapper.selectBatchIds(fileIds).stream()
                .collect(Collectors.toMap(StoredFileEntity::getFileId, Function.identity(), (a, b) -> a));
        return entries.stream()
                .filter(e -> MemoryProjectEntry.CONTENT_TYPE_FILE.equals(e.getContentType()))
                .filter(e -> e.getFileId() != null && !e.getFileId().isBlank())
                .map(e -> {
                    String fid = e.getFileId();
                    MemoryAssetMemory mem = memByFile.get(fid);
                    StoredFileEntity meta = metaByFile.get(fid);
                    boolean cleaned = meta == null || !StoredFileEntity.STATUS_ACTIVE.equals(meta.getStatus());
                    return RecalledFileCard.builder()
                            .memoryId(null)              // 项目卡片无个人 memoryId → 前端不渲染「展开分块」（分块仅作者本人可读）
                            .fileId(fid)
                            .originalName(mem != null ? mem.getOriginalName() : null)
                            .fileKind(mem != null ? mem.getFileKind() : null)
                            .chunkCount(0)               // 项目卡片不深读/不展开分块（跨用户），仅下载
                            .weakMemory(false)
                            .fileCleaned(cleaned)
                            .downloadable(!cleaned)
                            .l1(e.getL1Summary())        // 项目蒸馏 L1（项目上下文相关）
                            .l2(e.getL2Detail())
                            .build();
                }).toList();
    }

    /**
     * 深读：query 向量在命中记忆的分块里取 top-5（带 page_ref 语义锚点）。
     * embed 失败 / 维度不符 → 返空（降级不深读，卡片块仍装配）。
     */
    public List<DeepReadChunk> deepReadChunks(List<RecalledFileCard> hits, String query, Long userId) {
        if (hits == null || hits.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        float[] vector;
        try {
            String q = query.length() > QUERY_CAP ? query.substring(0, QUERY_CAP) : query;
            vector = llmGateway.embed(q, null, userId);
        } catch (Exception e) {
            log.warn("文件深读 query embed 失败 userId={}: {} → 降级不深读", userId, e.getMessage());
            return List.of();
        }
        if (vector == null || vector.length != HalfVecUtil.DIM) {
            log.warn("文件深读 embed 维度不符 userId={} → 降级不深读", userId);
            return List.of();
        }
        List<Long> memoryIds = hits.stream().map(RecalledFileCard::getMemoryId)
                .filter(Objects::nonNull).toList();
        List<FileChunkHit> rows = chunkMapper.searchTopK(memoryIds,
                HalfVecUtil.toHalfVec(vector), MAX_DISTANCE, DEEP_READ_TOP_K);
        Map<Long, String> names = hits.stream()
                .collect(Collectors.toMap(RecalledFileCard::getMemoryId,
                        c -> c.getOriginalName() == null ? "文件" : c.getOriginalName(), (a, b) -> a));
        return rows.stream()
                .map(r -> new DeepReadChunk(r.getAssetMemoryId(),
                        names.getOrDefault(r.getAssetMemoryId(), "文件"),
                        r.getPageRef(), r.getChunkText(),
                        r.getDistance() == null ? 0d : r.getDistance()))
                .toList();
    }

    /** 深读命中的分块（装配「文件深读」块用；distance 仅打点/debug）。 */
    public record DeepReadChunk(Long memoryId, String fileName, String pageRef, String chunkText, double distance) {
    }
}
