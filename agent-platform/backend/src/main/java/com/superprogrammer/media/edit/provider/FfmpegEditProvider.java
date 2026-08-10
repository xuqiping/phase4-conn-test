package com.superprogrammer.media.edit.provider;

import com.superprogrammer.media.edit.config.MediaEditProperties;
import com.superprogrammer.media.edit.dto.EditSpec;
import com.superprogrammer.media.edit.dto.MediaProbe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 系统 FFmpeg 多轨渲染实现（剪辑 provider 首实现）。
 *
 * <p><b>安全（命令注入防护，plan 安全清单核心）</b>：
 * <ul>
 *   <li>FFmpeg 一律走 {@link ProcessBuilder} 参数数组（不经 shell）。</li>
 *   <li>文件一律用 worker 生成的内部 temp 路径（按 fileId 命名），不传用户输入的文件名。</li>
 *   <li>字幕文本走 {@code textfile=<temp>} 临时文件，<b>不</b>用 {@code text=<用户串>}——用户文本永不进命令行/滤镜串。</li>
 *   <li>所有滤镜数值（trim/scale/fps/volume/adelay/start-end）来自已校验的 double，非字符串拼接。</li>
 *   <li>fontfile：admin 配置字体路径拷进 per-task workDir，filter_complex 仅用相对名 {@code font.ttf}（admin 路径不进滤镜串；绝对路径盘符冒号会破坏 filtergraph 解析，故用相对名）。</li>
 * </ul>
 *
 * <p><b>多轨渲染策略（filter_complex 单遍，EditSpec 须已 normalize 到 V2）</b>：
 * <ol>
 *   <li>VIDEO 轨（唯一）：segments 按 targetStart 升序，<b>间隙用 {@code color=black} source 滤镜填黑帧</b>，
 *       与归一化段（scale+pad+setsar+fps）一起 {@code concat=v=1:a=0} 成一条连续视频流，总长 = timelineEnd。</li>
 *   <li>音频：原声（VIDEO 段自带音频，逐段独立）+ 每条 AUDIO 轨每段，各自 {@code volume} 后用
 *       {@code adelay=<targetStart*1000>:all=1}（<b>毫秒</b>）对齐到时间轴绝对起点 → 全部 {@code amix=normalize=0}
 *       （禁用自动衰减保 volume 语义），再 {@code apad+atrim} 锁定到 timelineEnd（音频与视频等长，无需 -shortest）。</li>
 *   <li>字幕：逐条 {@code drawtext}（textfile+enable=between）叠在拼接后画面；有字幕但无可用字体 → fail-fast。</li>
 *   <li>输出 H.264(yuv420p)/AAC，{@code +faststart} 便于网页边下边播。</li>
 * </ol>
 *
 * <p><b>label 分配</b>：多轨 filter_complex 标签极多，用按类型递增计数（v0/gap0/ao0/as0/vt0 + 固定 vcat/aout/vout）集中管理，
 * 杜绝 {@code No such label}。concat 输入顺序必须 = 时间轴顺序。
 *
 * <p>⚠️ 本实现需 Phase4 在装好 FFmpeg 的环境真跑验证（间隙/多音轨混音/字幕/CJK 各分支）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FfmpegEditProvider implements MediaEditProvider {

    private final MediaEditProperties properties;

    @Override
    public void render(EditSpec spec, Map<String, Path> mediaByFileId, Path output) throws Exception {
        if (spec.getTracks() == null) {
            throw new IllegalArgumentException("EditSpec 未规范化（tracks 为空），需先经 EditSpecNormalizer.normalize");
        }
        EditSpec.TrackSpec videoTrack = uniqueTrack(spec, EditSpec.TrackType.VIDEO);
        if (videoTrack == null || videoTrack.getSegments() == null || videoTrack.getSegments().isEmpty()) {
            throw new IllegalArgumentException("VIDEO 轨至少需要 1 个片段");
        }
        List<EditSpec.SegmentSpec> vsegs = sortedByTarget(videoTrack.getSegments());
        List<EditSpec.TrackSpec> audioTracks = tracksOfType(spec, EditSpec.TrackType.AUDIO);
        EditSpec.TrackSpec textTrack = firstTrack(spec, EditSpec.TrackType.TEXT);
        List<EditSpec.TextSegmentSpec> texts = (textTrack != null && textTrack.getTexts() != null)
                ? textTrack.getTexts() : List.of();

        int[] size = resolveSize(spec);
        int w = size[0], h = size[1];
        int fps = resolveFps(spec);
        // workDir = per-task temp 目录（worker 传 output=workDir.resolve("output.mp4")）。非空契约：
        // 此处 fail-fast——null 会让字体拷贝/ffmpeg.log/run() directory 各自 NPE，集中报错更清晰。
        Path workDir = Objects.requireNonNull(output.getParent(), "output 必须有父目录（workDir）");

        // 字幕字体（有字幕但无可用字体 → fail-fast，不再静默降级）
        boolean hasTexts = texts.stream().anyMatch(t -> t.getContent() != null && !t.getContent().isBlank());
        String fontPath = properties.resolveFontFile();
        if (hasTexts && fontPath == null) {
            throw new IllegalStateException("字幕渲染失败：未配 media.edit.font-file 且无可用 CJK 字体。"
                    + "ffmpeg 4.4 不收 .ttc，须 .ttf/.otf（macOS 可用 /System/Library/Fonts/Supplemental/Arial Unicode.ttf）。"
                    + "请在 application.yml 配 media.edit.font-file 或设环境变量 MEDIA_EDIT_FONT_FILE。");
        }
        String drawFont;
        // 字体复制到 workDir，filter_complex 用相对名引用：绝对路径含 Windows 盘符冒号(C:)会让
        // filtergraph 把 ':' 当 option 分隔符 → "No option name near '/Windows/...'" 解析失败。
        // 配合 run() 里 ProcessBuilder.directory(workDir) 设 CWD，相对名即可正确解析（Linux 无此问题）。
        if (hasTexts) {
            Path fontDst = workDir.resolve("font.ttf");
            Files.copy(Path.of(fontPath), fontDst, StandardCopyOption.REPLACE_EXISTING);
            drawFont = "font.ttf";
        } else {
            drawFont = null;
        }

        double timelineEnd = computeTimelineEnd(vsegs, audioTracks, texts);

        // 组装 input 段（VIDEO 段 + AUDIO 段，各 -ss/-t seek）
        List<String> cmd = new ArrayList<>();
        cmd.add(properties.getFfmpegPath());
        cmd.add("-y");
        int nextInput = 0;
        Map<String, MediaProbe> probeCache = new HashMap<>();

        // VIDEO 段 input + hasAudio 探测
        int n = vsegs.size();
        int[] vInputIdx = new int[n];
        boolean[] vHasAudio = new boolean[n];
        boolean anyOrigAudio = false;
        for (int i = 0; i < n; i++) {
            EditSpec.SegmentSpec s = vsegs.get(i);
            vInputIdx[i] = nextInput;
            appendSeekInput(cmd, s, mediaByFileId);
            vHasAudio[i] = probeCached(mediaByFileId.get(s.getFileId()), probeCache).hasAudio();
            anyOrigAudio |= vHasAudio[i];
            nextInput++;
        }

        // AUDIO 段 input（跨所有音轨，保留轨归属与轨音量）
        List<EditSpec.SegmentSpec> aSegs = new ArrayList<>();
        List<Integer> aInputIdx = new ArrayList<>();
        List<Double> aTrackVol = new ArrayList<>();
        for (EditSpec.TrackSpec at : audioTracks) {
            if (at.getSegments() == null) {
                continue;
            }
            for (EditSpec.SegmentSpec s : sortedByTarget(at.getSegments())) {
                aInputIdx.add(nextInput);
                appendSeekInput(cmd, s, mediaByFileId);
                aSegs.add(s);
                aTrackVol.add(at.getVolume());
                nextInput++;
            }
        }
        boolean audioOut = anyOrigAudio || !aSegs.isEmpty();

        // filter_complex
        cmd.add("-filter_complex");
        cmd.add(buildFilter(vsegs, vInputIdx, vHasAudio, videoTrack.getVolume(),
                aSegs, aInputIdx, aTrackVol, texts, drawFont, w, h, fps, timelineEnd, workDir));

        // 映射 + 编码（无 -shortest：音频已 apad+atrim 锁定 timelineEnd，视频 concat 也 = timelineEnd）
        cmd.add("-map");
        cmd.add("[vout]");
        if (audioOut) {
            cmd.add("-map");
            cmd.add("[aout]");
        }
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("veryfast");
        cmd.add("-crf");
        cmd.add("23");
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        if (audioOut) {
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add("128k");
        }
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(output.toString());

        Path logFile = workDir.resolve("ffmpeg.log");
        Files.deleteIfExists(logFile);
        long t0 = System.currentTimeMillis();
        run(cmd, properties.getRenderTimeoutSeconds(), logFile, workDir);
        log.info("FFmpeg 多轨渲染完成 vsegs={} audios={} texts={} W={}x{} fps={} timelineEnd={}s 用时{}ms",
                n, aSegs.size(), hasTexts ? texts.size() : 0, w, h, fps, fmt(timelineEnd),
                System.currentTimeMillis() - t0);
    }

    /** 构建 filter_complex 串（VIDEO 填黑 concat + AUDIO adelay/amix/apad + TEXT drawtext）。各语句用 ';' 连接。 */
    String buildFilter(List<EditSpec.SegmentSpec> vsegs, int[] vInputIdx, boolean[] vHasAudio, Double videoTrackVol,
                               List<EditSpec.SegmentSpec> aSegs, List<Integer> aInputIdx, List<Double> aTrackVol,
                               List<EditSpec.TextSegmentSpec> texts, String drawFont,
                               int w, int h, int fps, double timelineEnd, Path workDir) throws Exception {
        List<String> segs = new ArrayList<>();
        int vCtr = 0, gCtr = 0, aoCtr = 0, asCtr = 0, vtCtr = 0;

        // —— VIDEO 连续流：段按 targetStart 升序，间隙填黑帧，trailing 不足 timelineEnd 也补黑 ——
        List<String> pieces = new ArrayList<>();
        double cursor = 0;
        for (int i = 0; i < vsegs.size(); i++) {
            EditSpec.SegmentSpec s = vsegs.get(i);
            double tStart = s.getTargetStart() != null ? s.getTargetStart() : cursor;
            double gap = tStart - cursor;
            if (gap > 0.001) {
                String g = "gap" + (gCtr++);
                segs.add("color=c=black:s=" + w + "x" + h + ":r=" + fps + ":d=" + fmt(gap) + ",setsar=1[" + g + "]");
                pieces.add("[" + g + "]");
            }
            String v = "v" + (vCtr++);
            segs.add("[" + vInputIdx[i] + ":v]scale=" + w + ":" + h
                    + ":force_original_aspect_ratio=decrease,pad=" + w + ":" + h
                    + ":(ow-iw)/2:(oh-ih)/2:black,setsar=1,fps=" + fps + "[" + v + "]");
            pieces.add("[" + v + "]");
            cursor = s.getTargetEnd() != null ? s.getTargetEnd() : tStart;
        }
        if (timelineEnd - cursor > 0.001) {
            String g = "gap" + (gCtr++);
            segs.add("color=c=black:s=" + w + "x" + h + ":r=" + fps + ":d=" + fmt(timelineEnd - cursor) + ",setsar=1[" + g + "]");
            pieces.add("[" + g + "]");
        }
        segs.add(String.join("", pieces) + "concat=n=" + pieces.size() + ":v=1:a=0[vcat]");

        // —— 音频：原声 + 音轨，各自 volume + adelay 对齐时间轴 → amix → apad+atrim 锁定 timelineEnd ——
        List<String> astreams = new ArrayList<>();
        for (int i = 0; i < vsegs.size(); i++) {
            if (!vHasAudio[i]) {
                continue;
            }
            EditSpec.SegmentSpec s = vsegs.get(i);
            String ao = "ao" + (aoCtr++);
            double vol = resolveVolume(s.getVolume(), videoTrackVol, 1.0);
            double start = s.getTargetStart() != null ? s.getTargetStart() : 0;
            segs.add("[" + vInputIdx[i] + ":a]volume=" + fmt(vol)
                    + ",adelay=" + fmt(start * 1000) + ":all=1[" + ao + "]");
            astreams.add("[" + ao + "]");
        }
        for (int j = 0; j < aSegs.size(); j++) {
            EditSpec.SegmentSpec s = aSegs.get(j);
            String as = "as" + (asCtr++);
            double vol = resolveVolume(s.getVolume(), aTrackVol.get(j), 0.5);
            double start = s.getTargetStart() != null ? s.getTargetStart() : 0;
            segs.add("[" + aInputIdx.get(j) + ":a]volume=" + fmt(vol)
                    + ",adelay=" + fmt(start * 1000) + ":all=1[" + as + "]");
            astreams.add("[" + as + "]");
        }
        if (!astreams.isEmpty()) {
            segs.add(String.join("", astreams) + "amix=inputs=" + astreams.size()
                    + ":duration=longest:normalize=0,apad=whole_dur=" + fmt(timelineEnd)
                    + ",atrim=0:" + fmt(timelineEnd) + ",aresample=async=1[aout]");
        }

        // —— 字幕 drawtext 叠在 vcat 上 ——
        String curV = "vcat";
        if (drawFont != null) {
            int margin = Math.max(8, h / 20);
            int defaultFs = Math.max(16, h / 18);
            int ti = 0;
            for (EditSpec.TextSegmentSpec t : texts) {
                String text = t.getContent() == null ? "" : t.getContent();
                if (text.isBlank()) {
                    continue;
                }
                Path tfile = workDir.resolve("sub-" + ti + ".txt");
                Files.write(tfile, text.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                double s = t.getTargetStart() != null ? t.getTargetStart() : 0;
                double e = t.getTargetEnd() != null ? t.getTargetEnd() : 1e9;
                int fs = t.getFontSize() != null && t.getFontSize() > 0 ? t.getFontSize() : defaultFs;
                boolean bottom = !EditSpec.POS_CENTER.equalsIgnoreCase(t.getPosition());
                String x = "(w-text_w)/2";
                String y = bottom ? ("h-text_h-" + margin) : ("(h-text_h)/2");
                String next = "vt" + (vtCtr++);
                segs.add("[" + curV + "]drawtext=fontfile=" + drawFont + ":textfile=" + tfile.getFileName()
                        + ":x=" + x + ":y=" + y + ":fontcolor=white:fontsize=" + fs
                        + ":box=1:boxcolor=black@0.5:boxborderw=8"
                        + ":enable='between(t," + fmt(s) + "," + fmt(e) + ")'[" + next + "]");
                curV = next;
                ti++;
            }
        }
        segs.add("[" + curV + "]null[vout]");
        return String.join(";", segs);
    }

    // ============ 工具方法 ============

    /** 取某类型所有轨。 */
    private List<EditSpec.TrackSpec> tracksOfType(EditSpec spec, EditSpec.TrackType t) {
        List<EditSpec.TrackSpec> r = new ArrayList<>();
        if (spec.getTracks() != null) {
            for (EditSpec.TrackSpec tr : spec.getTracks()) {
                if (t.name().equalsIgnoreCase(tr.getType())) {
                    r.add(tr);
                }
            }
        }
        return r;
    }

    /** VIDEO 轨须全局唯一。 */
    private EditSpec.TrackSpec uniqueTrack(EditSpec spec, EditSpec.TrackType t) {
        List<EditSpec.TrackSpec> ts = tracksOfType(spec, t);
        if (ts.size() > 1) {
            throw new IllegalArgumentException(t + " 轨必须唯一，实际 " + ts.size());
        }
        return ts.isEmpty() ? null : ts.get(0);
    }

    private EditSpec.TrackSpec firstTrack(EditSpec spec, EditSpec.TrackType t) {
        List<EditSpec.TrackSpec> ts = tracksOfType(spec, t);
        return ts.isEmpty() ? null : ts.get(0);
    }

    /** 按 targetStart 升序（不修改原列表）。 */
    private List<EditSpec.SegmentSpec> sortedByTarget(List<EditSpec.SegmentSpec> segs) {
        List<EditSpec.SegmentSpec> r = new ArrayList<>(segs);
        r.sort(Comparator.comparingDouble(s -> s.getTargetStart() != null ? s.getTargetStart() : 0));
        return r;
    }

    /** 追加一路 input（-ss/-t seek + -i 路径）。 */
    private void appendSeekInput(List<String> cmd, EditSpec.SegmentSpec s, Map<String, Path> mediaByFileId) {
        Path p = mediaByFileId.get(s.getFileId());
        if (p == null) {
            throw new IllegalArgumentException("素材未 copy 到 temp: " + s.getFileId());
        }
        double ts = s.getTrimStart() != null ? s.getTrimStart() : 0;
        Double te = s.getTrimEnd();
        if (ts > 0) {
            cmd.add("-ss");
            cmd.add(fmt(ts));
        }
        if (te != null && te > ts) {
            cmd.add("-t");
            cmd.add(fmt(te - ts));
        }
        cmd.add("-i");
        cmd.add(p.toString());
    }

    /** probe 并按路径缓存（同 fileId 多段只 probe 一次）。 */
    private MediaProbe probeCached(Path p, Map<String, MediaProbe> cache) throws Exception {
        MediaProbe cached = cache.get(p.toString());
        if (cached != null) {
            return cached;
        }
        MediaProbe pr = probe(p);
        cache.put(p.toString(), pr);
        return pr;
    }

    private double computeTimelineEnd(List<EditSpec.SegmentSpec> vsegs,
                                      List<EditSpec.TrackSpec> audioTracks,
                                      List<EditSpec.TextSegmentSpec> texts) {
        double end = 0;
        for (EditSpec.SegmentSpec s : vsegs) {
            end = Math.max(end, s.getTargetEnd() != null ? s.getTargetEnd() : 0);
        }
        for (EditSpec.TrackSpec at : audioTracks) {
            if (at.getSegments() != null) {
                for (EditSpec.SegmentSpec s : at.getSegments()) {
                    end = Math.max(end, s.getTargetEnd() != null ? s.getTargetEnd() : 0);
                }
            }
        }
        for (EditSpec.TextSegmentSpec t : texts) {
            end = Math.max(end, t.getTargetEnd() != null ? t.getTargetEnd() : 0);
        }
        return end;
    }

    /** 段级 > 轨级 > 默认值，clamp 到 [0,1]。 */
    private double resolveVolume(Double segVol, Double trackVol, double defaultVol) {
        double v = segVol != null ? segVol : (trackVol != null ? trackVol : defaultVol);
        return v < 0 ? 0 : Math.min(v, 1);
    }

    @Override
    public double probeDurationSeconds(Path input) throws Exception {
        MediaProbe p = probe(input);
        if (p.durationSeconds() == null) {
            throw new IllegalStateException("无法解析时长（可能不是有效媒体文件）");
        }
        return p.durationSeconds();
    }

    /**
     * 用 {@code ffmpeg -i} 解析媒体信息（不依赖 ffprobe，部署只需一个二进制）。
     * {@code ffmpeg -i} 未指定输出时退出码非 0（"At least one output file must be specified"）属预期，
     * 故不断言退出码，按 stderr 文本解析 Duration / Video: WxH / Audio:。
     */
    @Override
    public MediaProbe probe(Path input) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(properties.getFfmpegPath());
        cmd.add("-hide_banner");
        cmd.add("-i");
        cmd.add(input.toString());
        Path log = Files.createTempFile("ffprobe-", ".log");
        try {
            return parseFfmpegInfo(runCapture(cmd, 30, log));
        } finally {
            Files.deleteIfExists(log);
        }
    }

    /** 解析 ffmpeg -i stderr：{@code Duration: HH:MM:SS.xx} / {@code Video: ...WxH...} / {@code Audio:}。 */
    private MediaProbe parseFfmpegInfo(String out) {
        boolean hasVideo = false, hasAudio = false;
        Integer width = null, height = null;
        Double duration = null;
        java.util.regex.Matcher dm = java.util.regex.Pattern.compile(
                "Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").matcher(out);
        if (dm.find()) {
            duration = Integer.parseInt(dm.group(1)) * 3600.0
                    + Integer.parseInt(dm.group(2)) * 60.0
                    + Double.parseDouble(dm.group(3));
        }
        for (String line : out.split("\\R")) {
            if (!hasVideo && line.contains("Video:")) {
                hasVideo = true;
                // 维度前必有逗号（如 "yuv420p, 640x360"），避免误匹配 codec tag "0x31637661"。
                java.util.regex.Matcher vm = java.util.regex.Pattern.compile(",\\s*(\\d+)x(\\d+)").matcher(line);
                if (vm.find()) {
                    width = Integer.parseInt(vm.group(1));
                    height = Integer.parseInt(vm.group(2));
                }
            }
            if (line.contains("Audio:")) {
                hasAudio = true;
            }
        }
        return new MediaProbe(hasVideo, hasAudio, width, height, duration);
    }

    /** 运行外部进程，stdout+stderr 重定向到 logFile（防 pipe 缓冲死锁）；非零退出抛异常含输出尾部。 */
    private String run(List<String> cmd, long timeoutSec, Path logFile, Path workDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());   // workDir 非空契约由 render() Objects.requireNonNull 保证
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process p = pb.start();
        boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly().waitFor();
            throw new IllegalStateException("进程超时(>" + timeoutSec + "s): " + cmd.get(0));
        }
        String out = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
        int code = p.exitValue();
        if (code != 0) {
            throw new IllegalStateException("进程退出码 " + code + ": " + tail(out, 800));
        }
        return out;
    }

    /** 同 run 但不断言退出码（probe 用：ffmpeg -i 预期非 0 退出）。 */
    private String runCapture(List<String> cmd, long timeoutSec, Path logFile) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process p = pb.start();
        boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly().waitFor();
            throw new IllegalStateException("进程超时(>" + timeoutSec + "s): " + cmd.get(0));
        }
        return Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
    }

    private int[] resolveSize(EditSpec spec) {
        String res = spec.getOutput() != null && spec.getOutput().getResolution() != null
                ? spec.getOutput().getResolution() : properties.getMaxResolution();
        switch (res) {
            case "480p": return new int[]{854, 480};
            case "1080p": return new int[]{1920, 1080};
            case "720p":
            default: return new int[]{1280, 720};
        }
    }

    private int resolveFps(EditSpec spec) {
        if (spec.getOutput() != null && spec.getOutput().getFps() != null && spec.getOutput().getFps() > 0) {
            return spec.getOutput().getFps();
        }
        return 24;
    }

    /** double → locale 无关、最多 3 位小数（trim 去尾零），避免区域逗号破坏滤镜串。 */
    private static String fmt(double d) {
        String s = String.format(Locale.ROOT, "%.3f", d);
        s = s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
        return s.isEmpty() ? "0" : s;
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(s.length() - max) : s;
    }
}
