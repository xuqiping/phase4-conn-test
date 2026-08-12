package com.superprogrammer.media.edit.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EditSpecNormalizer} 单测：V1→V2 转换、V2 缺省填充、异常分支。
 * 用 Map 风格 durationResolver 模拟素材时长 probe（绕过真实 ffmpeg，属 Phase4 人工验证）。
 */
class EditSpecNormalizerTest {

    /** 模拟 probe：fileId → 素材全长（秒）；未知 fileId 返回 null（触发 durationOrThrow）。 */
    private static final Function<String, Double> DUR = fid -> switch (fid) {
        case "v1" -> 10.0;
        case "v2" -> 6.0;
        case "bgm" -> 30.0;
        default -> null;
    };

    @Test
    void normalize_v1_clipsBgmTexts_convertsToV2() {
        EditSpec v1 = new EditSpec();
        v1.setClips(List.of(clip("v1", 0.0, 4.0), clip("v2", 1.0, null))); // v2 无 trimEnd → 全长 6
        EditSpec.AudioSpec bgm = new EditSpec.AudioSpec();
        bgm.setFileId("bgm");
        bgm.setVolume(0.4);
        v1.setAudio(bgm);
        EditSpec.TextSpec t = new EditSpec.TextSpec();
        t.setContent("你好");
        t.setStart(1.0);
        t.setEnd(3.0);
        t.setPosition("BOTTOM");
        v1.setTexts(List.of(t));

        EditSpec v2 = EditSpecNormalizer.normalize(v1, DUR);

        assertEquals(EditSpec.SCHEMA_V2, v2.getSchemaVersion());
        assertEquals(3, v2.getTracks().size());

        // VIDEO 轨：v1[0~4] + v2[1~6] → target [0~4],[4~9]，首尾相接
        EditSpec.TrackSpec vt = v2.getTracks().get(0);
        assertEquals("VIDEO", vt.getType());
        assertEquals(2, vt.getSegments().size());
        assertEquals(0.0, vt.getSegments().get(0).getTargetStart());
        assertEquals(4.0, vt.getSegments().get(0).getTargetEnd());
        assertEquals(4.0, vt.getSegments().get(1).getTargetStart());
        assertEquals(9.0, vt.getSegments().get(1).getTargetEnd());
        assertEquals(6.0, vt.getSegments().get(1).getTrimEnd()); // 全长填充

        // AUDIO 轨：BGM 横跨成片 [0,9]，volume 0.4
        EditSpec.TrackSpec at = v2.getTracks().get(1);
        assertEquals("AUDIO", at.getType());
        assertEquals("BGM", at.getName());
        assertEquals(0.4, at.getVolume());
        assertEquals(1, at.getSegments().size());
        assertEquals(0.0, at.getSegments().get(0).getTargetStart());
        assertEquals(9.0, at.getSegments().get(0).getTargetEnd());

        // TEXT 轨
        EditSpec.TrackSpec tt = v2.getTracks().get(2);
        assertEquals("TEXT", tt.getType());
        assertEquals(1, tt.getTexts().size());
        assertEquals("你好", tt.getTexts().get(0).getContent());
        assertEquals(1.0, tt.getTexts().get(0).getTargetStart());
    }

    @Test
    void normalize_v1_noBgmNoTexts_onlyVideoTrack() {
        EditSpec v1 = new EditSpec();
        v1.setClips(List.of(clip("v1", null, null))); // 全长 10

        EditSpec v2 = EditSpecNormalizer.normalize(v1, DUR);

        assertEquals(1, v2.getTracks().size());
        assertEquals("VIDEO", v2.getTracks().get(0).getType());
        assertEquals(10.0, v2.getTracks().get(0).getSegments().get(0).getTargetEnd());
    }

