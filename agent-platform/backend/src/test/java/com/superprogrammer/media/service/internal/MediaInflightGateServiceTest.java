package com.superprogrammer.media.service.internal;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2x 第三轮 C3 · 每用户媒体生成并发闸门单测（15x 三问落地）。
 * 断言：默认上限 2/3、超限 42904+计数回退、video/image 独立键、TTL 兜底、
 * 上限 0 不限制、设置热更（管理员改 1 生效）、Redis/DB 故障 fail-open、release 负值清零、对账吞异常。
 */
@ExtendWith(MockitoExtension.class)
class MediaInflightGateServiceTest {

    private static final String VIDEO_KEY = "inflight:media:video:u:1";
    private static final String IMAGE_KEY = "inflight:media:image:u:1";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private MediaGenTaskMapper taskMapper;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private MediaInflightGateService gate;

    @BeforeEach
    void setUp() {
        gate = new MediaInflightGateService(redisTemplate, systemSettingService, taskMapper);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(systemSettingService.getLong(SystemSettingService.MEDIA_CONCURRENT_VIDEO, 2L))
                .thenReturn(2L);
        lenient().when(systemSettingService.getLong(SystemSettingService.MEDIA_CONCURRENT_IMAGE, 3L))
                .thenReturn(3L);
    }

    // AC-C3：系统调用（userId=null）不过闸
    @Test
    void acquire_nullUser_notGated() {
        assertFalse(gate.acquire(null, MediaInflightGateService.KIND_VIDEO));
        verifyNoInteractions(redisTemplate);
    }

    // AC-C3：默认上限 2——第 1/2 个视频放行；第 1 个建 TTL 兜底
    @Test
    void acquire_video_withinDefaultLimit2_admittedWithTtl() {
        when(valueOperations.increment(VIDEO_KEY)).thenReturn(1L);

        assertTrue(gate.acquire(1L, MediaInflightGateService.KIND_VIDEO));
        verify(redisTemplate).expire(VIDEO_KEY, 30L, TimeUnit.MINUTES);

        when(valueOperations.increment(VIDEO_KEY)).thenReturn(2L);
        assertTrue(gate.acquire(1L, MediaInflightGateService.KIND_VIDEO));
    }

    // AC-C3：上限 2 时第 3 个视频 → 42904 + 计数回退（DECR），不占槽
    @Test
    void acquire_video_overLimit_rejectedWithRollback() {
        when(valueOperations.increment(VIDEO_KEY)).thenReturn(3L);
        when(valueOperations.decrement(VIDEO_KEY)).thenReturn(2L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> gate.acquire(1L, MediaInflightGateService.KIND_VIDEO));

        assertEquals(ErrorCode.MEDIA_CONCURRENT_LIMIT.getCode(), e.getCode());
        assertEquals(42904, e.getCode());
        verify(valueOperations).decrement(VIDEO_KEY);
        verify(redisTemplate, never()).delete(VIDEO_KEY); // 回退后计数 2 > 0 不删键
    }

    // AC-C3：video/image 独立计数互不影响（image 键独立，默认上限 3 → 第 3 个仍放行）
    @Test
    void acquire_imageIndependentCount_admittedUpTo3() {
        when(valueOperations.increment(IMAGE_KEY)).thenReturn(3L);

        assertTrue(gate.acquire(1L, MediaInflightGateService.KIND_IMAGE));
        verify(valueOperations, never()).increment(VIDEO_KEY);
    }

    // AC-C3：上限 0 = 不限制（管理员显式关闭）→ 不碰 Redis
    @Test
    void acquire_limitZero_notGatedNoRedisTouch() {
        when(systemSettingService.getLong(SystemSettingService.MEDIA_CONCURRENT_VIDEO, 2L)).thenReturn(0L);

        assertFalse(gate.acquire(1L, MediaInflightGateService.KIND_VIDEO));
        verifyNoInteractions(valueOperations);
    }

    // AC-C3：设置热更——管理员把 video 上限改 1（不重启生效）→ 第 2 个拒
    @Test
    void acquire_settingsHotChange_limit1_rejectsSecond() {
        when(systemSettingService.getLong(SystemSettingService.MEDIA_CONCURRENT_VIDEO, 2L)).thenReturn(1L);
        when(valueOperations.increment(VIDEO_KEY)).thenReturn(2L);
        when(valueOperations.decrement(VIDEO_KEY)).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> gate.acquire(1L, MediaInflightGateService.KIND_VIDEO));

