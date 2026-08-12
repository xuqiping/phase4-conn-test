package com.superprogrammer.media.edit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 剪辑意图（edit_spec JSONB 的 Java 投影）。
 *
 * <p>提交期由前端构造 → controller → service 序列化成 JSONB 存 {@code media_edit_tasks.edit_spec}；
 * 渲染期 worker 反序列化 → 经 {@link EditSpecNormalizer#normalize} 抬到 V2 → 喂 {@code FfmpegEditProvider}。
 * 同一份契约贯穿提交/校验/渲染/导出，失败可读 spec 重放重渲。
 *
 * <p><b>schemaVersion=2（多轨，当前）</b>：{@link #tracks} 为权威结构，与剪映草稿 {@code tracks[]} 同构。
 * <ul>
 *   <li>VIDEO 轨全局唯一：segments 按时间轴排布，允许间隙（渲染填黑帧），同轨禁止重叠。</li>
 *   <li>AUDIO 轨可多条：各自 segments + 音量，渲染时 adelay 对齐后 amix 混音。</li>
 *   <li>TEXT 轨：{@link TrackSpec#getTexts()} 持字幕列表（drawtext 叠加 / 剪映 text material）。</li>
 *   <li>{@link #output} 统一分辨率/fps（归一化各路一致）。</li>
 * </ul>
 *
 * <p><b>schemaVersion=1（旧单轨，向后兼容）</b>：旧任务 edit_spec 无 {@code tracks}，用顶层 {@code clips/texts/audio}
 * 字段反序列化；{@link EditSpecNormalizer} 在 worker/校验/导出入口统一转成 V2。新代码不再写 V1 字段。
 *
 * <p>段的时间语义（无变速约束：{@code targetEnd-targetStart ≈ trimEnd-trimStart}）：
 * <ul>
 *   <li>{@code trimStart/trimEnd}：source 侧裁剪（素材内秒）；缺省 0 / 素材全长。</li>
 *   <li>{@code targetStart/targetEnd}：成片时间轴定位（秒）；VIDEO 段 {@code targetStart} 缺省=同轨前段 {@code targetEnd}。</li>
 * </ul>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EditSpec {

    /** 素材来源标记：GEN=SeedDance 生成 / UPLOAD=用户上传。仅展示与归属追溯用，渲染不依赖。 */
    public static final String SOURCE_GEN = "GEN";
    public static final String SOURCE_UPLOAD = "UPLOAD";

    /** 字幕位置（drawtext 锚点）。 */
    public static final String POS_CENTER = "CENTER";
    public static final String POS_BOTTOM = "BOTTOM";

    /** schema 版本常量。 */
    public static final int SCHEMA_V1 = 1;
    public static final int SCHEMA_V2 = 2;

    /** schema 版本（null/1=旧单轨，由 Normalizer 抬到 2；2=多轨）。 */
    private Integer schemaVersion;

    // —— V2 多轨主结构 ——
    /** 有序轨道列表。VIDEO 轨全局唯一；渲染/导出按数组顺序。 */
    private List<TrackSpec> tracks;

    private OutputSpec output;

    // —— V1 兼容字段（仅旧任务反序列化用；V2 不写，Normalizer 读取后转 V2）——
    private List<ClipSpec> clips;
    private List<TextSpec> texts;
    private AudioSpec audio;

    public enum TrackType { VIDEO, AUDIO, TEXT }

    /** 轨道（V2）。VIDEO/AUDIO 轨用 {@link #segments}，TEXT 轨用 {@link #texts}。 */
    @Data
    public static class TrackSpec {
        /** {@link TrackType} 名。 */
        private String type;
        /** 可选轨名（"BGM"/"配音"/"字幕"），日志 + 剪映轨名用。 */
        private String name;
        /** AUDIO 轨默认音量 0~1；segment 级 volume 覆盖之。 */
        private Double volume;
        /** VIDEO/AUDIO 段（按时间轴顺序）。 */
        private List<SegmentSpec> segments;
        /** TEXT 轨字幕列表（仅 type=TEXT 用）。 */
        private List<TextSegmentSpec> texts;
    }

    /** 视频/音频片段（V2）。 */
    @Data
    public static class SegmentSpec {
        /** → stored_files.file_id（渲染前 worker 校验归属并 copy 到 temp）。 */
        private String fileId;
        /** GEN / UPLOAD（展示与归属用）。 */
        private String sourceType;
        /** source 侧裁剪起点（秒，nullable=0）。 */
        private Double trimStart;
        /** source 侧裁剪终点（秒，nullable=素材全长）。 */
        private Double trimEnd;
        /** 成片时间轴起点（秒；VIDEO 段缺省=同轨前段 targetEnd）。 */
        private Double targetStart;
        /** 成片时间轴终点（秒；缺省=targetStart+(trimEnd-trimStart)）。 */
        private Double targetEnd;
        /** 段级音量 0~1（AUDIO 段 + 含音频的 VIDEO 段；null=轨级 volume 或 1.0）。 */
        private Double volume;
    }

    /** 字幕段（V2 TEXT 轨元素）。 */
    @Data
    public static class TextSegmentSpec {
        /** 字幕文本（渲染走 drawtext textfile 临时文件，不进命令行/滤镜串，防注入）。 */
        private String content;
        /** 成片时间轴起点（秒）。 */
        private Double targetStart;
        /** 成片时间轴终点（秒）。 */
        private Double targetEnd;
        /** CENTER / BOTTOM，默认 BOTTOM。 */
        private String position;
        /** 字号（可选；不填按画面高度比例）。 */
        private Integer fontSize;
    }

    // ============ V1 兼容内嵌类（旧单轨；保留字段与注释，Normalizer 读取后转 V2） ============

    /** V1 片段（旧单轨，数组顺序即播放顺序）。 */
    @Data
    public static class ClipSpec {
        private String fileId;
        private String sourceType;
        /** 裁掉首尾：从第几秒开始保留（nullable=0）。 */
        private Double trimStart;
        /** 裁掉首尾：保留到第几秒（nullable=素材原长）。 */
        private Double trimEnd;
        /** 序号（冗余：前端按数组顺序排，此字段便于日志/调试）。 */
        private Integer order;
    }

    /** V1 字幕（旧单轨）。 */
    @Data
    public static class TextSpec {
        private String content;
        /** 起始时间（秒，相对成片时间轴）。 */
        private Double start;
        /** 结束时间（秒）。 */
        private Double end;
        /** CENTER / BOTTOM，默认 BOTTOM。 */
        private String position;
        /** 字号（可选；不填按画面高度比例）。 */
        private Integer fontSize;
    }

    /** V1 背景音乐（旧单轨，单 BGM）。 */
    @Data
    public static class AudioSpec {
        /** BGM stored_files.file_id（nullable=不加背景音乐）。 */
        private String fileId;
        /** 音量 0~1，默认 0.5。 */
        private Double volume;
    }

    /** 输出参数（V1/V2 共用）。 */
    @Data
    public static class OutputSpec {
        /** 输出分辨率（720p/1080p…），渲染映射成 WxH，默认 720p。 */
        private String resolution;
        /** fps，默认 24（与 SeedDance 一致）。 */
        private Integer fps;
    }
}
