package com.superprogrammer.media.edit.dto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 把任意 {@link EditSpec} 规范化成 V2 多轨形态。无状态，全静态方法。
 *
 * <p>三处统一入口调用：{@code MediaEditTaskWorker}（渲染前）、{@code MediaEditTaskService/MediaAssetService}（提交校验）、
 * {@code MediaEditController.exportDraft}（剪映导出）。确保旧 V1 任务与新 V2 提交走同一条校验/渲染/导出路径。
 *
 * <p>需要 {@code durationResolver}（fileId → 素材时长秒）的原因：V1 {@code ClipSpec.trimEnd} 与 V2 {@code SegmentSpec.trimEnd}
 * 缺省表示「素材全长」，规范到 V2 的 {@code targetEnd} 必须是确定秒数 → 渲染期/校验期已 probe 每个素材，复用其结果。
 *
 * <ul>
 *   <li>V2 输入（{@code tracks!=null}）：{@link #fillDefaults} 补缺省（trimEnd/targetStart/targetEnd），置 schemaVersion=2。</li>
 *   <li>V1 输入：{@code clips} 首尾相接排成 VIDEO 轨；{@code audio}（单 BGM）横跨成片成一条 AUDIO 轨；{@code texts} 成 TEXT 轨。</li>
 * </ul>
 */
public final class EditSpecNormalizer {

    private EditSpecNormalizer() {}

    /**
     * 收集 spec 引用的所有 fileId（V2 tracks.segments + V1 clips/audio），去重保序。TEXT 轨无 fileId。
     * worker/validate 在 normalize 前 copy+probe 素材时用。
     */
    public static Set<String> collectFileIds(EditSpec spec) {
        Set<String> ids = new LinkedHashSet<>();
        if (spec.getTracks() != null) {
            for (EditSpec.TrackSpec t : spec.getTracks()) {
                if (t.getSegments() != null) {
                    for (EditSpec.SegmentSpec s : t.getSegments()) {
                        if (s.getFileId() != null && !s.getFileId().isBlank()) {
                            ids.add(s.getFileId());
                        }
                    }
                }
            }
        }
        if (spec.getClips() != null) {
            for (EditSpec.ClipSpec c : spec.getClips()) {
                if (c.getFileId() != null && !c.getFileId().isBlank()) {
                    ids.add(c.getFileId());
                }
            }
        }
        if (spec.getAudio() != null && spec.getAudio().getFileId() != null
                && !spec.getAudio().getFileId().isBlank()) {
            ids.add(spec.getAudio().getFileId());
        }
        return ids;
    }

    /**
     * 规范化成 V2。{@code durationResolver} 在 trimEnd/targetEnd 缺省（表示「素材全长」）时被调用。
     * @throws IllegalArgumentException edit_spec 为空、既无 tracks 又无 clips、或素材时长解析失败
     */
    public static EditSpec normalize(EditSpec raw, Function<String, Double> durationResolver) {
        if (raw == null) {
            throw new IllegalArgumentException("edit_spec 为空");
        }
        if (raw.getTracks() != null) {
            fillDefaults(raw, durationResolver);
            raw.setSchemaVersion(EditSpec.SCHEMA_V2);
            return raw;
        }
        if (raw.getClips() == null || raw.getClips().isEmpty()) {
            throw new IllegalArgumentException("edit_spec 缺少 tracks(V2) 或 clips(V1)");
        }
        return fromV1(raw, durationResolver);
    }

    /** V1 → V2：clips 首尾相接成 VIDEO 轨；audio 单 BGM 横跨成片；texts 成 TEXT 轨。 */
    private static EditSpec fromV1(EditSpec raw, Function<String, Double> durationResolver) {
        EditSpec v2 = new EditSpec();
        v2.setOutput(raw.getOutput());
        v2.setSchemaVersion(EditSpec.SCHEMA_V2);

        // VIDEO 轨（首尾相接，无间隙——V1 语义就是顺序拼接）
        List<EditSpec.SegmentSpec> vsegs = new ArrayList<>();
        double cursor = 0;
        for (EditSpec.ClipSpec c : raw.getClips()) {
            EditSpec.SegmentSpec s = new EditSpec.SegmentSpec();
            s.setFileId(c.getFileId());
            s.setSourceType(c.getSourceType());
            double ts = c.getTrimStart() != null ? c.getTrimStart() : 0;
            double full = durationOrThrow(durationResolver, c.getFileId());
            double te = c.getTrimEnd() != null ? c.getTrimEnd() : full;
            s.setTrimStart(ts);
            s.setTrimEnd(te);
            double dur = Math.max(0, te - ts);
            s.setTargetStart(cursor);
            s.setTargetEnd(cursor + dur);
            cursor += dur;
            vsegs.add(s);
        }
        double timelineEnd = cursor;

        EditSpec.TrackSpec vt = new EditSpec.TrackSpec();
        vt.setType(EditSpec.TrackType.VIDEO.name());
        vt.setSegments(vsegs);
        List<EditSpec.TrackSpec> tracks = new ArrayList<>();
        tracks.add(vt);

        // AUDIO 轨：V1 单 BGM → 一条覆盖整条成片的 AUDIO 轨
        if (raw.getAudio() != null && notBlank(raw.getAudio().getFileId())) {
            double bgmFull = durationOrThrow(durationResolver, raw.getAudio().getFileId());
            EditSpec.SegmentSpec a = new EditSpec.SegmentSpec();
            a.setFileId(raw.getAudio().getFileId());
            a.setTrimStart(0.0);
            a.setTrimEnd(Math.min(bgmFull, timelineEnd)); // BGM 只需覆盖成片
            a.setTargetStart(0.0);
            a.setTargetEnd(timelineEnd);
            a.setVolume(raw.getAudio().getVolume());
            EditSpec.TrackSpec at = new EditSpec.TrackSpec();
            at.setType(EditSpec.TrackType.AUDIO.name());
            at.setName("BGM");
            at.setVolume(raw.getAudio().getVolume());
            at.setSegments(new ArrayList<>(List.of(a)));
            tracks.add(at);
        }

        // TEXT 轨
        if (raw.getTexts() != null && !raw.getTexts().isEmpty()) {
            EditSpec.TrackSpec tt = new EditSpec.TrackSpec();
            tt.setType(EditSpec.TrackType.TEXT.name());
            tt.setTexts(raw.getTexts().stream().map(EditSpecNormalizer::toTextSegment).toList());
            tracks.add(tt);
        }

        v2.setTracks(tracks);
        return v2;
    }

    /**
     * V2 缺省填充（无变速约束：target 占用 = source 用量）。
     *
     * <p>填充优先级（谁已明确，另一个跟随）：
     * <ul>
     *   <li>{@code targetEnd} 已明确 + {@code trimEnd} 缺省 → {@code trimEnd} 按 target 占用推导（<b>trim 跟随 target</b>，
     *       因前端加入片段时 targetEnd 是时间线意图、trimEnd 常留 null 表示"未裁"）。</li>
     *   <li>{@code trimEnd} 已明确 + {@code targetEnd} 缺省 → {@code targetEnd} 跟随 source 用量。</li>
     *   <li>两者都缺省 → 用素材全长（probe）。</li>
     * </ul>
     * {@code trimStart} 缺省→0；{@code targetStart} 缺省→VIDEO 跟随 cursor（首尾相接）/ AUDIO→0。
     */
    private static void fillDefaults(EditSpec v2, Function<String, Double> durationResolver) {
        if (v2.getTracks() == null) {
            return;
        }
        for (EditSpec.TrackSpec t : v2.getTracks()) {
            EditSpec.TrackType tt = parseType(t.getType());
            if (tt == null || tt == EditSpec.TrackType.TEXT || t.getSegments() == null) {
                continue;
            }
            double cursor = 0;
            for (EditSpec.SegmentSpec s : t.getSegments()) {
                double ts = s.getTrimStart() != null ? s.getTrimStart() : 0.0;
                s.setTrimStart(ts);
                double tgs = s.getTargetStart() != null ? s.getTargetStart()
                        : (tt == EditSpec.TrackType.VIDEO ? cursor : 0.0);
                s.setTargetStart(tgs);
                if (s.getTargetEnd() != null) {
                    if (s.getTrimEnd() == null) {
                        s.setTrimEnd(ts + Math.max(0, s.getTargetEnd() - tgs));
                    }
                } else if (s.getTrimEnd() != null) {
                    s.setTargetEnd(tgs + Math.max(0, s.getTrimEnd() - ts));
                } else {
                    double dur = Math.max(0, durationOrThrow(durationResolver, s.getFileId()) - ts);
                    s.setTrimEnd(ts + dur);
                    s.setTargetEnd(tgs + dur);
                }
                cursor = s.getTargetEnd();
            }
        }
    }

    private static EditSpec.TextSegmentSpec toTextSegment(EditSpec.TextSpec t) {
        EditSpec.TextSegmentSpec ts = new EditSpec.TextSegmentSpec();
        ts.setContent(t.getContent());
        ts.setTargetStart(t.getStart());
        ts.setTargetEnd(t.getEnd());
        ts.setPosition(t.getPosition());
        ts.setFontSize(t.getFontSize());
        return ts;
    }

    private static EditSpec.TrackType parseType(String type) {
        if (type == null) {
            return null;
        }
        try {
            return EditSpec.TrackType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static double durationOrThrow(Function<String, Double> resolver, String fileId) {
        if (notBlank(fileId)) {
            Double d = resolver.apply(fileId);
            if (d != null && d > 0) {
                return d;
            }
        }
        throw new IllegalArgumentException("无法解析素材时长（需 probe）: " + fileId);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
