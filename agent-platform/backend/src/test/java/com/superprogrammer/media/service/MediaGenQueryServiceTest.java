package com.superprogrammer.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MediaGenQueryService ownership 硬过滤 + videoUrl 不暴露 Ark 临时 URL（spec 安全检查清单核心）。
 *
 * <p>覆盖：① 普通 user 访问他人任务 → FORBIDDEN；② admin 旁路看全量；③ owner 自查 → videoUrl 指向下载端点；
 * ④ 未完成/无 fileId → videoUrl=null；⑤ 下载非 SUCCEEDED → BAD_REQUEST；⑥ 不存在 → NOT_FOUND。
 */
@ExtendWith(MockitoExtension.class)
class MediaGenQueryServiceTest {

    @Mock private MediaGenTaskMapper taskMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MediaGenQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new MediaGenQueryService(taskMapper, objectMapper);
    }

    @Test
    void get_ownerSucceeded_voHasDownloadUrl() {
        MediaGenTask task = task(1L, 100L, MediaGenTask.STATUS_SUCCEEDED, "file-xyz");
        when(taskMapper.selectById(1L)).thenReturn(task);

        var vo = queryService.get(1L, 100L, false);

        assertEquals("/api/media/tasks/1/download", vo.getVideoUrl());
        assertEquals(MediaGenTask.STATUS_SUCCEEDED, vo.getStatus());
        assertNotNull(vo.getPrompt());
    }

    @Test
    void get_nonOwnerNonAdmin_throwsForbidden() {
        MediaGenTask task = task(1L, 100L, MediaGenTask.STATUS_SUCCEEDED, "file-xyz");
        when(taskMapper.selectById(1L)).thenReturn(task);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> queryService.get(1L, 999L, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void get_admin_bypassOwnership_seesOtherUsersTask() {
        MediaGenTask task = task(1L, 100L, MediaGenTask.STATUS_SUCCEEDED, "file-xyz");
        when(taskMapper.selectById(1L)).thenReturn(task);

        // admin=999 不是 owner，但 admin 旁路
        var vo = queryService.get(1L, 999L, true);

        assertEquals("/api/media/tasks/1/download", vo.getVideoUrl());
    }

    @Test
    void get_runningTask_videoUrlNullEvenForOwner() {
        MediaGenTask task = task(1L, 100L, MediaGenTask.STATUS_RUNNING, null);
        when(taskMapper.selectById(1L)).thenReturn(task);

        var vo = queryService.get(1L, 100L, false);

        assertNull(vo.getVideoUrl(), "未完成/无 fileId 时不应暴露下载端点");
    }

    @Test
    void get_AC_V3_03_restoresVideoParametersAndInputAttachmentSummaries() {
        MediaGenTask task = task(1L, 100L, MediaGenTask.STATUS_RUNNING, null);
        task.setRequestConfig("""
                {"prompt":"组合素材","ratio":"9:16","duration":12,"resolution":"1080p",
                 "watermark":true,"generateAudio":true,
                 "attachments":[
                   {"fileId":"img-1","kind":"image","frameRole":"first_frame","name":"首图.png"},
                   {"fileId":"vid-1","kind":"video","name":"动作.mp4"}
                 ]}
                """);
        when(taskMapper.selectById(1L)).thenReturn(task);

        var vo = queryService.get(1L, 100L, false);

        assertEquals("9:16", vo.getRatio());
        assertTrue(vo.getWatermark());
        assertTrue(vo.getGenerateAudio());
        assertEquals(2, vo.getInputAttachments().size());
        assertEquals("first_frame", vo.getInputAttachments().get(0).getFrameRole());
        assertEquals("首图.png", vo.getInputAttachments().get(0).getName());
        assertEquals("/api/files/img-1", vo.getInputAttachments().get(0).getPreviewUrl());
    }

    @Test
    void get_AC_V3_03_legacyRefFileBecomesFirstFrameSummary() {
        MediaGenTask task = task(1L, 100L, MediaGenTask.STATUS_RUNNING, null);
        task.setRequestConfig("{\"prompt\":\"旧任务\",\"refFileId\":\"legacy-img\",\"frameRole\":\"last\"}");
        when(taskMapper.selectById(1L)).thenReturn(task);

        var vo = queryService.get(1L, 100L, false);

        assertEquals(1, vo.getInputAttachments().size());
        assertEquals("image", vo.getInputAttachments().get(0).getKind());
        assertEquals("last_frame", vo.getInputAttachments().get(0).getFrameRole());
    }

    // ---------- 7x-4：hasReference 计算（首尾帧图不算参考视频） ----------

    @Test
    void get_hasReference_videoAttachmentPresent_isTrue() {
        // 含 kind=video 附件 → hasReference=true
        MediaGenTask task = task(1L, 100L, MediaGenTask.STATUS_RUNNING, null);
        task.setRequestConfig("""
                {"prompt":"带参考视频","attachments":[
                   {"fileId":"vid-1","kind":"video","name":"动作.mp4"}
                 ]}
                """);
        when(taskMapper.selectById(1L)).thenReturn(task);

        var vo = queryService.get(1L, 100L, false);

        assertTrue(vo.getHasReference(), "含 kind=video 附件应标 hasReference=true");
    }

    @Test
    void get_hasReference_imageFirstFrameOnly_isFalse() {
        // 仅首尾帧参考图（kind=image）→ hasReference=false（图不算参考视频）
        MediaGenTask task = task(1L, 100L, MediaGenTask.STATUS_RUNNING, null);
        task.setRequestConfig("""
                {"prompt":"首帧图","attachments":[
                   {"fileId":"img-1","kind":"image","frameRole":"first_frame","name":"首图.png"}
                 ]}
                """);
        when(taskMapper.selectById(1L)).thenReturn(task);

        var vo = queryService.get(1L, 100L, false);

        assertFalse(vo.getHasReference(), "仅首尾帧图应标 hasReference=false");
    }

    @Test
    void get_hasReference_noAttachments_isFalse() {
        // 无附件（纯文生视频）→ hasReference=false
        MediaGenTask task = task(1L, 100L, MediaGenTask.STATUS_RUNNING, null);
        when(taskMapper.selectById(1L)).thenReturn(task);

        var vo = queryService.get(1L, 100L, false);

        assertFalse(vo.getHasReference(), "无附件应标 hasReference=false");
    }

    @Test
    void get_AC_V3_07_returnsSubmittedAndRedactedProviderRequestsWithoutDataUri() throws Exception {
        MediaGenTask task = task(1L, 100L, MediaGenTask.STATUS_RUNNING, null);
        task.setRequestConfig("""
                {"prompt":"组合素材","ratio":"9:16","attachments":[{"fileId":"img-1","kind":"image"}],
                 "providerRequestSnapshot":{"provider":"ark-seedance","request":{"model":"seedance",
                   "content":[{"type":"image_url","image_url":{"redacted":true,"transport":"data_uri","fileId":"img-1"}}]}}}
                """);
        when(taskMapper.selectById(1L)).thenReturn(task);

        var vo = queryService.get(1L, 100L, false);

        assertEquals("组合素材", vo.getSubmittedRequest().path("prompt").asText());
        assertFalse(vo.getSubmittedRequest().has("providerRequestSnapshot"), "平台提交参数不重复嵌套 Provider 快照");
        assertEquals("ark-seedance", vo.getProviderRequestSnapshot().path("provider").asText());
        String json = objectMapper.writeValueAsString(vo);
        assertFalse(json.contains("data:"), "详情序列化严禁返回原始 data URI");
    }

    @Test
    void get_AC_V3_07_oldTaskHasNoProviderRequestSnapshot() {
        MediaGenTask task = task(1L, 100L, MediaGenTask.STATUS_SUCCEEDED, "file-xyz");
        when(taskMapper.selectById(1L)).thenReturn(task);

        var vo = queryService.get(1L, 100L, false);

        assertNotNull(vo.getSubmittedRequest());
        assertNull(vo.getProviderRequestSnapshot(), "旧任务不能伪造实际发送快照");
    }

    @Test
    void get_notFound_throwsNotFound() {
        when(taskMapper.selectById(404L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> queryService.get(404L, 100L, false));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void loadForDownload_nonOwner_throwsForbidden() {
        MediaGenTask task = task(1L, 100L, MediaGenTask.STATUS_SUCCEEDED, "file-xyz");
        when(taskMapper.selectById(1L)).thenReturn(task);

        assertThrows(BusinessException.class, () -> queryService.loadForDownload(1L, 999L, false));
    }

    @Test
    void loadForDownload_notSucceeded_throwsBadRequest() {
        MediaGenTask task = task(1L, 100L, MediaGenTask.STATUS_RUNNING, null);
        when(taskMapper.selectById(1L)).thenReturn(task);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> queryService.loadForDownload(1L, 100L, false));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void loadForDownload_succeededButNoFileId_throwsBadRequest() {
        MediaGenTask task = task(1L, 100L, MediaGenTask.STATUS_SUCCEEDED, null); // 无 fileId
        when(taskMapper.selectById(1L)).thenReturn(task);

        assertThrows(BusinessException.class, () -> queryService.loadForDownload(1L, 100L, false));
    }

    @Test
    void loadForDownload_ownerSucceeded_returnsTask() {
        MediaGenTask task = task(1L, 100L, MediaGenTask.STATUS_SUCCEEDED, "file-xyz");
        when(taskMapper.selectById(1L)).thenReturn(task);

        MediaGenTask loaded = queryService.loadForDownload(1L, 100L, false);

        assertEquals("file-xyz", loaded.getResultFileId());
    }

    // ---------- 分页（4x#2）：默认值/非法回落/limit 兼容/翻页边界 ----------

    @Test
    void page_passesEscapedQueryAndFiltersToBothCountAndList() {
        OffsetDateTime from = OffsetDateTime.parse("2026-08-01T00:00:00+08:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-08-11T00:00:00+08:00");
        when(taskMapper.countHistory(anyLong(), anyBoolean(), any(), any(), any(), any())).thenReturn(0L);
        when(taskMapper.selectHistory(anyLong(), anyBoolean(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(List.of());

        queryService.page(100L, false, "50%_猫\\", from, to, null, 1, 10, 25);

        // count 与 list 必须收到同一组转义参数，否则 total 与列表漂移
        verify(taskMapper).countHistory(100L, false, "50\\%\\_猫\\\\", from, to, null);
        verify(taskMapper).selectHistory(100L, false, "50\\%\\_猫\\\\", from, to, 10, 0, null);
    }

    @Test
    void page_blankQueryNormalized_adminPreserved_defaultSize10Offset0() {
        when(taskMapper.countHistory(anyLong(), anyBoolean(), any(), any(), any(), any())).thenReturn(0L);
        when(taskMapper.selectHistory(anyLong(), anyBoolean(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(List.of());

        // page/pageSize/legacyLimit 全缺省：第 1 页 × 默认 10 条
        var result = queryService.page(999L, true, "   ", null, null, null, null, null, null);

        verify(taskMapper).selectHistory(999L, true, null, null, null, 10, 0, null);
        assertEquals(10L, result.getSize());
        assertEquals(1L, result.getPage());
    }

    @Test
    void page_legacyLimitOnly_usedAsPageSize() {
        when(taskMapper.countHistory(anyLong(), anyBoolean(), any(), any(), any(), any())).thenReturn(50L);
        when(taskMapper.selectHistory(anyLong(), anyBoolean(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(List.of());

        // 旧调用只传 limit：limit 即每页条数（允许 25 这类非白名单值，沿用旧契约）
        queryService.page(100L, false, null, null, null, null, 2, null, 25);

        verify(taskMapper).selectHistory(100L, false, null, null, null, 25, 25, null);
    }

    @Test
    void page_limitAndPageSizeBoth_pageSizeWins() {
        when(taskMapper.countHistory(anyLong(), anyBoolean(), any(), any(), any(), any())).thenReturn(0L);
        when(taskMapper.selectHistory(anyLong(), anyBoolean(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(List.of());

        queryService.page(100L, false, null, null, null, null, 1, 5, 50);

        verify(taskMapper).selectHistory(100L, false, null, null, null, 5, 0, null);
    }

    @Test
    void page_pageSizeInvalid_silentlyFallsBackTo10() {
        when(taskMapper.countHistory(anyLong(), anyBoolean(), any(), any(), any(), any())).thenReturn(0L);
        when(taskMapper.selectHistory(anyLong(), anyBoolean(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(List.of());

        // 15 不在白名单 {5,10,20,50} → 静默回落 10（不报错，对齐 4x#2 拍板）
        queryService.page(100L, false, null, null, null, null, 1, 15, null);

        verify(taskMapper).selectHistory(100L, false, null, null, null, 10, 0, null);
    }

    @Test
    void page_pageLessThanOne_clampedToOffset0() {
        when(taskMapper.countHistory(anyLong(), anyBoolean(), any(), any(), any(), any())).thenReturn(30L);
        when(taskMapper.selectHistory(anyLong(), anyBoolean(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(List.of());

        var result = queryService.page(100L, false, null, null, null, null, 0, 10, null);

        verify(taskMapper).selectHistory(100L, false, null, null, null, 10, 0, null);
        assertEquals(1L, result.getPage(), "页码归一为 1 而非报错");
    }

    @Test
    void page_beyondLastPage_emptyRecords_totalKept() {
        when(taskMapper.countHistory(anyLong(), anyBoolean(), any(), any(), any(), any())).thenReturn(3L);
        when(taskMapper.selectHistory(anyLong(), anyBoolean(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(List.of());

        // 第 5 页 × 10 条但总数只有 3 → 空页不报错，total/pages 仍正确
        var result = queryService.page(100L, false, null, null, null, null, 5, 10, null);

        assertTrue(result.getRecords().isEmpty());
        assertEquals(3L, result.getTotal());
        assertEquals(1L, result.getPages());
        verify(taskMapper).selectHistory(100L, false, null, null, null, 10, 40, null);
    }

    @Test
    void page_omitsLargeRequestDetailsUntilUserOpensTask() {
        MediaGenTask task = task(1L, 100L, MediaGenTask.STATUS_RUNNING, null);
        task.setRequestConfig("{\"prompt\":\"p\",\"providerRequestSnapshot\":{\"provider\":\"ark-seedance\"}}");
        when(taskMapper.countHistory(anyLong(), anyBoolean(), any(), any(), any(), any())).thenReturn(1L);
        when(taskMapper.selectHistory(anyLong(), anyBoolean(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(List.of(task));

        var result = queryService.page(100L, false, null, null, null, null, 1, 10, null);

        assertNull(result.getRecords().get(0).getSubmittedRequest());
        assertNull(result.getRecords().get(0).getProviderRequestSnapshot());
    }

    @Test
    void page_rejectsInvalidTimeRangeAndLegacyLimit() {
        OffsetDateTime from = OffsetDateTime.parse("2026-08-11T00:00:00+08:00");
        OffsetDateTime to = from.minusMinutes(1);

        assertThrows(BusinessException.class,
                () -> queryService.page(100L, false, null, from, to, null, 1, 10, null));
        assertThrows(BusinessException.class,
                () -> queryService.page(100L, false, null, null, null, null, 1, 10, 101));
        verifyNoInteractions(taskMapper);
    }

    // ---------- kind 大类过滤（图片第二轮：图片记录不混进视频历史） ----------

    @Test
    void page_kindImage_passesImageKindToBothQueries() {
        when(taskMapper.countHistory(anyLong(), anyBoolean(), any(), any(), any(), any())).thenReturn(0L);
        when(taskMapper.selectHistory(anyLong(), anyBoolean(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(List.of());

        queryService.page(100L, false, null, null, null, "IMAGE", 1, 10, null);

        verify(taskMapper).selectHistory(100L, false, null, null, null, 10, 0, "IMAGE");
        verify(taskMapper).countHistory(100L, false, null, null, null, "IMAGE");
    }

    @Test
    void page_kindVideo_passesVideoKindToMapper() {
        when(taskMapper.countHistory(anyLong(), anyBoolean(), any(), any(), any(), any())).thenReturn(0L);
        when(taskMapper.selectHistory(anyLong(), anyBoolean(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(List.of());

        queryService.page(100L, false, null, null, null, "VIDEO", 1, 10, null);

        verify(taskMapper).selectHistory(100L, false, null, null, null, 10, 0, "VIDEO");
    }

    @Test
    void page_kindLowercase_isNormalizedToUppercase() {
        when(taskMapper.countHistory(anyLong(), anyBoolean(), any(), any(), any(), any())).thenReturn(0L);
        when(taskMapper.selectHistory(anyLong(), anyBoolean(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(List.of());

        queryService.page(100L, false, null, null, null, "image", 1, 10, null);

        verify(taskMapper).selectHistory(100L, false, null, null, null, 10, 0, "IMAGE");
    }

    @Test
    void page_kindInvalid_rejectedBeforeMapper() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> queryService.page(100L, false, null, null, null, "AUDIO", 1, 10, null));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verifyNoInteractions(taskMapper);
    }

    // ---------- 图片任务（TEXT2IMAGE/IMAGE2IMAGE）分支 ----------

    @Test
    void get_imageSucceeded_voHasPerImageDownloadUrls() {
        MediaGenTask task = imageTask(2L, 100L, MediaGenTask.STATUS_SUCCEEDED,
                "{\"imageFileIds\":[\"img-a\",\"img-b\",\"img-c\"],\"generatedImages\":3,\"outputTokens\":900}",
                "{\"prompt\":\"测试\",\"size\":\"2K\"}");
        when(taskMapper.selectById(2L)).thenReturn(task);

        var vo = queryService.get(2L, 100L, false);

        assertEquals(3, vo.getImageUrls().size(), "3 张图→3 个逐张下载端点");
        assertEquals("/api/media/tasks/2/images/0/download", vo.getImageUrls().get(0));
        assertEquals("/api/media/tasks/2/images/2/download", vo.getImageUrls().get(2));
        assertEquals(3, vo.getGeneratedImages());
        assertEquals(900L, vo.getOutputTokens());
        assertEquals("2K", vo.getSize());
        assertNull(vo.getVideoUrl(), "图片任务无 videoUrl");
    }

    @Test
    void get_imageRunning_imageUrlsNull() {
        MediaGenTask task = imageTask(2L, 100L, MediaGenTask.STATUS_RUNNING, null,
                "{\"prompt\":\"p\"}");
        when(taskMapper.selectById(2L)).thenReturn(task);

        var vo = queryService.get(2L, 100L, false);

        assertNull(vo.getImageUrls(), "未完成不暴露图片下载端点");
    }

    @Test
    void loadImageFileId_validIdx_returnsFileId() {
        MediaGenTask task = imageTask(2L, 100L, MediaGenTask.STATUS_SUCCEEDED,
                "{\"imageFileIds\":[\"img-a\",\"img-b\"]}", null);
        when(taskMapper.selectById(2L)).thenReturn(task);

        assertEquals("img-b", queryService.loadImageFileId(2L, 1, 100L, false));
    }

    @Test
    void loadImageFileId_idxOutOfBounds_throwsBadRequest() {
        MediaGenTask task = imageTask(2L, 100L, MediaGenTask.STATUS_SUCCEEDED,
                "{\"imageFileIds\":[\"img-a\"]}", null);
        when(taskMapper.selectById(2L)).thenReturn(task);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> queryService.loadImageFileId(2L, 5, 100L, false));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void loadImageFileId_nonOwner_throwsForbidden() {
        MediaGenTask task = imageTask(2L, 100L, MediaGenTask.STATUS_SUCCEEDED,
                "{\"imageFileIds\":[\"img-a\"]}", null);
        when(taskMapper.selectById(2L)).thenReturn(task);

        assertThrows(BusinessException.class,
                () -> queryService.loadImageFileId(2L, 0, 999L, false));
    }

    @Test
    void loadImageFileId_notSucceeded_throwsBadRequest() {
        MediaGenTask task = imageTask(2L, 100L, MediaGenTask.STATUS_FAILED,
                "{\"imageFileIds\":[\"img-a\"]}", null);
        when(taskMapper.selectById(2L)).thenReturn(task);

        assertThrows(BusinessException.class,
                () -> queryService.loadImageFileId(2L, 0, 100L, false));
    }

    // ---------- helpers ----------

    /** 造一个任务，requestConfig 含标准 prompt/duration/resolution。 */
    private MediaGenTask task(Long id, Long userId, String status, String resultFileId) {
        MediaGenTask t = new MediaGenTask();
        t.setId(id);
        t.setUserId(userId);
        t.setStatus(status);
        t.setResultFileId(resultFileId);
        t.setTaskType(MediaGenTask.TYPE_TEXT2VIDEO);
        t.setModel("doubao-seedance-1-0");
        t.setRequestConfig("{\"prompt\":\"一只橘猫晒太阳\",\"duration\":5,\"resolution\":\"720p\"}");
        return t;
    }

    /** 造一个图片任务（resultMeta JSONB + requestConfig）。 */
    private MediaGenTask imageTask(Long id, Long userId, String status, String resultMeta, String requestConfig) {
        MediaGenTask t = new MediaGenTask();
        t.setId(id);
        t.setUserId(userId);
        t.setStatus(status);
        t.setResultMeta(resultMeta);
        t.setTaskType(MediaGenTask.TYPE_TEXT2IMAGE);
        t.setModel("doubao-seedream-5.0-lite");
        t.setRequestConfig(requestConfig == null ? "{\"prompt\":\"p\"}" : requestConfig);
        return t;
    }
}
