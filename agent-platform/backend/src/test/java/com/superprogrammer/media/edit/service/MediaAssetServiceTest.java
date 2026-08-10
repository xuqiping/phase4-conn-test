package com.superprogrammer.media.edit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.media.edit.config.MediaEditProperties;
import com.superprogrammer.media.edit.dto.EditSpec;
import com.superprogrammer.media.edit.dto.MediaProbe;
import com.superprogrammer.media.edit.provider.MediaEditProvider;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MediaAssetService.validate 素材校验（plan 安全清单 / AC FR-ED1）：归属咽喉点 + ffprobe 格式/时长/裁剪/总数上限。
 *
 * <p>覆盖：① 合法 spec 通过；② 非视频(无 video 流)拒；③ 单素材时长超限拒；④ 裁剪超出素材时长拒；
 * ⑤ BGM 非音频拒；⑥ 成片总时长超限拒；⑦ 空片段拒；⑧ 非归属 → 咽喉点抛 FORBIDDEN 透传（防 IDOR）。
 *
 * <p>probe 经 mock provider（绕过真实 ffprobe/ffmpeg，后者属 Phase4 人工验证）。
 */
@ExtendWith(MockitoExtension.class)
class MediaAssetServiceTest {

    @Mock private FileStorageService fileStorageService;
    @Mock private MediaGenTaskMapper mediaGenTaskMapper;
    @Mock private MediaEditProvider provider;

    private MediaAssetService assetService;

    @BeforeEach
    void setUp() {
        MediaEditProperties properties = new MediaEditProperties(); // 默认 maxClips=10/maxClipSeconds=600/maxDuration=120
        assetService = new MediaAssetService(fileStorageService, mediaGenTaskMapper, provider, properties, new ObjectMapper());
    }

    @Test
    void validate_validSpecWithBgm_passes() {
        EditSpec spec = spec(List.of(clip("v1", 0.0, 5.0)), "a1");
        stubFiles("v1", "a1");
        stubProbes(new MediaProbe(true, true, 1280, 720, 10.0),   // v1 先探测
                new MediaProbe(false, true, null, null, 30.0));    // a1 后探测

        assertDoesNotThrow(() -> assetService.validate(spec, 100L, false));
    }

    @Test
    void validate_nonVideo_throwsBadRequest() {
        EditSpec spec = spec(List.of(clip("v1", null, null)), null);
        stubFiles("v1");
        stubProbes(new MediaProbe(false, true, null, null, 10.0)); // 无 video 流（假视频）

        assertThrows(BusinessException.class, () -> assetService.validate(spec, 100L, false));
    }

    @Test
    void validate_clipOverDuration_throwsBadRequest() {
        MediaEditProperties p = new MediaEditProperties();
        p.setMaxClipSeconds(5);
        assetService = new MediaAssetService(fileStorageService, mediaGenTaskMapper, provider, p, new ObjectMapper());

        EditSpec spec = spec(List.of(clip("v1", null, null)), null);
        stubFiles("v1");
        stubProbes(new MediaProbe(true, true, 1280, 720, 100.0)); // 100s > 5s 上限

        BusinessException ex = assertThrows(BusinessException.class,
                () -> assetService.validate(spec, 100L, false));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void validate_trimOutOfRange_throwsBadRequest() {
        EditSpec spec = spec(List.of(clip("v1", 0.0, 50.0)), null); // trimEnd 50 > 片长 10+0.5
        stubFiles("v1");
        stubProbes(new MediaProbe(true, true, 1280, 720, 10.0));

        assertThrows(BusinessException.class, () -> assetService.validate(spec, 100L, false));
    }

    @Test
    void validate_bgmNonAudio_throwsBadRequest() {
        EditSpec spec = spec(List.of(clip("v1", null, null)), "a1");
        stubFiles("v1", "a1");
        stubProbes(new MediaProbe(true, true, 1280, 720, 10.0),
                new MediaProbe(true, false, 1280, 720, 30.0)); // BGM 却是视频流、无音频

        assertThrows(BusinessException.class, () -> assetService.validate(spec, 100L, false));
    }

    @Test
    void validate_totalOverMax_throwsBadRequest() {
        MediaEditProperties p = new MediaEditProperties();
        p.setMaxDuration(8); // 两段各 5s = 10s > 8s
        assetService = new MediaAssetService(fileStorageService, mediaGenTaskMapper, provider, p, new ObjectMapper());

        EditSpec spec = spec(List.of(clip("v1", 0.0, 5.0), clip("v2", 0.0, 5.0)), null);
        stubFiles("v1", "v2");
        stubProbes(new MediaProbe(true, true, 1280, 720, 10.0),
                new MediaProbe(true, true, 1280, 720, 10.0));

        assertThrows(BusinessException.class, () -> assetService.validate(spec, 100L, false));
    }

    @Test
    void validate_emptyClips_throwsBadRequest() {
        EditSpec spec = new EditSpec();
        spec.setClips(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> assetService.validate(spec, 100L, false));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void validate_nonOwner_propagatesForbiddenFromChokepoint() {
        // 归属咽喉点：load 对非 owner 抛 FORBIDDEN → validate 透传（防 authenticated IDOR）
        when(fileStorageService.load("v1", 100L, false))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "无权访问"));

        EditSpec spec = spec(List.of(clip("v1", null, null)), null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> assetService.validate(spec, 100L, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    // ---------- helpers ----------

    /** 按 fileId 存根 load（返回可读 resource）；probe 与 fileId 解耦，按调用顺序存根（见 stubProbes）。 */
    private void stubFiles(String... fileIds) {
        for (String fid : fileIds) {
            Resource resource = mock(Resource.class);
            try {
                when(resource.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{0}));
            } catch (Exception ignored) { /* 不会发生 */ }
            when(fileStorageService.load(eq(fid), eq(100L), eq(false))).thenReturn(resource);
        }
    }

    /** 按调用顺序存根 provider.probe（validate 先 clips 后 audio，依次消费）。 */
    private void stubProbes(MediaProbe... probes) {
        try {
            when(provider.probe(any())).thenReturn(probes[0], java.util.Arrays.copyOfRange(probes, 1, probes.length));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private EditSpec.ClipSpec clip(String fileId, Double trimStart, Double trimEnd) {
        EditSpec.ClipSpec c = new EditSpec.ClipSpec();
        c.setFileId(fileId);
        c.setSourceType(EditSpec.SOURCE_UPLOAD);
        c.setTrimStart(trimStart);
        c.setTrimEnd(trimEnd);
        return c;
    }

    private EditSpec spec(List<EditSpec.ClipSpec> clips, String bgmFileId) {
        EditSpec s = new EditSpec();
        s.setClips(clips);
        if (bgmFileId != null) {
            EditSpec.AudioSpec a = new EditSpec.AudioSpec();
            a.setFileId(bgmFileId);
            a.setVolume(0.5);
            s.setAudio(a);
        }
        return s;
    }
}
