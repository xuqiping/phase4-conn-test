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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * <b>降级（5x 四轮 U3 后）</b>：embed 失败/维度不符 → <b>零卡片</b>（原「卡片仍在」正是无关文件刷屏根因之一，
 * 拍板②：宁缺勿噪）；无分块文件（弱记忆）不过向量门不出卡（已接受记档）；其余异常抛给 pipeline ⑥.5 try-catch 兜。
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
    /** 5x 四轮 U3：单文件深读分块上限（窗口 per-file；防分块多的单文件垄断 top-k 挤掉其他文件）。 */
    static final int PER_FILE_TOP_K = 2;
    /** query embed 输入截断（防爆 token）。 */
    private static final int QUERY_CAP = 1000;

    private final MemoryAssetMemoryMapper memoryMapper;
    private final MemoryAssetChunkMapper chunkMapper;
    private final StoredFileMapper storedFileMapper;
    private final LlmGateway llmGateway;
    private final SystemSettingService systemSettingService;

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
     * 深读（保留旧签名入口）：query → 向量 → {@link #recallGated}，返回过门文件的分块。
     * 空 hits 短路不 embed（旧语义）；embed 失败 / 维度不符 → 返空（降级不深读）。
     */
    public List<DeepReadChunk> deepReadChunks(List<RecalledFileCard> hits, String query, Long userId) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return recallGated(hits, embedQuery(query, userId)).chunks();
    }

    /**
     * 5x 四轮 U3 · query → 向量（一次 embed 供卡片门 + 深读 + 项目卡门三处复用）。
     * 截断 QUERY_CAP；失败/维度不符返 null——调用方据此走「零卡片」降级（宁缺勿噪，
     * 已在 plan 拍板记档：embed 挂了宁可这轮不出文件卡，不出无关卡）。
     */
    public float[] embedQuery(String query, Long userId) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.length() > QUERY_CAP ? query.substring(0, QUERY_CAP) : query;
        float[] vector;
        try {
            vector = llmGateway.embed(q, null, userId);
        } catch (Exception e) {
            log.warn("文件召回 query embed 失败 userId={}: {} → 降级零文件卡", userId, e.getMessage());
            return null;
        }
        if (vector == null || vector.length != HalfVecUtil.DIM) {
            log.warn("文件召回 embed 维度不符 userId={} → 降级零文件卡", userId);
            return null;
        }
        return vector;
    }

    /**
     * 5x 四轮 U3 · 向量门控召回（核心修法）：
     * 候选卡片须有 <b>≥1 个分块</b>与 query 的 cosine 距离 ≤ 阈值
     * （memory.recall.file-card-max-distance，默认 0.5）才展示+注入——原仅标签重叠命中，
     * 无关文件刷屏（U3 主诉）。一次 searchTopK 双产出：过门卡片（按最近邻距离排序）
     * + 深读分块（per-file ≤{@link #PER_FILE_TOP_K}，总 ≤{@link #DEEP_READ_TOP_K}）。
     * <p>
     * vec null（embed 失败）→ 空卡片空分块；无分块文件（弱记忆）天然不过门（已接受记档）。
     */
    public GatedFileRecall recallGated(List<RecalledFileCard> candidates, float[] queryVec) {
        if (candidates == null || candidates.isEmpty()
                || queryVec == null || queryVec.length != HalfVecUtil.DIM) {
            return GatedFileRecall.EMPTY;
        }
        Map<Long, RecalledFileCard> byId = candidates.stream()
                .filter(c -> c.getMemoryId() != null)
                .collect(Collectors.toMap(RecalledFileCard::getMemoryId, Function.identity(), (a, b) -> a));
        if (byId.isEmpty()) {
            return GatedFileRecall.EMPTY;
        }
        double maxDistance = systemSettingService.getMemoryRecallFileCardMaxDistance();
        List<FileChunkHit> rows = chunkMapper.searchTopK(new ArrayList<>(byId.keySet()),
                HalfVecUtil.toHalfVec(queryVec), maxDistance, PER_FILE_TOP_K, DEEP_READ_TOP_K);
        if (rows.isEmpty()) {
            return GatedFileRecall.EMPTY;
        }
        Map<Long, String> names = byId.values().stream()
                .collect(Collectors.toMap(RecalledFileCard::getMemoryId,
                        c -> c.getOriginalName() == null ? "文件" : c.getOriginalName(), (a, b) -> a));
        // rows 已按 distance ASC → 首见即该文件最近邻；LinkedHashMap 保序（卡片按相关性排，最近邻文件靠前）
        Map<Long, RecalledFileCard> kept = new LinkedHashMap<>();
        List<DeepReadChunk> chunks = new ArrayList<>();
        for (FileChunkHit r : rows) {
            RecalledFileCard c = byId.get(r.getAssetMemoryId());
            if (c == null) {
                continue;
            }
            kept.putIfAbsent(r.getAssetMemoryId(), c);
            chunks.add(new DeepReadChunk(r.getAssetMemoryId(),
                    names.getOrDefault(r.getAssetMemoryId(), "文件"),
                    r.getPageRef(), r.getChunkText(),
                    r.getDistance() == null ? 0d : r.getDistance()));
        }
        return new GatedFileRecall(List.copyOf(kept.values()), List.copyOf(chunks));
    }

    /**
     * 5x 四轮 U3 · 项目附件下载卡同门：项目卡 memoryId=null（跨用户不展开分块），按 fileId 回查
     * READY memory → 分块向量判距离（per-file 1、总 ≤ 文件数，只判过阈不取文本）。
     * 无分块/无 memory 行/不相关 → 不展示（原「项目 FILE 条目恒拼恒展示」是 U3 刷屏放大器）。
     * vec null → 全部不展示（与个人卡同策略：宁缺勿噪）。
     */
    public List<RecalledFileCard> gateProjectCards(List<RecalledFileCard> projectCards, float[] queryVec) {
        if (projectCards == null || projectCards.isEmpty()
                || queryVec == null || queryVec.length != HalfVecUtil.DIM) {
            return List.of();
        }
        List<String> fileIds = projectCards.stream()
                .map(RecalledFileCard::getFileId)
                .filter(fid -> fid != null && !fid.isBlank())
                .distinct().toList();
        if (fileIds.isEmpty()) {
            return List.of();
        }
        Map<String, Long> memIdByFile = memoryMapper.findReadyByFileIds(fileIds).stream()
                .filter(m -> m.getFileId() != null && m.getId() != null)
                .collect(Collectors.toMap(MemoryAssetMemory::getFileId, MemoryAssetMemory::getId, (a, b) -> a));
        if (memIdByFile.isEmpty()) {
            return List.of();
        }
        List<Long> memIds = new ArrayList<>(new LinkedHashSet<>(memIdByFile.values()));
        List<FileChunkHit> probe = chunkMapper.searchTopK(memIds, HalfVecUtil.toHalfVec(queryVec),
                systemSettingService.getMemoryRecallFileCardMaxDistance(), 1, memIds.size());
        Set<Long> passMemIds = probe.stream()
                .map(FileChunkHit::getAssetMemoryId).filter(Objects::nonNull).collect(Collectors.toSet());
        return projectCards.stream()
                .filter(c -> memIdByFile.get(c.getFileId()) != null)
                .filter(c -> passMemIds.contains(memIdByFile.get(c.getFileId())))
                .toList();
    }

    /** 深读命中的分块（装配「文件深读」块用；distance 仅打点/debug）。 */
    public record DeepReadChunk(Long memoryId, String fileName, String pageRef, String chunkText, double distance) {
    }

    /** 5x 四轮 U3：门控后产物——过阈值的卡片 + 对应深读分块（一次 SQL 双产出）。 */
    public record GatedFileRecall(List<RecalledFileCard> cards, List<DeepReadChunk> chunks) {
        static final GatedFileRecall EMPTY = new GatedFileRecall(List.of(), List.of());
    }
}
