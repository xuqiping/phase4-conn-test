package com.superprogrammer.media.reverse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.ContentPart;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.reverse.config.MediaReverseProperties;
import com.superprogrammer.media.reverse.dto.LocalizeRequest;
import com.superprogrammer.media.reverse.dto.LocalizeResponse;
import com.superprogrammer.media.reverse.dto.ReverseAnalyzeRequest;
import com.superprogrammer.media.reverse.dto.ReverseAnalyzeResponse;
import com.superprogrammer.media.service.MediaGenQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视频反推·关键帧提取（plan 计划6 Step1 / spec §4.1.2）。视频 → 带时间戳的代表帧文件集。
 *
 * <p><b>FFmpeg 命令模板</b>（运维排障用，plan 备注；一律 {@link ProcessBuilder} 参数数组不经 shell，
 * 文件路径来自 {@link FileStorageService#loadPath} 归属咽喉点校验后的服务器本地路径，用户输入只有已钳制的 double/int）：
 * <ol>
 *   <li>探测：{@code ffmpeg -hide_banner -i <video>} —— 不指定输出退出码非 0 属预期（同 media.edit probe 口径），
 *       解析 stderr 的 {@code Duration:} 与 {@code Video:}；</li>
 *   <li>场景扫描：{@code ffmpeg -hide_banner -i <video> -vf "select=gt(scene\,T),showinfo" -an -f null -} ——
 *       只解码打分不落帧，解析 showinfo 的 {@code pts_time:} 得切镜时间戳全集；</li>
 *   <li>取帧：{@code ffmpeg -hide_banner -y -ss <t> -i <video> -frames:v 1 -q:v 2 frame-NN.jpg} ——
 *       按选定时间戳精确取帧（input seek 快，单次 &lt;1s）。</li>
 * </ol>
 *
 * <p><b>兜底链（plan 坑表 1/2）</b>：场景命中 &lt;{@code minFrames}(4)（含解析失败 0 命中——showinfo 格式随版本漂移）
 * → 均匀采样兜底（每段中点 {@code (i+0.5)*duration/n}，避开片头黑帧）；命中 &gt;maxFrames →
 * {@link #pickEvenlySpacedIndices} 取间隔均匀子集截断（保留首尾命中，跨全片覆盖）。
 *
 * <p><b>三重预算钳制（plan 运维「容量/性能」）</b>：时长 ≤10min 拒；帧数 ≤24；缩略长边 ≤1024
 * （原始帧另存供查看，缩略版供 LLM——token 成本护栏）。
 *
 * <p><b>并发与超时</b>：信号量 {@code maxConcurrency}(2) 满则快速失败（同步接口防 FFmpeg 堆积）；
 * 每进程 {@code processTimeoutSeconds}(60) 超时 destroyForcibly 防僵尸。
 *
 * <p><b>产物落库</b>：每帧原始 JPEG + 缩略 JPEG 均走 {@link FileStorageService#storeStream}
 * （{@code stored_files} source=REVERSE，owner=发起用户；原帧无需缩略时 thumbFileId 复用 fileId 不重复存）。
 * 时间戳/镜头号元数据由返回结构携带，并编码进 originalName（{@code reverse-shot-01-12.34s.jpg}）留痕。
 *
 * <p><b>错误处理</b>（plan 安全清单「不泄漏服务器路径」）：FFmpeg/IO 失败固定话术 UNPROCESSABLE，
 * stderr 尾行只进 WARN 日志不进响应。
 */
@Slf4j
@Service
public class VideoReverseService {

    public static final String MODE_SCENE = "SCENE";
    public static final String MODE_UNIFORM = "UNIFORM";

    /** 请求 maxFrames 下限（plan 安全清单 maxFrames∈[4,24]）。 */
    private static final int MAX_FRAMES_LOWER = 4;
    /** 请求 sceneThreshold 下/上限（plan 安全清单 sceneThreshold∈[0.1,0.9]）。 */
    private static final double THRESHOLD_LOWER = 0.1;
    private static final double THRESHOLD_UPPER = 0.9;

    /** showinfo 帧时间戳（秒）。格式 4.x-6.x 稳定：{@code ... pts: 12345 pts_time:1.234 ...}。 */
    private static final Pattern PTS_TIME = Pattern.compile("pts_time:(\\d+(?:\\.\\d+)?)");
    /** {@code ffmpeg -i} 时长行：{@code Duration: HH:MM:SS.xx}。 */
    private static final Pattern DURATION = Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");

    private final MediaReverseProperties props;
    private final FileStorageService fileStorageService;
    private final LlmGateway llmGateway;
    private final MediaGenQueryService mediaGenQueryService;
    private final ObjectMapper objectMapper;
    /** 抽帧并发闸（tryAcquire 快速失败，不排队——同步接口宁拒不等）。 */
    private final Semaphore slots;

    public VideoReverseService(MediaReverseProperties props, FileStorageService fileStorageService,
                               LlmGateway llmGateway, MediaGenQueryService mediaGenQueryService,
                               ObjectMapper objectMapper) {
        this.props = props;
        this.fileStorageService = fileStorageService;
        this.llmGateway = llmGateway;
        this.mediaGenQueryService = mediaGenQueryService;
        this.objectMapper = objectMapper;
        this.slots = new Semaphore(Math.max(1, props.getMaxConcurrency()));
    }

    /** 单帧产物：原始帧 fileId（用户查看）+ 缩略帧 fileId（LLM 输入；无需缩略时两者相同）+ 时间戳 + 镜头号。 */
    public record KeyFrame(String fileId, String thumbFileId, double timestampSec, int shotNo) {}

    /** 抽帧结果：帧列表 + 源视频时长 + 模式（SCENE=场景检测 / UNIFORM=均匀采样兜底）+ 场景命中总数。 */
    public record KeyFrameResult(List<KeyFrame> frames, double durationSeconds, String mode, int sceneHits) {}

    /** 探测产物：时长秒 + 是否含视频流（用户传「改后缀假视频」被识破，同 media.edit 口径）。 */
    record ProbeResult(double durationSeconds, boolean hasVideo) {}

    /**
     * 关键帧提取主入口（plan Step1）。
     *
     * @param userId         发起用户（loadPath 归属校验：owner 或共享放行，否则 FORBIDDEN）
     * @param fileId         源视频文件 id
     * @param sceneThreshold 场景检测阈值，null=配置默认 0.3；钳制 [0.1,0.9]
     * @param maxFrames      期望帧数，null=默认 12；钳制 [4,24]
     */
    public KeyFrameResult extractKeyFrames(Long userId, String fileId, Double sceneThreshold, Integer maxFrames) {
        if (!props.isEnabled()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "视频反推功能未启用");
        }
        ExtractionArtifacts ex = runExtraction(userId, fileId, sceneThreshold, maxFrames, false);
        return new KeyFrameResult(
                ex.artifacts().stream().map(FrameArtifact::frame).toList(),
                ex.durationSeconds(), ex.mode(), ex.sceneHits());
    }

    // ============================ Step2：analyze / localize 编排 ============================

    /** script / targetLocale / notes 输入上限（plan 安全清单）。 */
    private static final int SCRIPT_MAX_LEN = 8_000;
    private static final int LOCALE_MAX_LEN = 32;
    private static final int NOTES_MAX_LEN = 500;
    /** LLM JSON 输出 token 预算（plan 坑表3：maxTokens 3000+ 精简约束）。 */
    private static final int ANALYZE_MAX_TOKENS = 4_000;

    /** 单帧中间产物：缩略字节（LLM image part 用）+ 落库元数据。 */
    record FrameArtifact(byte[] thumbBytes, KeyFrame frame) {}

    /** 抽帧中间产物全集（analyze 复用缩略字节免二次读盘）。 */
    record ExtractionArtifacts(List<FrameArtifact> artifacts, double durationSeconds, String mode, int sceneHits) {}

    /**
     * 反推分析（spec §4.1，plan Step2）：源校验→Step1 抽帧→（STORYBOARD/SCRIPT 时）
     * 帧序列按时间序组 image parts 单次调 LLM→JSON 解析（失败重试 1 次）→按 modes 装配。
     *
     * <p>计费：LLM 走 {@link LlmGateway#chat(LlmRequest, Long)} 既有 chat 链路（多模态 token），
     * {@code projectGroupId} 透传 → 组池/个人由网关分派（计划5 口径）；抽帧本身不另计费。
     */
    public ReverseAnalyzeResponse analyze(ReverseAnalyzeRequest req, Long userId) {
        return analyze(req, userId, false);
    }

    /**
     * 反推分析（admin 旁路与 media 列表同口径：admin 角色列表见全量任务 → 源校验同样放行，
     * 否则 admin 下拉里选得到的任务反推必 403，自相矛盾）。
     */
    public ReverseAnalyzeResponse analyze(ReverseAnalyzeRequest req, Long userId, boolean admin) {
        if (!props.isEnabled()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "视频反推功能未启用");
        }
        boolean wantStoryboard = false;
        boolean wantScript = false;
        boolean wantAny = false;
        if (req != null && req.getModes() != null) {
            for (String m : req.getModes()) {
                switch (m == null ? "" : m.trim().toUpperCase(Locale.ROOT)) {
                    case "STORYBOARD" -> {
                        wantAny = true;
                        wantStoryboard = true;
                    }
                    case "SCRIPT" -> {
                        wantAny = true;
                        wantScript = true;
                    }
                    case "KEYFRAMES" -> wantAny = true;
                    default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的产物类型: " + m);
                }
            }
        }
        if (!wantAny) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "modes 至少包含 KEYFRAMES/STORYBOARD/SCRIPT 之一");
        }
        String fileId = resolveSourceFileId(req == null ? null : req.getTaskId(),
                req == null ? null : req.getFileId(), userId, admin);

        ExtractionArtifacts ex = runExtraction(userId, fileId,
                req == null ? null : req.getSceneThreshold(),
                req == null ? null : req.getMaxFrames(), admin);

        List<Map<String, Object>> storyboard = null;
        Map<String, Object> script = null;
        String model = null;
        if (wantStoryboard || wantScript) {
            LlmJson out = callLlmJson(analyzeParts(ex), req.getModel(), userId,
                    req.getProjectGroupId(), "MEDIA_REVERSE_ANALYZE");
            model = out.model();
            if (wantStoryboard) {
                storyboard = toListMap(out.json().path("storyboard"));
            }
            if (wantScript) {
                script = toMap(out.json().path("script"));
            }
        }
        List<KeyFrame> frames = ex.artifacts().stream().map(FrameArtifact::frame).toList();
        log.info("media reverse analyze done: fileId={} modes={} frames={} storyboard={} script={} model={}",
                fileId, req == null ? null : req.getModes(), frames.size(),
                storyboard == null ? "-" : storyboard.size(), script == null ? "-" : "y", model);
        return new ReverseAnalyzeResponse(frames, ex.durationSeconds(), ex.mode(), ex.sceneHits(),
                storyboard, script, model);
    }

    /**
     * 本土化转绘（spec §4.2，plan Step2）：剧本→LLM 改写（镜头/场景数与顺序不变约束）→
     * localizedScript + changeLog；结构校验不一致 → warning 标注（结果仍可用，L3 联动边界）。
     */
    public LocalizeResponse localize(LocalizeRequest req, Long userId) {
        if (!props.isEnabled()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "视频反推功能未启用");
        }
        String script = req == null ? null : req.getScript();
        if (script == null || script.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "剧本不能为空");
        }
        if (script.length() > SCRIPT_MAX_LEN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "剧本长度超限（≤" + SCRIPT_MAX_LEN + "）");
        }
        String locale = req.getTargetLocale() == null ? "" : req.getTargetLocale().trim();
        if (locale.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "目标地区不能为空");
        }
        if (locale.length() > LOCALE_MAX_LEN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "目标地区超长（≤" + LOCALE_MAX_LEN + "字）");
        }
        String notes = req.getNotes() == null ? "" : req.getNotes().trim();
        if (notes.length() > NOTES_MAX_LEN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "保留要求超长（≤" + NOTES_MAX_LEN + "字）");
        }

        String instruction = """
                你是影视剧本本土化改写专家。把下面的剧本改写为「%s」文化版本，要求：
                1. 剧情、场景数量、场景顺序、每个场景的角色与台词条数完全不变；
                2. 只替换源文化专属元素（餐具/服饰/建筑/招牌文字/节庆/礼仪/饮食/称呼/地名等）为目标文化对应元素；
                3. 台词与描述自然融入目标文化语境，不逐字直译。
                严格只输出一个 JSON 对象，不要任何解释或 markdown 代码块：
                {"localizedScript":{与输入剧本同结构的改写版},"changeLog":[{"from":"源元素","to":"目标元素","scene":"第几场或场景标题"}]}
                %s剧本：
                """.formatted(locale, notes.isEmpty() ? "" : "额外保留要求：" + notes + "\n") + script;

        LlmJson out = callLlmJson(
                List.of(ContentPart.builder().type("text").text(instruction).build()),
                req.getModel(), userId, req.getProjectGroupId(), "MEDIA_REVERSE_LOCALIZE");
        JsonNode localized = out.json().path("localizedScript");
        if (!localized.isObject()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "模型未返回合法 JSON，请重试");
        }
        String warning = structureWarning(script, localized);
        List<Map<String, Object>> changeLog = toListMap(out.json().path("changeLog"));
        log.info("media reverse localize done: locale={} scriptChars={} changeLog={} warning={} model={}",
                locale, script.length(), changeLog.size(), warning != null, out.model());
        return new LocalizeResponse(localized.toString(), changeLog, warning);
    }

    /** 源解析：taskId → loadForDownload 咽喉（归属+终态校验，他人任务 403，admin 旁路）；fileId → 抽帧 loadPath 校验。 */
    private String resolveSourceFileId(Long taskId, String fileId, Long userId, boolean admin) {
        if (taskId == null && (fileId == null || fileId.isBlank())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "需提供 taskId 或 fileId 之一作为反推源");
        }
        if (taskId != null && fileId != null && !fileId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "taskId 与 fileId 只能二选一");
        }
        if (taskId != null) {
            MediaGenTask task = mediaGenQueryService.loadForDownload(taskId, userId, admin);
            return task.getResultFileId();
        }
        return fileId;
    }

    /** analyze 提示词：说明文本 part + 每帧 image part 与「第N帧 t=Xs」标注 part 交替（时间序）。 */
    private List<ContentPart> analyzeParts(ExtractionArtifacts ex) {
        List<ContentPart> parts = new ArrayList<>(1 + ex.artifacts().size() * 2);
        parts.add(ContentPart.builder().type("text").text(ANALYZE_INSTRUCTION).build());
        for (FrameArtifact a : ex.artifacts()) {
            parts.add(ContentPart.builder().type("image")
                    .data(Base64.getEncoder().encodeToString(a.thumbBytes()))
                    .mediaType("image/jpeg").build());
            parts.add(ContentPart.builder().type("text").text(
                    "第" + a.frame().shotNo() + "帧 t="
                            + String.format(Locale.ROOT, "%.2f", a.frame().timestampSec()) + "s").build());
        }
        return parts;
    }

    /** analyze 单次调用产出分镜+剧本双结构（两 mode 共用一次 LLM，省一次调用）。 */
    private static final String ANALYZE_INSTRUCTION = """
            你是影视分析专家。下面按时间顺序提供一段视频的关键帧（每张图片后紧跟一行文本标注镜头号与时间戳）。
            请分析整个视频，严格只输出一个 JSON 对象，不要任何解释或 markdown 代码块，结构如下：
            {"storyboard":[{"shotNo":1,"startSec":0.0,"endSec":5.2,"shotSize":"远景/全景/中景/近景/特写之一","cameraMove":"固定/推/拉/摇/移/跟之一","description":"画面描述，不超过80字","dialogue":"该镜头台词，无则空字符串"}],"script":{"scenes":[{"sceneHeading":"场景标题，如：内景-餐厅-白天","action":"画面与动作描述","dialogue":[{"role":"角色名","line":"台词"}]}],"synopsis":"全片一句话剧情，不超过60字"}}
            约束：storyboard 与 script 场景划分一致、按时间升序、时间戳与所给帧吻合；镜头数不超过30；所有文本用中文。
            """;

    /** LLM 调用结果（解析后的 JSON 对象 + 实际使用模型——网关可能回退管理员默认）。 */
    private record LlmJson(JsonNode json, String model) {}

    /**
     * LLM 调用 + JSON 解析（plan 坑表3）：解析失败（围栏外乱文/断尾/空）重试 1 次，两次皆败明确报错。
     * 重试是第二次真实调用（多一次计费）——比静默返回坏 JSON 可控。
     */
    private LlmJson callLlmJson(List<ContentPart> parts, String model, Long userId, Long projectGroupId,
                                 String callPurpose) {
        LlmMessage userMsg = LlmMessage.builder().role("user").content("").parts(parts).build();
        for (int attempt = 1; attempt <= 2; attempt++) {
            LlmRequest req = LlmRequest.builder()
                    .model(model)
                    .messages(List.of(userMsg))
                    .temperature(0.3)
                    .maxTokens(ANALYZE_MAX_TOKENS)
                    .stream(false)
                    .callPurpose(callPurpose)
                    .projectGroupId(projectGroupId)
                    .build();
            LlmResponse resp = llmGateway.chat(req, userId);
            String content = resp == null || resp.getContent() == null ? "" : resp.getContent();
            try {
                // 实际模型以响应为准（网关空 model 回退管理员默认）；响应缺失再回请求名
                String actualModel = resp != null && resp.getModel() != null && !resp.getModel().isBlank()
                        ? resp.getModel() : req.getModel();
                return new LlmJson(parseJsonObject(content), actualModel);
            } catch (BusinessException e) {
                log.warn("media reverse llm json parse failed: attempt={} purpose={} err={}",
                        attempt, callPurpose, e.getMessage());
            }
        }
        throw new BusinessException(ErrorCode.UNPROCESSABLE, "模型未返回合法 JSON，请重试");
    }

    /**
     * 容错 JSON 解析（承 CanvasNodeRunnerService/MemoryEntryDistiller 范式）：剥 ``` 围栏 →
     * 截首 {@code {} 尾 {@code }} → readTree；非对象/不可解析抛 UNPROCESSABLE。
     */
    private JsonNode parseJsonObject(String raw) {
        String cleaned = raw == null ? "" : raw.trim();
        if (cleaned.startsWith("```")) {
            int nl = cleaned.indexOf('\n');
            if (nl > 0) {
                cleaned = cleaned.substring(nl + 1);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();
        }
        int l = cleaned.indexOf('{');
        int r = cleaned.lastIndexOf('}');
        if (l < 0 || r <= l) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "未返回合法 JSON");
        }
        try {
            JsonNode node = objectMapper.readTree(cleaned.substring(l, r + 1));
            if (!node.isObject()) {
                throw new BusinessException(ErrorCode.UNPROCESSABLE, "未返回合法 JSON");
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "未返回合法 JSON");
        }
    }

    /** LLM 产物宽松映射：数组→List&lt;Map&gt;（非数组/缺失→空表）；对象→Map（非对象→空表）。 */
    private List<Map<String, Object>> toListMap(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item.isObject()) {
                out.add(objectMapper.convertValue(item,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            }
        }
        return out;
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    /**
     * 结构校验告警（plan 坑表8）：剧本.scenes 数与改写版对比，不一致返回告警文本（null=一致或无法比较）。
     * 用户改后剧本可能非 JSON——解析失败静默跳过比较（不阻断主流程）。
     */
    private String structureWarning(String originalScript, JsonNode localized) {
        Integer orig = sceneCountOf(originalScript);
        Integer loc = sceneCountOf(localized.toString());
        if (orig == null || loc == null || orig.equals(loc)) {
            return null;
        }
        return "场景数不一致：原 " + orig + " 现 " + loc + "，改写结果仅供参考，请人工核对";
    }

    /** 数「scenes」数组长度；输入非 JSON 对象或无 scenes → null（无法比较）。 */
    private Integer sceneCountOf(String scriptText) {
        try {
            JsonNode node = parseJsonObject(scriptText);
            JsonNode scenes = node.path("scenes");
            return scenes.isArray() ? scenes.size() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ============================ 抽帧主管线（Step1 逻辑，Step2 复用缩略字节） ============================

    /** 抽帧全管线：探测→场景扫描→兜底/截断→逐帧取帧→缩略→落库，返回含缩略字节的中间产物。 */
    private ExtractionArtifacts runExtraction(Long userId, String fileId, Double sceneThreshold, Integer maxFrames, boolean admin) {
        double threshold = clampSceneThreshold(sceneThreshold);
        int want = clampMaxFrames(maxFrames);
        Path video = fileStorageService.loadPath(fileId, userId, admin);

        if (!slots.tryAcquire()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "视频反推并发已满，请稍后重试");
        }
        Path workDir = null;
        long started = System.currentTimeMillis();
        try {
            workDir = Files.createTempDirectory("media-reverse-");
            ProbeResult probe = probeVideo(video, workDir);
            if (!probe.hasVideo()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不含视频流，无法反推");
            }
            if (probe.durationSeconds() > props.getMaxDurationSeconds()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "视频时长超限（最长 " + props.getMaxDurationSeconds() / 60 + " 分钟），请先截取片段");
            }

            // 场景扫描（解析失败/0 命中 → 空列表，走均匀兜底）
            List<Double> hits = scanScenes(video, threshold, workDir);

            String mode;
            List<Double> timestamps;
            if (hits.size() < props.getMinFrames()) {
                mode = MODE_UNIFORM;
                timestamps = uniformTimestamps(probe.durationSeconds(), want);
            } else {
                mode = MODE_SCENE;
                List<Integer> idx = pickEvenlySpacedIndices(hits.size(), want);
                timestamps = idx.stream().map(hits::get).toList();
            }

            List<FrameArtifact> artifacts = new ArrayList<>(timestamps.size());
            for (int i = 0; i < timestamps.size(); i++) {
                double t = timestamps.get(i);
                byte[] jpeg = grabFrame(video, t, i + 1, workDir);
                String originalName = String.format(Locale.ROOT, "reverse-shot-%02d-%.2fs.jpg", i + 1, t);
                String frameFileId = store(jpeg, originalName, userId);
                byte[] thumb = scaleToMaxEdge(jpeg, props.getThumbMaxEdge());
                // 原帧长边本就 ≤ 上限 → 缩略即原帧，复用 fileId 不重复落一份
                String thumbFileId = thumb == jpeg ? frameFileId : store(thumb,
                        originalName.replace(".jpg", "-thumb.jpg"), userId);
                artifacts.add(new FrameArtifact(thumb, new KeyFrame(frameFileId, thumbFileId, t, i + 1)));
            }

            log.info("media reverse keyframes: fileId={} frames={} mode={} sceneHits={} threshold={} durationSec={} costMs={}",
                    fileId, artifacts.size(), mode, hits.size(), threshold,
                    String.format(Locale.ROOT, "%.1f", probe.durationSeconds()),
                    System.currentTimeMillis() - started);
            return new ExtractionArtifacts(artifacts, probe.durationSeconds(), mode, hits.size());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("media reverse extract failed: fileId={} err={}", fileId, e.getMessage());
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "关键帧提取失败，请稍后重试");
        } finally {
            slots.release();
            cleanupWorkDir(workDir);
        }
    }

    // ============================ 钳制与选点（纯函数，单测直测） ============================

    /** sceneThreshold null→配置默认；越界钳到 [0.1,0.9]。NaN 也回退默认。 */
    public double clampSceneThreshold(Double t) {
        if (t == null || t.isNaN()) {
            return props.getSceneThreshold();
        }
        return Math.max(THRESHOLD_LOWER, Math.min(THRESHOLD_UPPER, t));
    }

    /** maxFrames null→配置默认(12)；越界钳到 [4,24]（上限取配置 maxFramesCap）。 */
    public int clampMaxFrames(Integer m) {
        int v = m == null ? props.getDefaultMaxFrames() : m;
        int cap = Math.max(MAX_FRAMES_LOWER, props.getMaxFramesCap());
        return Math.max(MAX_FRAMES_LOWER, Math.min(cap, v));
    }

    /**
     * 从 total 个命中里取 pick 个间隔均匀的下标（含首尾，跨全片覆盖）。
     * total≤pick 原样全取；否则步长 (total-1)/(pick-1) ≥1，下标严格递增无重复。
     */
    public static List<Integer> pickEvenlySpacedIndices(int total, int pick) {
        if (pick >= total) {
            List<Integer> all = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                all.add(i);
            }
            return all;
        }
        List<Integer> idx = new ArrayList<>(pick);
        for (int i = 0; i < pick; i++) {
            idx.add((int) Math.round((double) i * (total - 1) / (pick - 1)));
        }
        return idx;
    }

    /** 均匀采样时间戳：n 个分段各取中点 (i+0.5)*duration/n——避开片头/片尾黑帧，全部 < duration。 */
    public static List<Double> uniformTimestamps(double durationSeconds, int n) {
        List<Double> ts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ts.add((i + 0.5) * durationSeconds / n);
        }
        return ts;
    }

    /**
     * 长边 ≤ maxEdge 的等比缩放（plan 坑表「token 成本」护栏）。原帧不超限返回原引用（调用方据此免存重复缩略）。
     * 解码/编码失败抛 IllegalStateException（由上层统一转固定话术）。
     */
    public static byte[] scaleToMaxEdge(byte[] src, int maxEdge) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(src));
            if (img == null) {
                throw new IllegalStateException("帧图解码失败");
            }
            long edge = Math.max(img.getWidth(), img.getHeight());
            if (edge <= maxEdge) {
                return src;
            }
            double scale = (double) maxEdge / edge;
            int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
            BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(dst, "jpg", out);
            return out.toByteArray();
        } catch (IllegalStateException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("帧图缩略编码失败: " + e.getMessage());
        }
    }

    // ============================ FFmpeg 桥（包私有，单测可覆写） ============================

    /**
     * 探测时长与视频流（{@code ffmpeg -i}，退出码非 0 属预期不断言）。
     * 解析不到 Duration / 无 Video: 流 → BAD_REQUEST 固定话术。
     */
    ProbeResult probeVideo(Path video, Path workDir) {
        List<String> cmd = List.of(props.getFfmpegPath(), "-hide_banner", "-i", video.toString());
        String out;
        try {
            out = runFfmpeg(workDir, cmd, false);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "视频信息读取失败，请确认文件为有效视频");
        }
        Matcher dm = DURATION.matcher(out);
        if (!dm.find()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "无法解析视频时长，请确认文件为有效视频");
        }
        double duration = Integer.parseInt(dm.group(1)) * 3600.0
                + Integer.parseInt(dm.group(2)) * 60.0 + Double.parseDouble(dm.group(3));
        boolean hasVideo = out.contains("Video:");
        return new ProbeResult(duration, hasVideo);
    }

    /**
     * 场景检测扫描：全片解码打分不落帧，返回切镜时间戳（秒，升序）。
     * 退出码非 0 / 解析 0 命中（showinfo 格式随版本漂移）→ 空列表，调用方走均匀兜底。
     */
    List<Double> scanScenes(Path video, double threshold, Path workDir) {
        List<String> cmd = List.of(props.getFfmpegPath(), "-hide_banner", "-i", video.toString(),
                "-vf", "select=gt(scene\\," + fmt(threshold) + "),showinfo",
                "-an", "-f", "null", "-");
        String out;
        try {
            out = runFfmpeg(workDir, cmd, true);
        } catch (Exception e) {
            log.warn("media reverse scene scan failed: err={}", e.getMessage());
            return List.of();
        }
        List<Double> hits = new ArrayList<>();
        Matcher m = PTS_TIME.matcher(out);
        while (m.find()) {
            hits.add(Double.parseDouble(m.group(1)));
        }
        hits.sort(Comparator.naturalOrder());
        return hits;
    }

    /** 按时间戳取帧（input seek + 单帧输出），读回字节；失败抛 IllegalStateException 走统一话术。 */
    private byte[] grabFrame(Path video, double second, int seq, Path workDir) {
        String name = String.format(Locale.ROOT, "frame-%02d.jpg", seq);
        List<String> cmd = List.of(props.getFfmpegPath(), "-hide_banner", "-y",
                "-ss", fmt(second), "-i", video.toString(), "-frames:v", "1", "-q:v", "2", name);
        Path out = workDir.resolve(name);
        try {
            runFfmpeg(workDir, cmd, true);
            if (!Files.exists(out)) {
                throw new IllegalStateException("取帧未产出文件: " + name);
            }
            return Files.readAllBytes(out);
        } catch (Exception e) {
            throw new IllegalStateException("取帧失败 t=" + second + ": " + e.getMessage());
        }
    }

    /**
     * 运行 FFmpeg：stdout+stderr 归并写 workDir 日志文件（防管道缓冲死锁，同 media.edit 口径），
     * 进程超时 destroyForcibly；{@code assertExit} 时非 0 退出抛异常（含输出尾部，只进日志）。
     */
    String runFfmpeg(Path workDir, List<String> cmd, boolean assertExit) throws Exception {
        Path logFile = workDir.resolve("ffmpeg-" + System.nanoTime() + ".log");
        Files.deleteIfExists(logFile);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process p = pb.start();
        boolean finished = p.waitFor(props.getProcessTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly().waitFor();
            throw new IllegalStateException("FFmpeg 进程超时(>" + props.getProcessTimeoutSeconds() + "s)");
        }
        String out = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
        if (assertExit && p.exitValue() != 0) {
            throw new IllegalStateException("FFmpeg 退出码 " + p.exitValue() + ": " + tail(out, 500));
        }
        return out;
    }

    // ============================ 内部工具 ============================

    private String store(byte[] bytes, String originalName, Long userId) {
        return fileStorageService.storeStream(new ByteArrayInputStream(bytes), originalName,
                "image/jpeg", (long) bytes.length, userId, StoredFileEntity.SOURCE_REVERSE);
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.3f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String tail(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(s.length() - max) : s;
    }

    /** 递归删临时目录（抽帧中间产物；stored_files 已另存正式副本，此处清理无碍）。失败仅日志。 */
    private static void cleanupWorkDir(Path workDir) {
        if (workDir == null) {
            return;
        }
        try (var walk = Files.walk(workDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时文件删除失败不阻断（OS 重启自清）
                }
            });
        } catch (IOException e) {
            log.warn("media reverse workdir cleanup failed: {} {}", workDir, e.getMessage());
        }
    }
}
