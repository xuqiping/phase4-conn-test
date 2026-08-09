package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemoryRosterVO;
import com.superprogrammer.chat.service.internal.MemoryRosterService;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 计划12 · I2 · MemoryRosterController 单测（Mockito，mock service + stub SecurityContext）。
 * <p>
 * 记忆二期 P1：recall-acl GET/PUT 端点随一期 ACL 矩阵下线（FR-006），本类只剩 roster 用例。
 * <ol>
 *   <li>roster：成员可见 / 非成员 403 / DEPARTED 403 / 未登录 401。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MemoryRosterControllerTest {

    @Mock MemoryRosterService rosterService;

    private MemoryRosterController controller;

    @BeforeEach
    void setUp() {
        controller = new MemoryRosterController(rosterService);
        loginAs(1L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Long uid) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(uid, null));
    }

    // ===== roster =====

    @Test
    void roster_member_returnsList() {
        when(rosterService.isMember(100L, 1L)).thenReturn(true);
        MemoryRosterVO vo = MemoryRosterVO.builder().userId(2L).role("ADMIN").build();
        when(rosterService.getRoster(100L)).thenReturn(List.of(vo));

        List<MemoryRosterVO> out = controller.roster(100L).getBody().getData();

        assertEquals(1, out.size());
        assertEquals(2L, out.get(0).getUserId());
    }

    @Test
    void roster_nonMember_forbidden() {
        when(rosterService.isMember(100L, 1L)).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class, () -> controller.roster(100L));
        assertEquals(403, ex.getCode());
        verify(rosterService, never()).getRoster(any());
    }

    @Test
    void roster_departed_forbidden() {
        // DEPARTED → isMember false → 403（已离开无项目读权）
        when(rosterService.isMember(100L, 1L)).thenReturn(false);
        assertThrows(BusinessException.class, () -> controller.roster(100L));
    }

    @Test
    void roster_notLogin_unauthorized() {
        SecurityContextHolder.clearContext();
        BusinessException ex = assertThrows(BusinessException.class, () -> controller.roster(100L));
        assertEquals(401, ex.getCode());
        verifyNoInteractions(rosterService);
    }
}
