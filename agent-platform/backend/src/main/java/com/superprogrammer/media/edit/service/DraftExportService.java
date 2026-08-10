package com.superprogrammer.media.edit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.media.edit.config.MediaEditProperties;
import com.superprogrammer.media.edit.dto.EditSpec;
import com.superprogrammer.media.edit.dto.EditSpecNormalizer;
import com.superprogrammer.media.edit.dto.MediaProbe;
import com.superprogrammer.media.edit.provider.MediaEditProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 剪映（JianYing）草稿导出：把 {@link EditSpec}（多轨）映射成剪映 {@code draft_content.json} +
 * {@code draft_meta_info.json}，连同引用素材打包成 zip 下载。
 *
 * <p><b>两阶段</b>（让 controller 在开始流式响应前能返回 4xx）：
 * <ol>
 *   <li>{@link #prepare}：经归属咽喉点 probe 所有素材（时长/宽高）+ 预检总大小 + normalize 成 V2 → {@link DraftContext}。</li>
 *   <li>{@link #streamZip}：仅 IO——把素材（可选打包）+ 两个 draft JSON 写进 zip。不应再抛业务错。</li>
 * </ol>
 *
 * <p><b>剪映 schema 关键点</b>（来源 pyJianYingDraft + 社区解析）：
 * <ul>
 *   <li>时间单位<b>微秒</b>（{@code sec * 1_000_000}）。</li>
 *   <li>字幕是 {@code type:"text"} 轨 + {@code materials.texts}（非 sticker）；{@code text_content} 是嵌套 JSON 字符串。</li>
 *   <li>{@code source_timerange.start}=trimStart（素材内入点），{@code target_timerange.start}=targetStart（时间轴位置），duration 一致。</li>
 *   <li>素材 path：bundle 模式写 {@code ./<fileId>}（草稿夹内有素材，部分版本可直接解析；缺失则用剪映「替换素材」）；absolute 模式写服务器绝对路径。</li>
 *   <li>明文草稿各版剪映均可导入（加密只影响「读取已有草稿」，不影响「打开新生成草稿」）。</li>
 * </ul>
 *
 * <p>用 {@link ObjectNode} 手搓而非全量建模剪映 DTO——schema 字段多且版本漂移，最小可用子集精确控制输出，配合 Phase4 真机剪映导入验证迭代。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftExportService {

    private final FileStorageService fileStorageService;
    private final MediaEditProvider provider;
    private final MediaEditProperties properties;
    private final ObjectMapper objectMapper;

    // ========== 阶段一：prepare（可抛业务错） ==========

    /** 归属 probe + 预检 + normalize。返回 zip 写入所需的上下文。 */
    public DraftContext prepare(EditSpec spec, Long userId, boolean admin, String draftName, boolean bundle) {
        Map<String, MediaProbe> probes = new HashMap<>();
        Map<String, StoredFileEntity> metas = new HashMap<>();
        long totalBytes = 0;
        for (String fid : EditSpecNormalizer.collectFileIds(spec)) {
            probes.put(fid, probeOwned(fid, userId, admin));
            StoredFileEntity meta = fileStorageService.findMeta(fid);
            metas.put(fid, meta);
            if (meta != null && meta.getSize() != null) {
                totalBytes += meta.getSize();
            }
        }
        if (probes.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "至少需要 1 个视频片段");
        }
        long limit = properties.getDraftMaxTotalMb() * 1024L * 1024L;
        if (totalBytes > limit) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "素材总大小超限（≤" + properties.getDraftMaxTotalMb() + "MB）");
        }
        EditSpec v2 = EditSpecNormalizer.normalize(spec, fid -> {
            MediaProbe p = probes.get(fid);
            return p != null && p.durationSeconds() != null ? p.durationSeconds() : null;
        });
        int[] canvas = resolveCanvas(v2);
        long durationUs = Math.round(computeTimelineEnd(v2) * 1_000_000L);
        return new DraftContext(v2, probes, metas, draftName, bundle, userId, admin,
                UUID.randomUUID().toString(), canvas, durationUs, Instant.now().toEpochMilli() * 1000L);
    }

    // ========== 阶段二：streamZip（仅 IO） ==========

    public void streamZip(DraftContext ctx, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            // 1. 素材（bundle 模式打包进草稿夹；absolute 模式不打包）
            if (ctx.bundle) {
                for (String fid : ctx.probes.keySet()) {
                    zip.putNextEntry(new ZipEntry(ctx.draftName + "/" + fid));
                    try (InputStream in = fileStorageService.load(fid, ctx.userId, ctx.admin).getInputStream()) {
                        in.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
            // 2. draft_content.json
            zip.putNextEntry(new ZipEntry(ctx.draftName + "/draft_content.json"));
            zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(buildDraftContent(ctx)));
            zip.closeEntry();
            // 3. draft_meta_info.json
            zip.putNextEntry(new ZipEntry(ctx.draftName + "/draft_meta_info.json"));
            zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(buildMetaInfo(ctx)));
            zip.closeEntry();
        }
        log.info("剪映草稿导出完成 draftName={} tracks={} bundle={}",
                ctx.draftName, ctx.v2.getTracks().size(), ctx.bundle);
    }

    // ========== draft_content.json 构建 ==========

    private ObjectNode buildDraftContent(DraftContext ctx) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", properties.getDraftJianyingVersion());
        root.put("draft_id", ctx.draftId);
        root.put("duration", ctx.durationUs);
        root.put("create_time", ctx.nowUs);
        root.put("update_time", ctx.nowUs);

        ObjectNode canvas = objectMapper.createObjectNode();
        canvas.put("width", ctx.canvas[0]);
        canvas.put("height", ctx.canvas[1]);
        canvas.put("ratio", "original");
        root.set("canvas_config", canvas);

        ObjectNode materials = objectMapper.createObjectNode();
        ArrayNode videos = materials.putArray("videos");
        ArrayNode audios = materials.putArray("audios");
        ArrayNode texts = materials.putArray("texts");
        for (String k : new String[]{"stickers", "images", "effects", "transitions", "filters", "fonts",
                "animations", "sound_channel_mappings", "material_animations", "material_colors",
                "masks", "pyeffects", "multi_language_texts", "video_effects", "video_transitions",
                "audios_audio_effects"}) {
            materials.putArray(k);
        }

        ArrayNode tracks = objectMapper.createArrayNode();
        Map<String, String> videoMatByFile = new HashMap<>();
        Map<String, String> audioMatByFile = new HashMap<>();
        int[] segIdx = {0};
        int[] matIdx = {0};
        int audioTrackSeq = 0;

        for (EditSpec.TrackSpec track : ctx.v2.getTracks()) {
            EditSpec.TrackType tt = parseType(track.getType());
            if (tt == null) {
                continue;
            }
            if (tt == EditSpec.TrackType.TEXT) {
                ObjectNode trk = objectMapper.createObjectNode();
                trk.put("id", "tr-text");
                trk.put("type", "text");
                ArrayNode segs = objectMapper.createArrayNode();
                if (track.getTexts() != null) {
                    for (EditSpec.TextSegmentSpec tx : track.getTexts()) {
                        if (tx.getContent() == null || tx.getContent().isBlank()) {
                            continue;
                        }
                        String matId = "mat-text-" + (matIdx[0]++);
                        texts.add(textMaterial(matId, tx));
                        double tts = num(tx.getTargetStart(), 0);
                        double tte = num(tx.getTargetEnd(), tts);
                        // 字幕无源素材：source_timerange={0,dur}，target_timerange={targetStart,dur}
                        segs.add(segment(matId, "tr-text", segIdx[0]++, 0, tte - tts, tts, tte));
                    }
                }
                trk.set("segments", segs);
                tracks.add(trk);
                continue;
            }
            boolean isVideo = tt == EditSpec.TrackType.VIDEO;
            String trkId = isVideo ? "tr-video" : ("tr-audio-" + (audioTrackSeq++));
            ObjectNode trk = objectMapper.createObjectNode();
            trk.put("id", trkId);
            trk.put("type", isVideo ? "video" : "audio");
            ArrayNode segs = objectMapper.createArrayNode();
            if (track.getSegments() != null) {
                List<EditSpec.SegmentSpec> ordered = new ArrayList<>(track.getSegments());
                ordered.sort(Comparator.comparingDouble(s -> num(s.getTargetStart(), 0)));
                for (EditSpec.SegmentSpec s : ordered) {
                    // material 按 fileId 去重：首次见则建 material 入数组，后续段复用 matId
                    String matId;
                    Map<String, String> matByFile = isVideo ? videoMatByFile : audioMatByFile;
                    matId = matByFile.get(s.getFileId());
                    if (matId == null) {
                        matId = (isVideo ? "mat-v-" : "mat-a-") + (matIdx[0]++);
                        matByFile.put(s.getFileId(), matId);
                        ArrayNode arr = isVideo ? videos : audios;
                        arr.add(isVideo ? videoMaterial(matId, s.getFileId(), ctx)
                                : audioMaterial(matId, s.getFileId(), ctx));
                    }
                    double trimStart = num(s.getTrimStart(), 0);
                    double trimEnd = num(s.getTrimEnd(), trimStart);
                    double targetStart = num(s.getTargetStart(), 0);
                    double targetEnd = num(s.getTargetEnd(), targetStart + (trimEnd - trimStart));
                    segs.add(segment(matId, trkId, segIdx[0]++, trimStart, trimEnd, targetStart, targetEnd));
                }
            }
            trk.set("segments", segs);
            tracks.add(trk);
        }

        root.set("materials", materials);
        root.set("tracks", tracks);
        return root;
    }

    private ObjectNode buildMetaInfo(DraftContext ctx) {
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("id", ctx.draftId);
        meta.put("draft_name", ctx.draftName);
        meta.put("tm_draft_create_timestamp", ctx.nowUs);
        meta.put("tm_draft_modified_timestamp", ctx.nowUs);
        meta.put("draft_enterprise_info", objectMapper.createObjectNode());
        meta.put("draft_fold_path", "");
        meta.put("duration", ctx.durationUs);
        return meta;
    }

    // ========== material / segment 节点 ==========

    private ObjectNode videoMaterial(String matId, String fileId, DraftContext ctx) {
        ObjectNode m = objectMapper.createObjectNode();
        m.put("id", matId);
        m.put("type", "video");
        m.put("material_name", materialName(fileId, ctx));
        m.put("path", mediaPath(fileId, ctx));
        MediaProbe p = ctx.probes.get(fileId);
        m.put("duration", us(p != null && p.durationSeconds() != null ? p.durationSeconds() : 0));
        m.put("width", p != null && p.width() != null ? p.width() : ctx.canvas[0]);
        m.put("height", p != null && p.height() != null ? p.height() : ctx.canvas[1]);
        return m;
    }

    private ObjectNode audioMaterial(String matId, String fileId, DraftContext ctx) {
        ObjectNode m = objectMapper.createObjectNode();
        m.put("id", matId);
        m.put("type", "audio");
        m.put("material_name", materialName(fileId, ctx));
        m.put("path", mediaPath(fileId, ctx));
        MediaProbe p = ctx.probes.get(fileId);
        m.put("duration", us(p != null && p.durationSeconds() != null ? p.durationSeconds() : 0));
        return m;
    }

    private ObjectNode textMaterial(String matId, EditSpec.TextSegmentSpec tx) {
        ObjectNode m = objectMapper.createObjectNode();
        m.put("id", matId);
        m.put("type", "text");
        m.put("text", tx.getContent());
        // text_content：嵌套 JSON 字符串 {"content":"..."}，需正确转义
        m.put("text_content", "{\"content\":" + safeJsonString(tx.getContent()) + "}");
        double dur = num(tx.getTargetEnd(), num(tx.getTargetStart(), 0)) - num(tx.getTargetStart(), 0);
        m.put("duration", us(dur));
        return m;
    }

    /** segment：source_timerange（素材内）+ target_timerange（时间轴）+ clip（占位 transform）。 */
    private ObjectNode segment(String matId, String trkId, int idx,
                               double trimStart, double trimEnd, double targetStart, double targetEnd) {
        ObjectNode seg = objectMapper.createObjectNode();
        seg.put("id", "seg-" + idx);
        seg.put("track_id", trkId);
        seg.put("material_id", matId);
        seg.set("source_timerange", timerange(trimStart, trimEnd - trimStart));
        seg.set("target_timerange", timerange(targetStart, targetEnd - targetStart));
        ObjectNode clip = objectMapper.createObjectNode();
        ObjectNode transform = objectMapper.createObjectNode();
        transform.put("x", 0);
        transform.put("y", 0);
        clip.set("transform", transform);
        ObjectNode scale = objectMapper.createObjectNode();
        scale.put("x", 1);
        scale.put("y", 1);
        clip.set("scale", scale);
        clip.put("alpha", 1);
        seg.set("clip", clip);
        return seg;
    }

    private ObjectNode timerange(double startSec, double durationSec) {
        ObjectNode t = objectMapper.createObjectNode();
        t.put("start", us(startSec));
        t.put("duration", us(durationSec));
        return t;
    }

    // ========== 辅助 ==========

    private String mediaPath(String fileId, DraftContext ctx) {
        if (ctx.bundle) {
            return "./" + fileId;
        }
        try {
            Resource resource = fileStorageService.load(fileId, ctx.userId, ctx.admin);
            return resource.getFile().getAbsolutePath();
        } catch (Exception e) {
            // 退化为相对路径（剪映可用「替换素材」指向解压文件）
            return "./" + fileId;
        }
    }

    private String materialName(String fileId, DraftContext ctx) {
        StoredFileEntity meta = ctx.metas.get(fileId);
        if (meta != null && meta.getOriginalName() != null && !meta.getOriginalName().isBlank()) {
            return meta.getOriginalName();
        }
        return fileId;
    }

    /** 经归属咽喉点 load → 直接 probe（本地 FS）；失败抛 BAD_REQUEST。 */
    private MediaProbe probeOwned(String fileId, Long userId, boolean admin) {
        Resource resource = fileStorageService.load(fileId, userId, admin);
        try {
            return provider.probe(resource.getFile().toPath());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "素材解析失败: " + rootMessage(e));
        }
    }

    private int[] resolveCanvas(EditSpec v2) {
        String res = v2.getOutput() != null && v2.getOutput().getResolution() != null
                ? v2.getOutput().getResolution() : "720p";
        switch (res) {
            case "480p": return new int[]{854, 480};
            case "1080p": return new int[]{1920, 1080};
            case "720p":
            default: return new int[]{1280, 720};
        }
    }

    private double computeTimelineEnd(EditSpec v2) {
        double end = 0;
        if (v2.getTracks() == null) {
            return end;
        }
        for (EditSpec.TrackSpec t : v2.getTracks()) {
            EditSpec.TrackType tt = parseType(t.getType());
            if (tt == EditSpec.TrackType.TEXT) {
                if (t.getTexts() != null) {
                    for (EditSpec.TextSegmentSpec tx : t.getTexts()) {
                        end = Math.max(end, num(tx.getTargetEnd(), 0));
                    }
                }
            } else if (t.getSegments() != null) {
                for (EditSpec.SegmentSpec s : t.getSegments()) {
                    end = Math.max(end, num(s.getTargetEnd(), 0));
                }
            }
        }
        return end;
    }

    private static EditSpec.TrackType parseType(String type) {
        try {
            return EditSpec.TrackType.valueOf(type == null ? "" : type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static double num(Double d, double def) {
        return d != null ? d : def;
    }

    private static long us(double sec) {
        return Math.round(sec * 1_000_000L);
    }

    /** 用 Jackson 把字符串编成合法 JSON 字符串字面量（含引号与转义），用于拼 text_content。 */
    private String safeJsonString(String s) {
        try {
            return objectMapper.writeValueAsString(s == null ? "" : s);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : (m.length() > 150 ? m.substring(0, 150) : m);
    }

    /** prepare 产出的不可变上下文，供 streamZip 消费。 */
    public static final class DraftContext {
        final EditSpec v2;
        final Map<String, MediaProbe> probes;
        final Map<String, StoredFileEntity> metas;
        final String draftName;
        final boolean bundle;
        final Long userId;
        final boolean admin;
        final String draftId;
        final int[] canvas;
        final long durationUs;
        final long nowUs;

        DraftContext(EditSpec v2, Map<String, MediaProbe> probes, Map<String, StoredFileEntity> metas,
                     String draftName, boolean bundle, Long userId, boolean admin,
                     String draftId, int[] canvas, long durationUs, long nowUs) {
            this.v2 = v2;
            this.probes = probes;
            this.metas = metas;
            this.draftName = draftName;
            this.bundle = bundle;
            this.userId = userId;
            this.admin = admin;
            this.draftId = draftId;
            this.canvas = canvas;
            this.durationUs = durationUs;
            this.nowUs = nowUs;
        }
    }
}
