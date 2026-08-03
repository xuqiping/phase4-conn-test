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
}
