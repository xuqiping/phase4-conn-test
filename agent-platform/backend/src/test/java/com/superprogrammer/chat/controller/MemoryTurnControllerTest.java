package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemoryRawBatchDeleteRequest;
import com.superprogrammer.chat.entity.MemoryTurn;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划12 · C · 流水账控制器单测（Mockito，SecurityContext 直设）。
 * 出口对齐 plan C + 设计 §6：raw 仅本人可见(向量7) / 批量 ownership 过滤返实际条数(向量13) / 不存在统一 NOT_FOUND 防探测。
 */
@ExtendWith(MockitoExtension.class)
class MemoryTurnControllerTest {

    @Mock private MemoryTurnMapper turnMapper;
    @Mock private com.superprogrammer.chat.service.internal.MemoryTurnDeleteCascadeService cascadeService;

    @InjectMocks private MemoryTurnController controller;

    private void loginAs(Long uid) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(uid, null));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("listRaw 返回本人 raw（VO 含 rawContent）")
    void listRaw_returnsOwnRaw() {
        loginAs(1L);
        MemoryTurn t = new MemoryTurn();
        t.setId(10L);
        t.setUserId(1L);
        t.setDirection("INPUT");
        t.setRawContent("我住萧山");
        t.setGenDone(false);
        when(turnMapper.selectList(any())).thenReturn(List.of(t));

        var resp = controller.listRaw();

        assertEquals(1, resp.getBody().getData().size());
        assertEquals("我住萧山", resp.getBody().getData().get(0).getRawContent());
        assertEquals("INPUT", resp.getBody().getData().get(0).getDirection());
    }

    @Test
    @DisplayName("delete 不存在 → NOT_FOUND（不泄露存在性）")
    void delete_notFound() {
        loginAs(1L);
        when(turnMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.delete(99L));
        assertEquals(404, ex.getCode());
        verify(turnMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("delete 非 owner → NOT_FOUND（统一语义防探测，不删）")
    void delete_nonOwner_forbidden() {
        loginAs(1L);
        MemoryTurn others = new MemoryTurn();
        others.setId(50L);
        others.setUserId(2L);  // 别人的
        when(turnMapper.selectById(50L)).thenReturn(others);

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.delete(50L));
        assertEquals(404, ex.getCode(), "非 owner 统一 NOT_FOUND 不区分存在性");
        verify(turnMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("delete owner → 软删 + ok")
    void delete_owner_ok() {
        loginAs(1L);
        MemoryTurn mine = new MemoryTurn();
        mine.setId(50L);
        mine.setUserId(1L);
        mine.setDirection("INPUT");
        when(turnMapper.selectById(50L)).thenReturn(mine);

        var resp = controller.delete(50L);

        verify(turnMapper).deleteById(50L);
        assertTrue(resp.getStatusCode().is2xxSuccessful());
    }

    @Test
    @DisplayName("batchDelete 返 mapper.delete 实际条数（ownership 过滤在 wrapper）")
    void batchDelete_returnsActualCount() {
        loginAs(1L);
        // 请求 5 个 id，mapper.delete(wrapper) 只删本人 3 条（wrapper 过滤模拟）
        when(turnMapper.delete(any())).thenReturn(3);

        MemoryRawBatchDeleteRequest req = new MemoryRawBatchDeleteRequest();
        req.setIds(List.of(1L, 2L, 3L, 4L, 5L));

        var resp = controller.batchDeleteRaw(req);

        assertEquals(3, resp.getBody().getData(), "返实际有权删除条数（向量 13）");
    }

    @Test
    @DisplayName("未登录 → UNAUTHORIZED")
    void notLoggedIn_unauthorized() {
        // 不 loginAs
        BusinessException ex = assertThrows(BusinessException.class, () -> controller.listRaw());
        assertEquals(401, ex.getCode());
    }
}
