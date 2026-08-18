package com.superprogrammer.media.reverse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.ContentPart;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.reverse.config.MediaReverseProperties;
import com.superprogrammer.media.reverse.dto.LocalizeRequest;
import com.superprogrammer.media.reverse.dto.LocalizeResponse;
import com.superprogrammer.media.reverse.dto.ReverseAnalyzeRequest;
import com.superprogrammer.media.reverse.dto.ReverseAnalyzeResponse;
import com.superprogrammer.media.service.MediaGenQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划6 Step1 单测：抽帧四态（正常 / 0命中兜底 / 超限截断 / 超时长拒）+ 钳制与选点纯函数 + 缩略护栏。
 * Step2 增量：analyze/localize 编排（源解析 / 单次 LLM parts / JSON 解析重试 / 结构告警 / 入参校验）。
 *
 * <p>FFmpeg 桥通过 Mockito spy 覆写：{@code probeVideo} 直接回桩值；{@code runFfmpeg} 按 cmd 分流——
 * 含 showinfo=场景扫描回 canned 输出，含 -frames:v=取帧往 workDir 落一张小 JPEG 模拟 FFmpeg 产物。
 */
class VideoReverseServiceTest {

    @TempDir
    Path tempDir;

    private FileStorageService fileStorageService;
    private LlmGateway llmGateway;
    private MediaGenQueryService mediaGenQueryService;
    private MediaReverseProperties props;
    private VideoReverseService service;
    private final AtomicInteger fileSeq = new AtomicInteger();

    /** canned 场景扫描输出（showinfo 格式，pts_time 升序由服务端再排序保证）。 */
    private String sceneOutput = "";

    @BeforeEach
    void setUp() throws Exception {
        fileStorageService = mock(FileStorageService.class);
        llmGateway = mock(LlmGateway.class);
        mediaGenQueryService = mock(MediaGenQueryService.class);
        props = new MediaReverseProperties();
        service = Mockito.spy(new VideoReverseService(props, fileStorageService,
                llmGateway, mediaGenQueryService, new ObjectMapper()));
        fileSeq.set(0);
        sceneOutput = "";

        when(fileStorageService.loadPath(anyString(), anyLong(), anyBoolean())).thenReturn(tempDir.resolve("src.mp4"));
        when(fileStorageService.storeStream(any(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> "f-" + fileSeq.incrementAndGet());

        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> cmd = inv.getArgument(1);
            if (cmd.toString().contains("showinfo")) {
                return sceneOutput;
            }
            if (cmd.contains("-frames:v")) {
                Path workDir = inv.getArgument(0);
                writeJpeg(workDir.resolve(cmd.get(cmd.size() - 1)), 8, 6);
                return "";
            }
            return "";
        }).when(service).runFfmpeg(any(), anyList(), anyBoolean());
    }

    private static void writeJpeg(Path target, int w, int h) throws IOException {
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", target.toFile());
    }

    private void stubProbe(double durationSec, boolean hasVideo) {
        doReturn(new VideoReverseService.ProbeResult(durationSec, hasVideo)).when(service)
                .probeVideo(any(), any());
    }

    private static String sceneOut(double... ts) {
        StringBuilder sb = new StringBuilder();
        for (double t : ts) {
            sb.append("[Parsed_showinfo_1 @ 0x7f] n:   0 pts: ").append((long) (t * 1000))
                    .append(" pts_time:").append(t).append(" duration:0.04\n");
        }
        return sb.toString();
    }

    // ============================ 四态 ============================

    @Test
    void extract_normalSceneHits_storesFramesSortedWithShotNo() {
        stubProbe(60, true);
        sceneOutput = sceneOut(8.2, 1.5, 55.9, 15.0, 44.1, 30.5); // 乱序输入，服务端应排序
        VideoReverseService.KeyFrameResult r = service.extractKeyFrames(7L, "src-1", null, 12);

        assertEquals(VideoReverseService.MODE_SCENE, r.mode());
        assertEquals(6, r.sceneHits());
        assertEquals(6, r.frames().size());
        for (int i = 0; i < 6; i++) {
            assertEquals(i + 1, r.frames().get(i).shotNo());
            assertNotNull(r.frames().get(i).fileId());
            // 小帧(8x6)≤1024 → 缩略即原帧，thumbFileId 复用不重复存
            assertEquals(r.frames().get(i).fileId(), r.frames().get(i).thumbFileId());
        }
        assertEquals(1.5, r.frames().get(0).timestampSec());
        assertEquals(55.9, r.frames().get(5).timestampSec());
        verify(fileStorageService, Mockito.times(6)).storeStream(any(), anyString(), anyString(), any(), any(), any());
        verify(fileStorageService).loadPath("src-1", 7L, false);
    }

