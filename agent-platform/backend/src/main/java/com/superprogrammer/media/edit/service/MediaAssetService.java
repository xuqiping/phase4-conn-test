package com.superprogrammer.media.edit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.media.edit.config.MediaEditProperties;
import com.superprogrammer.media.edit.dto.EditSpec;
import com.superprogrammer.media.edit.dto.EditSpecNormalizer;
import com.superprogrammer.media.edit.dto.MediaAssetVO;
import com.superprogrammer.media.edit.dto.MediaProbe;
import com.superprogrammer.media.edit.provider.MediaEditProvider;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 素材校验 + 元数据（submit 期阻塞校验，FR-ED1）。
 *
 * <p>两职责：
 * <ul>
 *   <li>{@link #listGeneratedAssets}：列当前用户（admin 全量）SeedDance 已生成视频，供素材库面板勾选。</li>
 *   <li>{@link #validate}：提交前对 edit_spec 引用的每个 fileId 强校验——归属（FileStorageService.load 咽喉点
 *       403/404）+ 格式（ffprobe：片段须视频、BGM 须音频）+ 时长/裁剪范围/总数上限（防假视频与超大 DoS）。
 *       归属在提交期一次性卡死，worker 渲染期用 admin 旁路读，不再重复校验。</li>
 * </ul>
 *
 * <p>probe 经 {@link MediaEditProvider#probe}（ffprobe）；为避免重复 IO，同一 fileId 在一次 validate 内缓存探测结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaAssetService {

    private final FileStorageService fileStorageService;
    private final MediaGenTaskMapper mediaGenTaskMapper;
    private final MediaEditProvider provider;
    private final MediaEditProperties properties;
    private final ObjectMapper objectMapper;

    /** 列已生成视频（SUCCEEDED 且有 result_file_id），owner 自查自、admin 全量，最近 100 条。 */
    public List<MediaAssetVO> listGeneratedAssets(Long userId, boolean admin) {
        LambdaQueryWrapper<MediaGenTask> w = new LambdaQueryWrapper<>();
        w.eq(MediaGenTask::getStatus, MediaGenTask.STATUS_SUCCEEDED)
                .isNotNull(MediaGenTask::getResultFileId);
        if (!admin) {
            w.eq(MediaGenTask::getUserId, userId);
        }
        w.orderByDesc(MediaGenTask::getCreatedAt).last("LIMIT 100");
        return mediaGenTaskMapper.selectList(w).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    private MediaAssetVO toVO(MediaGenTask task) {
        Double duration = null;
        try {
            JsonNode cfg = objectMapper.readTree(task.getRequestConfig());
            if (cfg.path("duration").isNumber()) {
                duration = cfg.path("duration").asDouble();
            }
        } catch (Exception e) {
            log.warn("生成素材解析 duration 失败 taskId={}: {}", task.getId(), e.getMessage());
        }
        return MediaAssetVO.builder()
                .fileId(task.getResultFileId())
                .name("生成视频 #" + task.getId())
                .durationSeconds(duration)
                .sourceType(EditSpec.SOURCE_GEN)
                .createdAt(task.getCreatedAt())
                .build();
    }

    /**
     * 提交前校验 edit_spec：归属 + 格式 + 时长/裁剪/上限，并规范化成 V2。
     *
     * @return 已 normalize 的 V2 spec（controller 应将其传给 {@code taskService.submit} 落库，保证存库即规范）
     * @throws BusinessException 任一素材不归属/非视频(音频)/超限 → 403/404/400
     */
    public EditSpec validate(EditSpec spec, Long userId, boolean admin) {
        if (spec == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "剪辑意图不能为空");
        }
        // 收集 fileId + 经归属咽喉点 probe（格式/时长缓存）
        Map<String, MediaProbe> cache = new HashMap<>();
        for (String fid : EditSpecNormalizer.collectFileIds(spec)) {
            cache.computeIfAbsent(fid, id -> probeOwned(id, userId, admin));
        }
        if (cache.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "至少需要 1 个视频片段");
        }
        // normalize V1/V2 → V2（用 probe 时长解析缺省 trimEnd/target）
        EditSpec v2 = EditSpecNormalizer.normalize(spec, fid -> {
            MediaProbe p = cache.get(fid);
            return p != null && p.durationSeconds() != null ? p.durationSeconds() : null;
        });
        validateV2(v2, cache);
        return v2;
    }

    /** V2 结构校验：VIDEO 轨唯一、格式匹配、trim/target 合法且无变速、同轨无重叠、轨数/段数/时长上限。 */
    private void validateV2(EditSpec spec, Map<String, MediaProbe> cache) {
        if (spec.getTracks() == null || spec.getTracks().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少轨道");
        }
        List<EditSpec.TrackSpec> videoTracks = new ArrayList<>();
        int audioTrackCount = 0;
        int totalSegs = 0;
        double timelineEnd = 0;
        for (EditSpec.TrackSpec t : spec.getTracks()) {
            EditSpec.TrackType tt = parseTrackType(t.getType());
            if (tt == EditSpec.TrackType.VIDEO) {
                videoTracks.add(t);
            } else if (tt == EditSpec.TrackType.AUDIO) {
                audioTrackCount++;
            } else if (tt == EditSpec.TrackType.TEXT) {
                if (t.getTexts() != null) {
                    for (EditSpec.TextSegmentSpec tx : t.getTexts()) {
                        if (tx.getContent() == null || tx.getContent().isBlank()) {
                            throw new BusinessException(ErrorCode.BAD_REQUEST, "字幕内容不能为空");
                        }
                        timelineEnd = Math.max(timelineEnd, num(tx.getTargetEnd(), 0));
                    }
                }
                continue;
            }
            // VIDEO/AUDIO segments
            List<EditSpec.SegmentSpec> segs = t.getSegments();
            if (segs == null || segs.isEmpty()) {
                if (tt == EditSpec.TrackType.VIDEO) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "VIDEO 轨至少需要 1 个片段");
                }
                continue; // 空音频轨允许
            }
            List<EditSpec.SegmentSpec> ordered = new ArrayList<>(segs);
            ordered.sort(Comparator.comparingDouble(s -> num(s.getTargetStart(), 0)));
            double prevEnd = -1;
            for (EditSpec.SegmentSpec s : ordered) {
                totalSegs++;
                if (s.getFileId() == null || s.getFileId().isBlank()) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "片段缺少 fileId");
                }
                MediaProbe p = cache.get(s.getFileId());
                if (p == null) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "素材未校验: " + s.getFileId());
                }
                double full = p.durationSeconds() != null ? p.durationSeconds() : 0;
                if (full > properties.getMaxClipSeconds()) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "素材时长超限（≤" + properties.getMaxClipSeconds() + "s）");
                }
                if (tt == EditSpec.TrackType.VIDEO && !p.hasVideo()) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "片段不是有效视频: " + s.getFileId());
                }
                if (tt == EditSpec.TrackType.AUDIO && !p.hasAudio()) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "片段不是有效音频: " + s.getFileId());
                }
                double ts = num(s.getTrimStart(), 0);
                double te = num(s.getTrimEnd(), full);
                if (te <= ts) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "trimEnd 须大于 trimStart");
                }
                if (ts < 0 || (full > 0 && te > full + 0.5)) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "裁剪范围超出素材时长");
                }
                double tgs = num(s.getTargetStart(), 0);
                double tge = num(s.getTargetEnd(), tgs + (te - ts));
                if (Math.abs((tge - tgs) - (te - ts)) > 0.5) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "片段时长与目标时长不一致（不支持变速）");
                }
                if (tgs < prevEnd - 0.001) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "同轨片段重叠");
                }
                prevEnd = tge;
                timelineEnd = Math.max(timelineEnd, tge);
            }
        }
        if (videoTracks.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "至少需要 1 个 VIDEO 轨");
        }
        if (videoTracks.size() > 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "VIDEO 轨必须唯一");
        }
        int vsegs = videoTracks.get(0).getSegments() != null ? videoTracks.get(0).getSegments().size() : 0;
        if (vsegs > properties.getMaxClips()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "视频片段数超上限（≤" + properties.getMaxClips() + "）");
        }
        if (audioTrackCount > properties.getMaxAudioTracks()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "音频轨数超上限（≤" + properties.getMaxAudioTracks() + "）");
        }
        if (totalSegs > properties.getMaxSegments()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "片段总数超上限（≤" + properties.getMaxSegments() + "）");
        }
        if (timelineEnd > properties.getMaxDuration()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "成片总时长超限（≤" + properties.getMaxDuration() + "s）");
        }
    }

    private static EditSpec.TrackType parseTrackType(String type) {
        try {
            return EditSpec.TrackType.valueOf(type == null ? "" : type);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未知轨类型: " + type);
        }
    }

    private static double num(Double d, double def) {
        return d != null ? d : def;
    }

    /** 经归属咽喉点 load（403/404）→ copy temp → ffprobe → 删 temp。 */
    private MediaProbe probeOwned(String fileId, Long userId, boolean admin) {
        Resource resource = fileStorageService.load(fileId, userId, admin);
        Path tmp;
        try {
            tmp = Files.createTempFile("asset-", ".bin");
        } catch (IOException e) {
            throw new IllegalStateException("建 temp 失败: " + e.getMessage(), e);
        }
        try {
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            return provider.probe(tmp);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "素材解析失败: " + rootMessage(e));
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignore) { /* 尽力而为 */ }
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : (m.length() > 150 ? m.substring(0, 150) : m);
    }
}
