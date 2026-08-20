package com.superprogrammer.canvas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.canvas.dto.CanvasVO;
import com.superprogrammer.canvas.dto.CanvasVersionVO;
import com.superprogrammer.canvas.entity.Canvas;
import com.superprogrammer.canvas.entity.CanvasVersion;
import com.superprogrammer.canvas.mapper.CanvasVersionMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CanvasVersionService 单测（2x 五轮「版本保存」）：
 * 快照缺省回落服务端当前、默认版本名、恢复前自动存版、超上限修剪、跨画布版本 id 拒识。
 * CanvasService/CanvasVersionMapper 用 Mockito，ObjectMapper 真实实例（nodeCount 解析）。
 */
@ExtendWith(MockitoExtension.class)
class CanvasVersionServiceTest {

    @Mock
    private CanvasService canvasService;

    @Mock
    private CanvasVersionMapper versionMapper;

    private CanvasVersionService service;

    private final AtomicLong idGen = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        service = new CanvasVersionService(canvasService, versionMapper, new ObjectMapper());
    }

    @Test
    void create_snapshotBlank_fallsBackToServerCurrent() {
        Canvas canvas = newCanvas(1L, 7L, "{\"nodes\":[{\"id\":\"a\"},{\"id\":\"b\"}]}");
        when(canvasService.loadOwned(1L, 7L, false)).thenReturn(canvas);
        when(versionMapper.insert(any(CanvasVersion.class))).thenAnswer(inv -> {
            ((CanvasVersion) inv.getArgument(0)).setId(idGen.incrementAndGet());
            return 1;
        });
        // 修剪查询：返回 1 条（≤ 上限不删）
        when(versionMapper.selectList(any())).thenReturn(List.of(newVersion(1L, 1L, "v")));

        CanvasVersionVO vo = service.create(1L, 7L, false, "里程碑", "  ");

        ArgumentCaptor<CanvasVersion> captor = ArgumentCaptor.forClass(CanvasVersion.class);
        verify(versionMapper).insert(captor.capture());
        assertEquals("{\"nodes\":[{\"id\":\"a\"},{\"id\":\"b\"}]}", captor.getValue().getSnapshot(),
                "snapshot 缺省必须定格服务端当前快照");
        assertEquals(2, captor.getValue().getNodeCount());
        assertEquals("里程碑", captor.getValue().getLabel());
        assertEquals(2, vo.getNodeCount());
    }

    @Test
    void create_blankLabel_getsTimestampDefault() {
        when(canvasService.loadOwned(1L, 7L, false)).thenReturn(newCanvas(1L, 7L, "{}"));
        when(versionMapper.insert(any(CanvasVersion.class))).thenAnswer(inv -> {
            ((CanvasVersion) inv.getArgument(0)).setId(idGen.incrementAndGet());
            return 1;
        });
        when(versionMapper.selectList(any())).thenReturn(List.of());

        service.create(1L, 7L, false, null, "{\"nodes\":[]}");

        ArgumentCaptor<CanvasVersion> captor = ArgumentCaptor.forClass(CanvasVersion.class);
        verify(versionMapper).insert(captor.capture());
        assertTrue(captor.getValue().getLabel().startsWith("版本 "),
                "空版本名补缺省时间戳名，实际=" + captor.getValue().getLabel());
    }

    @Test
    void list_omitsSnapshot_ordersHandledByWrapper() {
        when(canvasService.loadOwned(1L, 7L, false)).thenReturn(newCanvas(1L, 7L, "{}"));
        when(versionMapper.selectList(any())).thenReturn(List.of(newVersion(1L, 5L, "v5")));

        List<CanvasVersionVO> vos = service.list(1L, 7L, false);
        assertEquals(1, vos.size());
        assertNull(vos.get(0).getSnapshot(), "列表摘要不得返回 snapshot 重字段");
        assertEquals("v5", vos.get(0).getLabel());
    }

    @Test
    void restore_backsUpCurrentBeforeOverwrite() {
        Canvas canvas = newCanvas(1L, 7L, "{\"nodes\":[\"当前态\"]}");
        when(canvasService.loadOwned(1L, 7L, false)).thenReturn(canvas);
        when(versionMapper.selectById(55L)).thenReturn(newVersion(1L, 55L, "旧版"));
        when(versionMapper.insert(any(CanvasVersion.class))).thenAnswer(inv -> {
            ((CanvasVersion) inv.getArgument(0)).setId(idGen.incrementAndGet());
            return 1;
        });
        when(versionMapper.selectList(any())).thenReturn(List.of());

        CanvasVO vo = service.restore(1L, 55L, 7L, false);

        ArgumentCaptor<CanvasVersion> backup = ArgumentCaptor.forClass(CanvasVersion.class);
        verify(versionMapper, atLeastOnce()).insert(backup.capture());
        assertTrue(backup.getValue().getLabel().startsWith("恢复前自动存"),
                "恢复前必须自动存「恢复前」版本防误操作");
        // 覆盖后画布快照=版本快照，VO 带回前端重建
        assertEquals("{\"nodes\":[\"旧版内容\"]}", vo.getSnapshot());
        verify(canvasService).saveEntity(canvas);
    }

    @Test
    void restore_versionOfAnotherCanvas_notFound() {
        when(canvasService.loadOwned(1L, 7L, false)).thenReturn(newCanvas(1L, 7L, "{}"));
        // 版本挂在画布 2 下，用画布 1 访问 → NOT_FOUND（不泄露存在性）
        when(versionMapper.selectById(55L)).thenReturn(newVersion(2L, 55L, "他布版本"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.restore(1L, 55L, 7L, false));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void prune_removesOldestBeyondKeep() {
        when(canvasService.loadOwned(1L, 7L, false)).thenReturn(newCanvas(1L, 7L, "{}"));
        when(versionMapper.insert(any(CanvasVersion.class))).thenAnswer(inv -> {
            ((CanvasVersion) inv.getArgument(0)).setId(idGen.incrementAndGet());
            return 1;
        });
        // 修剪查询返回 KEEP+1 条（id 降序=新→旧）：最旧 1 条（id 1）应被软删
        List<CanvasVersion> over = IntStream.rangeClosed(1, CanvasVersionService.KEEP_PER_CANVAS + 1)
                .mapToObj(i -> newVersion(1L, (long) i, "v" + i))
                .collect(Collectors.toList());
        java.util.Collections.reverse(over);
        when(versionMapper.selectList(any())).thenReturn(over);

        service.create(1L, 7L, false, "触发修剪", "{\"nodes\":[]}");

        verify(versionMapper).deleteById(1L); // 降序排列后最旧= id 1
    }

    private Canvas newCanvas(long id, long userId, String snapshot) {
        Canvas c = new Canvas();
        c.setId(id);
        c.setUserId(userId);
        c.setName("c");
        c.setSnapshot(snapshot);
        return c;
    }

    private CanvasVersion newVersion(long canvasId, long id, String label) {
        CanvasVersion v = new CanvasVersion();
        v.setId(id);
        v.setCanvasId(canvasId);
        v.setLabel(label);
        v.setSnapshot("{\"nodes\":[\"旧版内容\"]}");
        v.setNodeCount(1);
        return v;
    }
}
