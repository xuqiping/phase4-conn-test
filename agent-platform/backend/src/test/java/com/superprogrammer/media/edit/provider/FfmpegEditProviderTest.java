package com.superprogrammer.media.edit.provider;

import com.superprogrammer.media.edit.config.MediaEditProperties;
import com.superprogrammer.media.edit.dto.EditSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FfmpegEditProvider#buildFilter} 单测：多轨 filter_complex 字符串拼装（plan 验证清单）。
 *
 * <p>纯逻辑（不调真实 FFmpeg，后者属 Phase4 人工 smoke）。验证关键结构：间隙 color 填黑、concat 段数、
 * adelay 毫秒单位、amix normalize=0、apad 锁定 timelineEnd、drawtext fontfile + enable between。
 * 抓 label 碰撞 / 单位错误 / 拼接遗漏等。
 */
class FfmpegEditProviderTest {

    @Test
    void buildFilter_multiTrackWithGap_correctStructure() throws Exception {
        FfmpegEditProvider provider = new FfmpegEditProvider(new MediaEditProperties());
        Path workDir = Files.createTempDirectory("fft-");
        try {
            // VIDEO: seg0 target 0-2，seg1 target 3-5（间隙 2-3 填黑）；seg0 含原声
            EditSpec.SegmentSpec v0 = seg("v1", 0, 2, 0, 2);
            EditSpec.SegmentSpec v1 = seg("v2", 0, 2, 3, 5);
            List<EditSpec.SegmentSpec> vsegs = List.of(v0, v1);
            int[] vInputIdx = {0, 1};
            boolean[] vHasAudio = {true, false};
            // AUDIO: seg target 0-5
            EditSpec.SegmentSpec a0 = seg("a1", 0, 5, 0, 5);
            List<EditSpec.SegmentSpec> aSegs = List.of(a0);
            List<Integer> aInputIdx = List.of(2);
            List<Double> aTrackVol = List.of(0.5);
            // TEXT: target 1-4
            EditSpec.TextSegmentSpec tx = text("你好", 1, 4);

            String filter = provider.buildFilter(vsegs, vInputIdx, vHasAudio, null,
                    aSegs, aInputIdx, aTrackVol, List.of(tx), "/font.ttf", 1280, 720, 24, 5, workDir);

            // 间隙 color 填黑（gap=1s → d=1），尺寸/帧率对齐
            assertTrue(filter.contains("color=c=black:s=1280x720:r=24:d=1"), "间隙应用 color 填黑，参数对齐 → " + filter);
            // concat = 3 pieces（2 segs + 1 gap），无音频轨（a=0）
            assertTrue(filter.contains("concat=n=3:v=1:a=0"), "VIDEO concat 含 gap 共 3 段 → " + filter);
            // adelay 毫秒：targetStart 0 → adelay=0
            assertTrue(filter.contains("adelay=0:all=1"), "adelay 用毫秒 → " + filter);
            // amix normalize=0（2 路：原声 + 音轨）
            assertTrue(filter.contains("amix=inputs=2:duration=longest:normalize=0"), "amix normalize=0 → " + filter);
            // apad+atrim 锁定 timelineEnd=5s（不依赖 -shortest）
            assertTrue(filter.contains("apad=whole_dur=5"), "apad 锁定 timelineEnd → " + filter);
            assertTrue(filter.contains("atrim=0:5"), "atrim 截到 timelineEnd → " + filter);
            // drawtext：候选字体名（相对名、无引号——避 Windows 盘符冒号破坏 filter_complex）+ textfile 相对名 + enable between 用秒
            assertTrue(filter.contains("drawtext=fontfile=/font.ttf"), "drawtext 用字体名（无引号）→ " + filter);
            assertTrue(filter.contains("textfile=sub-0.txt"), "textfile 用相对名（非绝对路径）→ " + filter);
            assertFalse(filter.contains("textfile=" + workDir.toString()), "textfile 不应含 workDir 绝对路径（盘符冒号坑）→ " + filter);
            assertTrue(filter.contains("enable='between(t,1,4)'"), "字幕 enable between 用秒 → " + filter);
            // null 终标签
            assertTrue(filter.contains("[vout]"));
        } finally {
            try (var walk = Files.walk(workDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignore) { /* 尽力 */ }
                });
            }
        }
    }

    @Test
    void buildFilter_noAudioNoText_videoOnly() throws Exception {
        FfmpegEditProvider provider = new FfmpegEditProvider(new MediaEditProperties());
        Path workDir = Files.createTempDirectory("fft-");
        try {
            EditSpec.SegmentSpec v0 = seg("v1", 0, 3, 0, 3);
            String filter = provider.buildFilter(List.of(v0), new int[]{0}, new boolean[]{false}, null,
                    List.of(), List.of(), List.of(), List.of(), null, 1280, 720, 24, 3, workDir);

            // 无音频：无 amix / adelay / aout
            assertFalse(filter.contains("amix"), "无音频不应有 amix");
            assertFalse(filter.contains("adelay"), "无音频不应有 adelay");
            assertFalse(filter.contains("[aout]"), "无音频不应有 aout 标签");
            // 无字幕：无 drawtext
            assertFalse(filter.contains("drawtext"));
            assertTrue(filter.contains("[vout]"));
        } finally {
            try (var walk = Files.walk(workDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignore) { /* 尽力 */ }
                });
            }
        }
    }

    private EditSpec.SegmentSpec seg(String fileId, double trimStart, double trimEnd, double targetStart, double targetEnd) {
        EditSpec.SegmentSpec s = new EditSpec.SegmentSpec();
        s.setFileId(fileId);
        s.setTrimStart(trimStart);
        s.setTrimEnd(trimEnd);
        s.setTargetStart(targetStart);
        s.setTargetEnd(targetEnd);
        return s;
    }

    private EditSpec.TextSegmentSpec text(String content, double start, double end) {
        EditSpec.TextSegmentSpec t = new EditSpec.TextSegmentSpec();
        t.setContent(content);
        t.setTargetStart(start);
        t.setTargetEnd(end);
        t.setPosition("BOTTOM");
        return t;
    }
}
