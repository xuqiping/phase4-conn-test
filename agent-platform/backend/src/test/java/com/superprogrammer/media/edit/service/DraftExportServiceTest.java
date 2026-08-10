package com.superprogrammer.media.edit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.media.edit.config.MediaEditProperties;
import com.superprogrammer.media.edit.dto.EditSpec;
import com.superprogrammer.media.edit.dto.MediaProbe;
import com.superprogrammer.media.edit.provider.MediaEditProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link DraftExportService} 单测：剪映 draft_content.json 生成 + zip 打包（plan 验证清单）。
 *
 * <p>覆盖：① V2 多轨 → tracks(videos/audios/texts) 结构；② material 按 fileId 去重；③ 时间换算微秒；
 * ④ 字幕 text_content 嵌套 JSON 转义；⑤ bundle 模式素材打包进 zip；⑥ duration=timelineEnd。
 *
 * <p>probe/findMeta/load 经 mock（绕过真实 ffmpeg/磁盘，剪映真机导入属 Phase4）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DraftExportServiceTest {

    @Mock private FileStorageService fileStorageService;
    @Mock private MediaEditProvider provider;

    private DraftExportService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new DraftExportService(fileStorageService, provider, new MediaEditProperties(), mapper);
    }

    @Test
    void export_v2MultiTrack_buildsJianYingDraft() throws Exception {
        EditSpec spec = sampleSpec();
        // probe 顺序 = collectFileIds 顺序：VIDEO(v1) → AUDIO(a1)
        stubOwned("v1", new MediaProbe(true, true, 1920, 1080, 5.0), 1000L, "v1.mp4");
        stubOwned("a1", new MediaProbe(false, true, null, null, 5.0), 2000L, "a1.mp3");
        when(provider.probe(any())).thenReturn(
                new MediaProbe(true, true, 1920, 1080, 5.0),
                new MediaProbe(false, true, null, null, 5.0));

        DraftExportService.DraftContext ctx = service.prepare(spec, 100L, false, "test-draft", true);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        service.streamZip(ctx, baos);

        // 解 zip
        JsonNode content = null, meta = null;
        int entries = 0;
        boolean hasV1 = false, hasA1 = false;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                entries++;
                String name = e.getName();
                byte[] data = zin.readAllBytes();
                if (name.endsWith("draft_content.json")) {
                    content = mapper.readTree(data);
                } else if (name.endsWith("draft_meta_info.json")) {
                    meta = mapper.readTree(data);
                } else if (name.endsWith("v1")) {
                    hasV1 = true;
                } else if (name.endsWith("a1")) {
                    hasA1 = true;
                }
            }
        }

        assertNotNull(content, "draft_content.json 必须存在");
        assertNotNull(meta, "draft_meta_info.json 必须存在");
        assertTrue(hasV1 && hasA1, "bundle 模式须打包 v1/a1 素材");
        assertTrue(entries >= 4, "至少 2 素材 + 2 json");

        // tracks：video + audio + text
        JsonNode tracks = content.path("tracks");
        assertTrue(tracks.isArray(), "tracks 须为数组");
        assertEquals(3, tracks.size());

        // materials 去重：各 1 个
        assertEquals(1, content.path("materials").path("videos").size(), "video material 按 fileId 去重");
        assertEquals(1, content.path("materials").path("audios").size());
        assertEquals(1, content.path("materials").path("texts").size());

        // 字幕 text_content 嵌套 JSON 含中文
        JsonNode textMat = content.path("materials").path("texts").get(0);
        assertEquals("你好", textMat.path("text").asText());
        assertTrue(textMat.path("text_content").asText().contains("你好"), "text_content 须含原文");
        assertTrue(textMat.path("text_content").asText().startsWith("{\"content\""), "text_content 须为嵌套 JSON 字符串");

        // 时间换算微秒：text seg targetStart=1s → 1_000_000μs
        JsonNode textTrack = trackByType(tracks, "text");
        JsonNode textSeg = textTrack.path("segments").get(0);
        assertEquals(1_000_000L, textSeg.path("target_timerange").path("start").asLong());
        assertEquals(2_000_000L, textSeg.path("target_timerange").path("duration").asLong()); // 1~3s = 2s

        // video seg targetStart=0
        JsonNode videoSeg = trackByType(tracks, "video").path("segments").get(0);
        assertEquals(0L, videoSeg.path("target_timerange").path("start").asLong());
        assertEquals(4_000_000L, videoSeg.path("target_timerange").path("duration").asLong());

        // duration = timelineEnd(4s) → 4_000_000μs
        assertEquals(4_000_000L, content.path("duration").asLong());
        assertEquals("test-draft", meta.path("draft_name").asText());
        assertEquals(content.path("draft_id").asText(), meta.path("id").asText());
    }

    @Test
    void prepare_overSizeLimit_throws() throws Exception {
        MediaEditProperties p = new MediaEditProperties();
        p.setDraftMaxTotalMb(0); // 0MB 上限
        service = new DraftExportService(fileStorageService, provider, p, mapper);

        EditSpec spec = sampleSpec();
        stubOwned("v1", new MediaProbe(true, true, 1920, 1080, 5.0), 1000L, "v1.mp4");
        stubOwned("a1", new MediaProbe(false, true, null, null, 5.0), 2000L, "a1.mp3");
        when(provider.probe(any())).thenReturn(
                new MediaProbe(true, true, 1920, 1080, 5.0),
                new MediaProbe(false, true, null, null, 5.0));

        assertThrows(com.superprogrammer.common.exception.BusinessException.class,
                () -> service.prepare(spec, 100L, false, "test-draft", true));
    }

    // ---------- helpers ----------

    private JsonNode trackByType(JsonNode tracks, String type) {
        for (JsonNode t : tracks) {
            if (type.equals(t.path("type").asText())) {
                return t;
            }
        }
        throw new AssertionError("未找到 " + type + " 轨");
    }

    private void stubOwned(String fileId, MediaProbe probe, long size, String originalName) throws Exception {
        Resource resource = mock(Resource.class);
        when(resource.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(resource.getFile()).thenReturn(new File("/tmp/dummy-" + fileId));
        when(fileStorageService.load(eq(fileId), eq(100L), eq(false))).thenReturn(resource);
        StoredFileEntity meta = mock(StoredFileEntity.class);
        when(meta.getSize()).thenReturn(size);
        when(meta.getOriginalName()).thenReturn(originalName);
        when(fileStorageService.findMeta(fileId)).thenReturn(meta);
    }

    /** V2 spec：1 VIDEO 段(v1,0~4) + 1 AUDIO 段(a1,0~4) + 1 字幕(你好,1~3)。timelineEnd=4s。 */
    private EditSpec sampleSpec() {
        EditSpec.SegmentSpec vs = new EditSpec.SegmentSpec();
        vs.setFileId("v1");
        vs.setTrimStart(0.0);
        vs.setTrimEnd(4.0);
        vs.setTargetStart(0.0);
        vs.setTargetEnd(4.0);
        EditSpec.TrackSpec video = new EditSpec.TrackSpec();
        video.setType("VIDEO");
        video.setSegments(new java.util.ArrayList<>(List.of(vs)));

        EditSpec.SegmentSpec as = new EditSpec.SegmentSpec();
        as.setFileId("a1");
        as.setTrimStart(0.0);
        as.setTrimEnd(4.0);
        as.setTargetStart(0.0);
        as.setTargetEnd(4.0);
        as.setVolume(0.5);
        EditSpec.TrackSpec audio = new EditSpec.TrackSpec();
        audio.setType("AUDIO");
        audio.setName("BGM");
        audio.setVolume(0.5);
        audio.setSegments(new java.util.ArrayList<>(List.of(as)));

        EditSpec.TextSegmentSpec tx = new EditSpec.TextSegmentSpec();
        tx.setContent("你好");
        tx.setTargetStart(1.0);
        tx.setTargetEnd(3.0);
        tx.setPosition("BOTTOM");
        EditSpec.TrackSpec text = new EditSpec.TrackSpec();
        text.setType("TEXT");
        text.setTexts(new java.util.ArrayList<>(List.of(tx)));

        EditSpec spec = new EditSpec();
        spec.setSchemaVersion(EditSpec.SCHEMA_V2);
        spec.setTracks(new java.util.ArrayList<>(List.of(video, audio, text)));
        EditSpec.OutputSpec out = new EditSpec.OutputSpec();
        out.setResolution("1080p");
        out.setFps(24);
        spec.setOutput(out);
        return spec;
    }
}