        assertEquals(42904, e.getCode());
    }

    // AC-C3：设置读取失败（DB 抖动）→ 降级放行且不动计数（避免 INCR 后无人 release 的泄漏窗口）
    @Test
    void acquire_settingsReadFails_degradesWithoutTouchingCounter() {
        when(systemSettingService.getLong(SystemSettingService.MEDIA_CONCURRENT_VIDEO, 2L))
                .thenThrow(new RuntimeException("db down"));

        assertFalse(gate.acquire(1L, MediaInflightGateService.KIND_VIDEO));
        verifyNoInteractions(valueOperations);
    }

    // AC-C3 降级红线：Redis 故障 → 放行（可用性 > 强制力）
    @Test
    void acquire_redisDown_degradesOpen() {
        when(valueOperations.increment(anyString())).thenThrow(new RuntimeException("redis down"));

        assertFalse(assertDoesNotThrow(() -> gate.acquire(1L, MediaInflightGateService.KIND_VIDEO)));
    }

    // AC-C3：未知 kind 防御 → 降级放行
    @Test
    void acquire_unknownKind_degradesOpen() {
        assertFalse(gate.acquire(1L, "audio"));
        verifyNoInteractions(valueOperations);
    }

    // AC-C3：release 到 0 删键（主路径）
    @Test
    void release_decrementsToZero_deletesKey() {
        when(valueOperations.decrement(VIDEO_KEY)).thenReturn(0L);

        gate.release(1L, MediaInflightGateService.KIND_VIDEO);

        verify(redisTemplate).delete(VIDEO_KEY);
    }

    // AC-C3：release 错配场景（acquire 降级未计数而 worker 释放）→ floor 0 清零
    @Test
    void release_negativeMismatch_floorsAndDeletes() {
        when(valueOperations.decrement(VIDEO_KEY)).thenReturn(-1L);

        gate.release(1L, MediaInflightGateService.KIND_VIDEO);

        verify(redisTemplate).delete(VIDEO_KEY);
    }

    @Test
    void release_nullUser_noop() {
        gate.release(null, MediaInflightGateService.KIND_VIDEO);
        verifyNoInteractions(valueOperations);
    }

    // AC-C3：release Redis 故障 → 吞异常（绝不阻断 worker 收尾，TTL 30min 兜底）
    @Test
    void release_redisDown_swallowed() {
        when(valueOperations.decrement(anyString())).thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> gate.release(1L, MediaInflightGateService.KIND_VIDEO));
    }

    // AC-C3：对账——Redis 与 DB 漂移大仅 WARN 不抛；全程吞异常
    @Test
    void reconcile_driftAndFailures_swallowed() {
        when(taskMapper.countActiveByUserAndType()).thenReturn(List.of(
                Map.of("userId", 1L, "taskType", "TEXT2VIDEO", "cnt", 5L)));
        when(valueOperations.get(VIDEO_KEY)).thenReturn("1"); // |1-5|=4 > 2 → warn 分支
        assertDoesNotThrow(() -> gate.reconcile());

        // doThrow/doReturn 重挂桩：when() 会对已 thenThrow 的桩再调用一次而真抛，故此处必须用 do 系
        doThrow(new RuntimeException("db down")).when(taskMapper).countActiveByUserAndType();
        assertDoesNotThrow(() -> gate.reconcile());

        doReturn(List.of(Map.of("userId", 1L, "taskType", "TEXT2VIDEO", "cnt", 1L)))
                .when(taskMapper).countActiveByUserAndType();
        doThrow(new RuntimeException("redis down")).when(valueOperations).get(anyString());
        assertDoesNotThrow(() -> gate.reconcile());
    }

    // task_type → kind 映射（对账分组用）
    @Test
    void kindOfTaskType_mapsAllFourTypes() {
        assertEquals(MediaInflightGateService.KIND_IMAGE, MediaInflightGateService.kindOfTaskType("TEXT2IMAGE"));
        assertEquals(MediaInflightGateService.KIND_IMAGE, MediaInflightGateService.kindOfTaskType("IMAGE2IMAGE"));
        assertEquals(MediaInflightGateService.KIND_VIDEO, MediaInflightGateService.kindOfTaskType("TEXT2VIDEO"));
        assertEquals(MediaInflightGateService.KIND_VIDEO, MediaInflightGateService.kindOfTaskType("IMAGE2VIDEO"));
        assertNull(MediaInflightGateService.kindOfTaskType("EDIT"));
    }
}
