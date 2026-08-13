// agent-platform/backend/src/test/java/com/superprogrammer/auth/controller/UserControllerStatusTest.java
package com.superprogrammer.auth.controller;

import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.mapper.UserRoleMapper;
import com.superprogrammer.auth.service.AuthService;
import com.superprogrammer.common.security.BanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * UserController.updateUserStatus 防护单测（11x 加固 P1-C3）：
 * 枚举白名单/原因长度/防自封/防最后超管/revoke/restore 调用。
 */
@ExtendWith(MockitoExtension.class)
class UserControllerStatusTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private AuthService authService;
    @Mock
    private BanService banService;

    private UserController controller;

    @BeforeEach
    void setUp() {
        controller = new UserController(userMapper, userRoleMapper, authService, banService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Long operatorId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(operatorId, "op", List.of()));
    }

    private User activeUser(long id) {
        User u = new User();
        u.setId(id);
        u.setStatus("ACTIVE");
        return u;
    }

    @Test
    void illegalStatus_400() {
        ResponseEntity<?> resp = controller.updateUserStatus(2L, Map.of("status", "HACKED"));
        assertEquals(400, resp.getStatusCode().value());
        verifyNoInteractions(userMapper, banService);
    }

    @Test
    void reasonTooLong_400() {
        ResponseEntity<?> resp = controller.updateUserStatus(2L,
                Map.of("status", "BANNED", "reason", "x".repeat(129)));
        assertEquals(400, resp.getStatusCode().value());
        verifyNoInteractions(userMapper, banService);
    }

    @Test
    void userNotFound_404() {
        when(userMapper.selectById(99L)).thenReturn(null);
        ResponseEntity<?> resp = controller.updateUserStatus(99L, Map.of("status", "BANNED"));
        assertEquals(404, resp.getStatusCode().value());
        verifyNoInteractions(banService);
    }

    @Test
    void selfBan_403() {
        loginAs(1L);
        when(userMapper.selectById(1L)).thenReturn(activeUser(1L));

        ResponseEntity<?> resp = controller.updateUserStatus(1L, Map.of("status", "BANNED"));

        assertEquals(403, resp.getStatusCode().value());
        verify(userMapper, never()).updateById(any());
        verifyNoInteractions(banService);
    }

    @Test
    void lastActiveAdmin_403() {
        loginAs(1L);
        when(userMapper.selectById(2L)).thenReturn(activeUser(2L));
        when(userMapper.selectPermissionCodesByUserId(2L)).thenReturn(List.of("user:manage"));
        when(userMapper.countActiveByPermission("user:manage")).thenReturn(1L);

        ResponseEntity<?> resp = controller.updateUserStatus(2L, Map.of("status", "BANNED"));

        assertEquals(403, resp.getStatusCode().value());
        verify(userMapper, never()).updateById(any());
        verifyNoInteractions(banService);
    }

    @Test
    void banNormalUser_updatesAndRevokes() {
        loginAs(1L);
        User target = activeUser(2L);
        when(userMapper.selectById(2L)).thenReturn(target);
        when(userMapper.selectPermissionCodesByUserId(2L)).thenReturn(List.of());

        ResponseEntity<?> resp = controller.updateUserStatus(2L,
                Map.of("status", "BANNED", "reason", "恶意爬虫"));

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("BANNED", target.getStatus());
        assertEquals("恶意爬虫", target.getBanReason());
        verify(userMapper).updateById(target);
        verify(banService).revoke(2L, "BANNED");
        verify(banService, never()).restore(anyLong());
    }

    @Test
    void reactivate_clearsReasonAndRestores() {
        loginAs(1L);
        User target = activeUser(2L);
        target.setStatus("BANNED");
        target.setBanReason("旧原因");
        when(userMapper.selectById(2L)).thenReturn(target);

        ResponseEntity<?> resp = controller.updateUserStatus(2L, Map.of("status", "ACTIVE"));

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("ACTIVE", target.getStatus());
        assertNull(target.getBanReason());
        assertNull(target.getLockedUntil());
        verify(banService).restore(2L);
        verify(banService, never()).revoke(anyLong(), anyString());
    }

    @Test
    void adminBan_allowedWhenOtherAdminExists() {
        loginAs(1L);
        User target = activeUser(2L);
        when(userMapper.selectById(2L)).thenReturn(target);
        when(userMapper.selectPermissionCodesByUserId(2L)).thenReturn(List.of("user:manage"));
        when(userMapper.countActiveByPermission("user:manage")).thenReturn(2L); // 还有其他 admin

        ResponseEntity<?> resp = controller.updateUserStatus(2L,
                Map.of("status", "LOCKED", "reason", "暴破锁定"));

        assertEquals(200, resp.getStatusCode().value());
        verify(banService).revoke(2L, "LOCKED");
    }
}
