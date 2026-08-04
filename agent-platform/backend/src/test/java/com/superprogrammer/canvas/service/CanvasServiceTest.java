package com.superprogrammer.canvas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.canvas.dto.CanvasSaveRequest;
import com.superprogrammer.canvas.dto.CanvasVO;
import com.superprogrammer.canvas.entity.Canvas;
import com.superprogrammer.canvas.mapper.CanvasMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CanvasService 单测：ownership 硬过滤 + nodeCount 派生 + 默认名 + 保存/软删。
 * ObjectMapper 用真实实例（测 nodeCount 解析逻辑），CanvasMapper 用 Mockito。
 */
@ExtendWith(MockitoExtension.class)
class CanvasServiceTest {

    @Mock
    private CanvasMapper canvasMapper;

    private CanvasService service;

    @BeforeEach
    void setUp() {
        service = new CanvasService(canvasMapper, new ObjectMapper());
    }

    @Test
    void create_usesDefaultName_whenBlank() {
        when(canvasMapper.insert(any(Canvas.class))).thenAnswer(inv -> {
            ((Canvas) inv.getArgument(0)).setId(9L);
            return 1;
        });
        Canvas c = service.create(7L, "   ");
        assertEquals(9L, c.getId());
        assertEquals(7L, c.getUserId());
        assertEquals(CanvasService.DEFAULT_NAME, c.getName());
        assertEquals("{}", c.getSnapshot());
    }

    @Test
    void get_forbidden_whenNotOwner() {
        Canvas c = newCanvas(1L, 2L, "{}");
        when(canvasMapper.selectById(1L)).thenReturn(c);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.get(1L, 7L, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void get_adminBypass_andNodeCountDerived() {
        Canvas c = newCanvas(1L, 2L, "{\"nodes\":[{\"id\":\"a\"},{\"id\":\"b\"}]}");
        when(canvasMapper.selectById(1L)).thenReturn(c);
        CanvasVO vo = service.get(1L, 7L, true);
        assertEquals(2, vo.getNodeCount());
        assertEquals("{\"nodes\":[{\"id\":\"a\"},{\"id\":\"b\"}]}", vo.getSnapshot());
    }

    @Test
    void get_notFound_throws404() {
        when(canvasMapper.selectById(99L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.get(99L, 7L, false));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void list_omitsSnapshot_butKeepsNodeCount() {
        Canvas c = newCanvas(1L, 7L, "{\"nodes\":[1]}");
        c.setName("画布A");
        when(canvasMapper.selectList(any())).thenReturn(List.of(c));
        List<CanvasVO> vos = service.list(7L, false);
        assertEquals(1, vos.size());
        assertNull(vos.get(0).getSnapshot(), "列表摘要不得返回 snapshot 重字段");
        assertEquals(1, vos.get(0).getNodeCount());
    }

    @Test
    void save_updatesNameAndSnapshot() {
        Canvas c = newCanvas(1L, 7L, "{}");
        when(canvasMapper.selectById(1L)).thenReturn(c);
        when(canvasMapper.updateById(any(Canvas.class))).thenReturn(1);
        CanvasSaveRequest req = new CanvasSaveRequest();
        req.setName("新名字");
        req.setSnapshot("{\"nodes\":[{\"id\":\"x\"}]}");
        Canvas saved = service.save(1L, 7L, false, req);
        assertEquals("新名字", saved.getName());
        assertEquals("{\"nodes\":[{\"id\":\"x\"}]}", saved.getSnapshot());
        verify(canvasMapper).updateById(any(Canvas.class));
    }

    @Test
    void delete_softDeletesOwnedCanvas() {
        Canvas c = newCanvas(1L, 7L, "{}");
        when(canvasMapper.selectById(1L)).thenReturn(c);
        when(canvasMapper.deleteById(1L)).thenReturn(1);
        service.delete(1L, 7L, false);
        verify(canvasMapper).deleteById(1L);
    }

    private Canvas newCanvas(long id, long userId, String snapshot) {
        Canvas c = new Canvas();
        c.setId(id);
        c.setUserId(userId);
        c.setName("c");
        c.setSnapshot(snapshot);
        return c;
    }
}
