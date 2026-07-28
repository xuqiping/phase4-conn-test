package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemoryRosterVO;
import com.superprogrammer.chat.dto.MemoryRecallAclRequest;
import com.superprogrammer.chat.dto.MemoryRecallAclVO;
import com.superprogrammer.chat.service.internal.MemoryRecallAclConfigService;
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
 * 覆盖（对齐 I2 plan 向量 14/15 出口条件）：
 * <ol>
 *   <li>roster：成员可见 / 非成员 403 / 未登录 401。</li>
 *   <li>recall-acl GET：owner·recall_admin 可见 / 非配权者 403。</li>
 *   <li>recall-acl PUT：配权者替换 + 审计 / 非配权者 403 / readerUserId 缺 BAD_REQUEST。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MemoryRosterControllerTest {

    @Mock MemoryRosterService rosterService;
    @Mock MemoryRecallAclConfigService aclConfigService;

    private MemoryRosterController controller;

    @BeforeEach
    void setUp() {
        controller = new MemoryRosterController(rosterService, aclConfigService);
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

    // ===== recall-acl GET =====

    @Test
    void getRecallAcl_configurable_returnsMatrix() {
        when(aclConfigService.isConfigurable(100L, 1L)).thenReturn(true);
        MemoryRecallAclVO vo = MemoryRecallAclVO.builder().readerUserId(2L).targetUserId(3L).build();
        when(aclConfigService.getMatrix(100L)).thenReturn(List.of(vo));

        List<MemoryRecallAclVO> out = controller.getRecallAcl(100L).getBody().getData();

        assertEquals(1, out.size());
        assertEquals(2L, out.get(0).getReaderUserId());
    }

    @Test
    void getRecallAcl_member_forbidden() {
        // 普通成员无配权 → 403（向量 14）
        when(aclConfigService.isConfigurable(100L, 1L)).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class, () -> controller.getRecallAcl(100L));
        assertEquals(403, ex.getCode());
        verify(aclConfigService, never()).getMatrix(any());
    }

    @Test
    void getRecallAcl_adminWithoutRecallAdmin_forbidden() {
        // admin 但 recall_admin=false → 403
        when(aclConfigService.isConfigurable(100L, 1L)).thenReturn(false);
        assertThrows(BusinessException.class, () -> controller.getRecallAcl(100L));
    }

    // ===== recall-acl PUT =====

    @Test
    void putRecallAcl_owner_replacesWithAudit() {
        when(aclConfigService.isConfigurable(100L, 1L)).thenReturn(true);
        when(aclConfigService.replaceAll(eq(100L), eq(2L), any(), eq(1L))).thenReturn(3);
        MemoryRecallAclRequest req = new MemoryRecallAclRequest();
        req.setReaderUserId(2L);
        req.setTargetUserIds(List.of(10L, 11L, 12L));

        Integer written = controller.putRecallAcl(100L, req).getBody().getData();

        assertEquals(3, written);
        // 审计：operatorId(1L) 透传作 created_by
        verify(aclConfigService).replaceAll(100L, 2L, req, 1L);
    }

    @Test
    void putRecallAcl_member_forbidden() {
        when(aclConfigService.isConfigurable(100L, 1L)).thenReturn(false);
        MemoryRecallAclRequest req = new MemoryRecallAclRequest();
        req.setReaderUserId(2L);
        BusinessException ex = assertThrows(BusinessException.class, () -> controller.putRecallAcl(100L, req));
        assertEquals(403, ex.getCode());
        verify(aclConfigService, never()).replaceAll(anyLong(), anyLong(), any(), any());
    }

    @Test
    void putRecallAcl_missingReader_badRequest() {
        when(aclConfigService.isConfigurable(100L, 1L)).thenReturn(true);
        MemoryRecallAclRequest req = new MemoryRecallAclRequest();  // readerUserId null
        BusinessException ex = assertThrows(BusinessException.class, () -> controller.putRecallAcl(100L, req));
        assertEquals(400, ex.getCode());
        verify(aclConfigService, never()).replaceAll(anyLong(), anyLong(), any(), any());
    }
}