    @Test
    void extract_zeroHits_fallsBackUniformSampling() {
        stubProbe(60, true);
        sceneOutput = "no matches here at all"; // 解析 0 命中（含版本漂移场景）
        VideoReverseService.KeyFrameResult r = service.extractKeyFrames(7L, "src-1", null, 4);

        assertEquals(VideoReverseService.MODE_UNIFORM, r.mode());
        assertEquals(0, r.sceneHits());
        assertEquals(4, r.frames().size());
        List<Double> expect = VideoReverseService.uniformTimestamps(60, 4);
        for (int i = 0; i < 4; i++) {
            assertEquals(expect.get(i), r.frames().get(i).timestampSec());
        }
    }

    @Test
    void extract_hitsExceedMaxFrames_truncatesEvenlyKeepingBothEnds() {
        stubProbe(60, true);
        double[] ts = new double[30];
        for (int i = 0; i < 30; i++) {
            ts[i] = (i + 1) * 1.0;
        }
        sceneOutput = sceneOut(ts);
        VideoReverseService.KeyFrameResult r = service.extractKeyFrames(7L, "src-1", null, 12);

        assertEquals(VideoReverseService.MODE_SCENE, r.mode());
        assertEquals(30, r.sceneHits());
        assertEquals(12, r.frames().size());
        // 首尾命中保留（跨全片覆盖），中间间隔均匀
        assertEquals(1.0, r.frames().get(0).timestampSec());
        assertEquals(30.0, r.frames().get(11).timestampSec());
        for (int i = 1; i < 12; i++) {
            assertTrue(r.frames().get(i).timestampSec() > r.frames().get(i - 1).timestampSec());
        }
        verify(fileStorageService, Mockito.times(12)).storeStream(any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void extract_durationOverLimit_rejectsBeforeAnyFfmpeg() throws Exception {
        stubProbe(601, true);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.extractKeyFrames(7L, "src-1", null, 12));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(service, never()).runFfmpeg(any(), anyList(), anyBoolean());
        verify(fileStorageService, never()).storeStream(any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void extract_noVideoStream_rejects() {
        stubProbe(60, false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.extractKeyFrames(7L, "src-1", null, 12));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void extract_disabled_rejects() {
        props.setEnabled(false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.extractKeyFrames(7L, "src-1", null, 12));
        assertEquals(ErrorCode.UNPROCESSABLE.getCode(), ex.getCode());
    }

    @Test
    void extract_grabFails_fixedWordingNoPathLeak() throws Exception {
        stubProbe(60, true);
        sceneOutput = sceneOut(1.5, 2.5, 4.5, 6.5);
        // 取帧阶段不落文件（模拟 FFmpeg 产出缺失）→ 统一话术
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> cmd = inv.getArgument(1);
            if (cmd.toString().contains("showinfo")) {
                return sceneOutput;
            }
            return "";
        }).when(service).runFfmpeg(any(), anyList(), anyBoolean());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.extractKeyFrames(7L, "src-1", null, 4));
        assertEquals(ErrorCode.UNPROCESSABLE.getCode(), ex.getCode());
        // 固定话术不泄漏服务器路径（plan 安全清单「错误处理」）
        assertFalse(java.util.regex.Pattern.compile("[A-Za-z]:[\\\\/]|/tmp|/var")
                .matcher(String.valueOf(ex.getMessage())).find());
    }

    // ============================ 钳制与选点纯函数 ============================

    @Test
    void clampSceneThreshold_nullOrDefaultAndBounds() {
        assertEquals(0.3, service.clampSceneThreshold(null));
        assertEquals(0.3, service.clampSceneThreshold(Double.NaN));
        assertEquals(0.1, service.clampSceneThreshold(0.05));
        assertEquals(0.9, service.clampSceneThreshold(5.0));
        assertEquals(0.5, service.clampSceneThreshold(0.5));
    }

    @Test
    void clampMaxFrames_nullOrDefaultAndBounds() {
        assertEquals(12, service.clampMaxFrames(null));
        assertEquals(4, service.clampMaxFrames(2));
        assertEquals(24, service.clampMaxFrames(99));
        assertEquals(8, service.clampMaxFrames(8));
    }

    @Test
    void pickEvenlySpacedIndices_coversBothEndsWithoutDup() {
        List<Integer> idx = VideoReverseService.pickEvenlySpacedIndices(30, 12);
        assertEquals(12, idx.size());
        assertEquals(0, idx.get(0));
        assertEquals(29, idx.get(idx.size() - 1));
        for (int i = 1; i < idx.size(); i++) {
            assertTrue(idx.get(i) > idx.get(i - 1), "严格递增: " + idx);
        }
        // total≤pick 原样全取
        assertEquals(List.of(0, 1, 2), VideoReverseService.pickEvenlySpacedIndices(3, 5));
    }

    @Test
    void uniformTimestamps_midpointsAllInsideDuration() {
        List<Double> ts = VideoReverseService.uniformTimestamps(60, 4);
        assertEquals(List.of(7.5, 22.5, 37.5, 52.5), ts);
        for (double t : ts) {
            assertTrue(t > 0 && t < 60);
        }
    }

    // ============================ 缩略护栏 ============================

    @Test
    void scaleToMaxEdge_shrinksLongEdgeKeepsRatio() throws IOException {
        BufferedImage big = new BufferedImage(2048, 1024, BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(big, "jpg", out);
        byte[] scaled = VideoReverseService.scaleToMaxEdge(out.toByteArray(), 1024);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(scaled));
        assertEquals(1024, img.getWidth());
        assertEquals(512, img.getHeight());
    }

    @Test
    void scaleToMaxEdge_smallFrameReturnsSameReference() throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB), "jpg", out);
        byte[] src = out.toByteArray();
        assertSame(src, VideoReverseService.scaleToMaxEdge(src, 1024));
    }

    @Test
    void scaleToMaxEdge_undecodableThrowsIllegalState() {
        assertThrows(IllegalStateException.class,
                () -> VideoReverseService.scaleToMaxEdge("not an image".getBytes(), 1024));
    }

    // ============================ Step2：analyze 编排 ============================

    /** 连续多次 chat 返回（重试场景按序消耗）。 */
    private void stubChat(String... contents) {
        LlmResponse[] resps = java.util.Arrays.stream(contents)
                .map(c -> LlmResponse.builder().content(c).model("gpt-test").build())
                .toArray(LlmResponse[]::new);
        when(llmGateway.chat(any(), any())).thenReturn(resps[0], java.util.Arrays.copyOfRange(resps, 1, resps.length));
    }

    private ReverseAnalyzeRequest analyzeReq(List<String> modes, Long taskId, String fileId) {
        ReverseAnalyzeRequest r = new ReverseAnalyzeRequest();
        r.setModes(modes);
        r.setTaskId(taskId);
        r.setFileId(fileId);
        r.setMaxFrames(4);
        r.setModel("vision-x");
        r.setProjectGroupId(42L);
        return r;
    }

    private void stubExtractionFourFrames() throws Exception {
        stubProbe(60, true);
        sceneOutput = sceneOut(1.5, 2.5, 4.5, 6.5);
    }

    private static final String GOOD_ANALYZE_JSON = """
            ```json
            {"storyboard":[{"shotNo":1,"description":"开场"}],"script":{"scenes":[{"sceneHeading":"内景-餐厅-白天"}],"synopsis":"测试剧情"}}
            ```
            """;

    @Test
    void analyze_keyframesOnly_neverCallsLlm() throws Exception {
        stubExtractionFourFrames();
        ReverseAnalyzeResponse r = service.analyze(analyzeReq(List.of("KEYFRAMES"), null, "src-1"), 7L);

        assertEquals(4, r.keyframes().size());
        assertNull(r.storyboard());
        assertNull(r.script());
        assertNull(r.model());
        verify(llmGateway, never()).chat(any(), any());
    }

    @Test
    void analyze_llmModes_singleCallImagePartsAndParams() throws Exception {
        stubExtractionFourFrames();
        stubChat(GOOD_ANALYZE_JSON);
        ReverseAnalyzeResponse r = service.analyze(
                analyzeReq(List.of("KEYFRAMES", "STORYBOARD", "SCRIPT"), null, "src-1"), 7L);

        // 单次 LLM 调用（分镜+剧本共用），parts=1条指令+每帧2parts（图+标注）
        ArgumentCaptor<LlmRequest> cap = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmGateway, times(1)).chat(cap.capture(), any());
        LlmRequest sent = cap.getValue();
        List<ContentPart> parts = sent.getMessages().get(0).getParts();
        assertEquals(1 + 4 * 2, parts.size());
        assertEquals("image", parts.get(1).getType());
        assertEquals("image/jpeg", parts.get(1).getMediaType());
        assertNotNull(parts.get(1).getData());
        assertTrue(((String) parts.get(2).getText()).contains("第1帧"));
        assertEquals(0.3, sent.getTemperature());
        assertEquals(4000, sent.getMaxTokens());
        assertFalse(sent.getStream());
        assertEquals("MEDIA_REVERSE_ANALYZE", sent.getCallPurpose());
        assertEquals(42L, sent.getProjectGroupId());
        assertEquals("vision-x", sent.getModel());

        assertEquals(1, r.storyboard().size());
        assertEquals("开场", r.storyboard().get(0).get("description"));
        assertEquals("测试剧情", r.script().get("synopsis"));
        assertEquals("gpt-test", r.model());
    }

    @Test
    void analyze_badJson_retriesOnceThenSucceeds() throws Exception {
        stubExtractionFourFrames();
        stubChat("模型前言不输出JSON", GOOD_ANALYZE_JSON);
        ReverseAnalyzeResponse r = service.analyze(
                analyzeReq(List.of("STORYBOARD"), null, "src-1"), 7L);

        assertEquals(1, r.storyboard().size());
        verify(llmGateway, times(2)).chat(any(), any());
    }

    @Test
    void analyze_bothAttemptsBadJson_unprocessable() throws Exception {
        stubExtractionFourFrames();
        stubChat("垃圾输出1", "垃圾输出2");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.analyze(analyzeReq(List.of("SCRIPT"), null, "src-1"), 7L));
        assertEquals(ErrorCode.UNPROCESSABLE.getCode(), ex.getCode());
        assertEquals("模型未返回合法 JSON，请重试", ex.getMessage());
        verify(llmGateway, times(2)).chat(any(), any());
    }

    @Test
    void analyze_taskIdSource_resolvesResultFileWithOwnershipGate() throws Exception {
        stubExtractionFourFrames();
        MediaGenTask task = new MediaGenTask();
        task.setResultFileId("res-9");
        when(mediaGenQueryService.loadForDownload(100L, 7L, false)).thenReturn(task);

        service.analyze(analyzeReq(List.of("KEYFRAMES"), 100L, null), 7L);

        verify(mediaGenQueryService).loadForDownload(100L, 7L, false);
        verify(fileStorageService).loadPath("res-9", 7L, false);
    }

    @Test
    void analyze_adminBypass_passesAdminFlagToOwnershipGates() throws Exception {
        // admin 角色列表见全量任务（media 列表口径）→ 源校验同放行，否则 admin 下拉里选得到的任务反推必 403
        stubExtractionFourFrames();
        MediaGenTask task = new MediaGenTask();
        task.setResultFileId("res-9");
        when(mediaGenQueryService.loadForDownload(100L, 7L, true)).thenReturn(task);

        service.analyze(analyzeReq(List.of("KEYFRAMES"), 100L, null), 7L, true);

        verify(mediaGenQueryService).loadForDownload(100L, 7L, true);
        verify(fileStorageService).loadPath("res-9", 7L, true);
    }

    @Test
    void analyze_taskIdAndFileIdBothOrNeither_badRequest() {
        BusinessException both = assertThrows(BusinessException.class,
                () -> service.analyze(analyzeReq(List.of("KEYFRAMES"), 100L, "src-1"), 7L));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), both.getCode());
        BusinessException neither = assertThrows(BusinessException.class,
                () -> service.analyze(analyzeReq(List.of("KEYFRAMES"), null, null), 7L));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), neither.getCode());
    }

    @Test
    void analyze_invalidMode_badRequest() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.analyze(analyzeReq(List.of("KEYFRAMES", "AUDIO"), null, "src-1"), 7L));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("AUDIO"));
    }

    @Test
    void analyze_emptyModes_badRequest() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.analyze(analyzeReq(List.of(), null, "src-1"), 7L));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void analyze_otherUsersTask_forbiddenPropagates() {
        when(mediaGenQueryService.loadForDownload(anyLong(), anyLong(), anyBoolean()))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "无权访问该任务"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.analyze(analyzeReq(List.of("KEYFRAMES"), 100L, null), 7L));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    // ============================ Step2：localize 编排 ============================

    private static String scriptWithScenes(int n) {
        StringBuilder sb = new StringBuilder("{\"scenes\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"sceneHeading\":\"场景").append(i + 1).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private LocalizeRequest localizeReq(String script, String locale) {
        LocalizeRequest r = new LocalizeRequest();
        r.setScript(script);
        r.setTargetLocale(locale);
        r.setNotes("保留春节团圆情节");
        return r;
    }

    @Test
    void localize_happyPath_noWarning() {
        stubChat("{\"localizedScript\":" + scriptWithScenes(2)
                + ",\"changeLog\":[{\"from\":\"筷子\",\"to\":\"刀叉\",\"scene\":\"场景1\"}]}");
        LocalizeResponse r = service.localize(localizeReq(scriptWithScenes(2), "美国"), 7L);

        assertTrue(r.localizedScript().contains("scenes"));
        assertEquals(1, r.changeLog().size());
        assertEquals("筷子", r.changeLog().get(0).get("from"));
        assertNull(r.warning());
        // 提示词带目标地区与保留要求
        ArgumentCaptor<LlmRequest> cap = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmGateway).chat(cap.capture(), any());
        String prompt = cap.getValue().getMessages().get(0).getParts().get(0).getText();
        assertTrue(prompt.contains("美国"));
        assertTrue(prompt.contains("保留春节团圆情节"));
    }

    @Test
    void localize_sceneCountMismatch_returnsWarning() {
        stubChat("{\"localizedScript\":" + scriptWithScenes(3) + ",\"changeLog\":[]}");
        LocalizeResponse r = service.localize(localizeReq(scriptWithScenes(2), "美国"), 7L);

        assertNotNull(r.warning());
        assertTrue(r.warning().contains("原 2 现 3"));
    }

    @Test
    void localize_nonJsonOriginalScript_skipsStructureCheck() {
        stubChat("{\"localizedScript\":" + scriptWithScenes(3) + ",\"changeLog\":[]}");
        LocalizeResponse r = service.localize(localizeReq("自由文本剧本，非 JSON", "美国"), 7L);
        assertNull(r.warning());
    }

    @Test
    void localize_localizedScriptMissing_unprocessable() {
        stubChat("{\"changeLog\":[]}");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.localize(localizeReq(scriptWithScenes(2), "美国"), 7L));
        assertEquals(ErrorCode.UNPROCESSABLE.getCode(), ex.getCode());
    }

    @Test
    void localize_inputValidation_badRequest() {
        BusinessException blank = assertThrows(BusinessException.class,
                () -> service.localize(localizeReq("  ", "美国"), 7L));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), blank.getCode());
        BusinessException noLocale = assertThrows(BusinessException.class,
                () -> service.localize(localizeReq(scriptWithScenes(1), null), 7L));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), noLocale.getCode());
        BusinessException tooLong = assertThrows(BusinessException.class,
                () -> service.localize(localizeReq("x".repeat(8001), "美国"), 7L));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), tooLong.getCode());
        BusinessException longLocale = assertThrows(BusinessException.class,
                () -> service.localize(localizeReq(scriptWithScenes(1), "地".repeat(33)), 7L));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), longLocale.getCode());
    }

    @Test
    void localize_disabled_unprocessable() {
        props.setEnabled(false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.localize(localizeReq(scriptWithScenes(1), "美国"), 7L));
        assertEquals(ErrorCode.UNPROCESSABLE.getCode(), ex.getCode());
    }

    @Test
    void localize_changeLogTolerant_whenArrayMissing() {
        stubChat("{\"localizedScript\":" + scriptWithScenes(1) + "}");
        LocalizeResponse r = service.localize(localizeReq(scriptWithScenes(1), "美国"), 7L);
        assertEquals(List.of(), r.changeLog());
        assertNull(r.warning());
    }

    @Test
    void analyze_responseCarriesExtractionMeta() throws Exception {
        stubExtractionFourFrames();
        ReverseAnalyzeResponse r = service.analyze(analyzeReq(List.of("KEYFRAMES"), null, "src-1"), 7L);
        assertEquals(60.0, r.durationSeconds());
        assertEquals(VideoReverseService.MODE_SCENE, r.mode());
        assertEquals(4, r.sceneHits());
    }
}