    @Test
    void normalize_v2Video_fillDefaults() {
        EditSpec v2 = new EditSpec();
        EditSpec.SegmentSpec s = new EditSpec.SegmentSpec();
        s.setFileId("v1"); // trim/target 全 null
        EditSpec.TrackSpec vt = new EditSpec.TrackSpec();
        vt.setType("VIDEO");
        vt.setSegments(new ArrayList<>(List.of(s)));
        v2.setTracks(new ArrayList<>(List.of(vt)));

        EditSpec out = EditSpecNormalizer.normalize(v2, DUR);

        assertEquals(EditSpec.SCHEMA_V2, out.getSchemaVersion());
        EditSpec.SegmentSpec filled = out.getTracks().get(0).getSegments().get(0);
        assertEquals(0.0, filled.getTrimStart());   // 缺省 0
        assertEquals(10.0, filled.getTrimEnd());    // 全长
        assertEquals(0.0, filled.getTargetStart()); // 首段 cursor=0
        assertEquals(10.0, filled.getTargetEnd());  // targetStart+dur
    }

    @Test
    void normalize_v2Audio_targetStartDefaultsToZeroNotCursor() {
        EditSpec v2 = new EditSpec();
        EditSpec.SegmentSpec s = new EditSpec.SegmentSpec();
        s.setFileId("bgm");
        EditSpec.TrackSpec at = new EditSpec.TrackSpec();
        at.setType("AUDIO");
        at.setSegments(new ArrayList<>(List.of(s)));
        v2.setTracks(new ArrayList<>(List.of(at)));

        EditSpec out = EditSpecNormalizer.normalize(v2, DUR);
        EditSpec.SegmentSpec filled = out.getTracks().get(0).getSegments().get(0);
        assertEquals(0.0, filled.getTargetStart()); // AUDIO 缺省 0，不跟随 cursor
        assertEquals(30.0, filled.getTrimEnd());
    }

    @Test
    void normalize_v2TrimEndNull_targetEndGiven_trimFollowsTarget() {
        // 模拟前端加入片段：trimStart/trimEnd=null（未裁），targetEnd=前端估计值（如 5）。
        // 实际素材 v1=10s，但前端只估了 5s。trim 应跟随 target 占用（5），而非 probe 整片（10），
        // 否则 |target占用 - trim用量| = |5-10| > 0.5 触发 400「片段时长与目标时长不一致」。
        EditSpec v2 = new EditSpec();
        EditSpec.SegmentSpec s = new EditSpec.SegmentSpec();
        s.setFileId("v1");
        s.setTargetStart(0.0);
        s.setTargetEnd(5.0); // trimStart/trimEnd 留 null
        EditSpec.TrackSpec vt = new EditSpec.TrackSpec();
        vt.setType("VIDEO");
        vt.setSegments(new ArrayList<>(List.of(s)));
        v2.setTracks(new ArrayList<>(List.of(vt)));

        EditSpec out = EditSpecNormalizer.normalize(v2, DUR);
        EditSpec.SegmentSpec f = out.getTracks().get(0).getSegments().get(0);
        assertEquals(0.0, f.getTrimStart());
        assertEquals(5.0, f.getTrimEnd()); // 跟随 target 占用，不是 probe 的 10
        assertEquals(5.0, f.getTargetEnd());
        assertEquals(f.getTrimEnd() - f.getTrimStart(), f.getTargetEnd() - f.getTargetStart());
    }

    @Test
    void normalize_emptySpec_throws() {
        EditSpec empty = new EditSpec(); // 无 tracks 无 clips
        assertThrows(IllegalArgumentException.class, () -> EditSpecNormalizer.normalize(empty, DUR));
    }

    @Test
    void normalize_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> EditSpecNormalizer.normalize(null, DUR));
    }

    @Test
    void normalize_durationUnresolvable_throws() {
        EditSpec v1 = new EditSpec();
        v1.setClips(List.of(clip("unknown", null, null))); // 未知 fileId → resolver null
        assertThrows(IllegalArgumentException.class, () -> EditSpecNormalizer.normalize(v1, DUR));
    }

    private EditSpec.ClipSpec clip(String fileId, Double trimStart, Double trimEnd) {
        EditSpec.ClipSpec c = new EditSpec.ClipSpec();
        c.setFileId(fileId);
        c.setSourceType(EditSpec.SOURCE_UPLOAD);
        c.setTrimStart(trimStart);
        c.setTrimEnd(trimEnd);
        return c;
    }
}
