package com.superprogrammer.media.edit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.media.edit.entity.MediaEditTask;
import com.superprogrammer.media.edit.mapper.MediaEditTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MediaEditQueryService ownership 硬过滤 + videoUrl 不暴露内部路径（plan 安全清单核心 / AC FR-ED6）。
 *
 * <p>覆盖：① 普通 user 访问他人任务 → FORBIDDEN；② admin 旁路看全量；③ owner 自查 SUCCEEDED → videoUrl 指向下载端点；
 * ④ 未完成/无 fileId → videoUrl=null；⑤ 下载非 SUCCEEDED → BAD_REQUEST；⑥ 不存在 → NOT_FOUND。
 */
@ExtendWith(MockitoExtension.class)
class MediaEditQueryServiceTest {

    @Mock private MediaEditTaskMapper taskMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MediaEditQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new MediaEditQueryService(taskMapper, objectMapper);
    }

    @Test
    void get_ownerSucceeded_voHasDownloadUrl() {
        MediaEditTask task = task(1L, 100L, MediaEditTask.STATUS_SUCCEEDED, "file-xyz");
        when(taskMapper.selectById(1L)).thenReturn(task);

        var vo = queryService.get(1L, 100L, false);

        assertEquals("/api/media/edit/tasks/1/download", vo.getVideoUrl());
        assertEquals(MediaEditTask.STATUS_SUCCEEDED, vo.getStatus());
        assertEquals(1, vo.getClipsCount());
        assertTrue(vo.getHasBgm());
        assertEquals(1, vo.getSubtitlesCount());
    }

    @Test
    void get_nonOwnerNonAdmin_throwsForbidden() {
        MediaEditTask task = task(1L, 100L, MediaEditTask.STATUS_SUCCEEDED, "file-xyz");
        when(taskMapper.selectById(1L)).thenReturn(task);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> queryService.get(1L, 999L, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void get_admin_bypassOwnership_seesOtherUsersTask() {
        MediaEditTask task = task(1L, 100L, MediaEditTask.STATUS_SUCCEEDED, "file-xyz");
        when(taskMapper.selectById(1L)).thenReturn(task);

        var vo = queryService.get(1L, 999L, true);

        assertEquals("/api/media/edit/tasks/1/download", vo.getVideoUrl());
    }

    @Test
    void get_runningTask_videoUrlNullEvenForOwner() {
        MediaEditTask task = task(1L, 100L, MediaEditTask.STATUS_RUNNING, null);
        when(taskMapper.selectById(1L)).thenReturn(task);

        var vo = queryService.get(1L, 100L, false);

        assertNull(vo.getVideoUrl(), "未完成时不应暴露下载端点");
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
        MediaEditTask task = task(1L, 100L, MediaEditTask.STATUS_SUCCEEDED, "file-xyz");
        when(taskMapper.selectById(1L)).thenReturn(task);

        assertThrows(BusinessException.class, () -> queryService.loadForDownload(1L, 999L, false));
    }

    @Test
    void loadForDownload_notSucceeded_throwsBadRequest() {
        MediaEditTask task = task(1L, 100L, MediaEditTask.STATUS_RUNNING, null);
        when(taskMapper.selectById(1L)).thenReturn(task);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> queryService.loadForDownload(1L, 100L, false));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void loadForDownload_ownerSucceeded_returnsTask() {
        MediaEditTask task = task(1L, 100L, MediaEditTask.STATUS_SUCCEEDED, "file-xyz");
        when(taskMapper.selectById(1L)).thenReturn(task);

        MediaEditTask loaded = queryService.loadForDownload(1L, 100L, false);

        assertEquals("file-xyz", loaded.getResultFileId());
    }

    // ---------- helpers ----------

    /** 造一个任务，edit_spec 含 1 片段 + BGM + 1 字幕。 */
    private MediaEditTask task(Long id, Long userId, String status, String resultFileId) {
        MediaEditTask t = new MediaEditTask();
        t.setId(id);
        t.setUserId(userId);
        t.setStatus(status);
        t.setResultFileId(resultFileId);
        t.setEditSpec("{\"clips\":[{\"fileId\":\"v1\"}],\"audio\":{\"fileId\":\"b1\"},\"texts\":[{\"content\":\"hi\"}]}");
        return t;
    }
}
