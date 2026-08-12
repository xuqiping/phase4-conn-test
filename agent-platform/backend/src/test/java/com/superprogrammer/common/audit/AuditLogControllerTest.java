// agent-platform/backend/src/test/java/com/superprogrammer/common/audit/AuditLogControllerTest.java
package com.superprogrammer.common.audit;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.security.JwtAuthenticationFilter;
import com.superprogrammer.auth.security.PermissionEvaluator;
import com.superprogrammer.auth.security.RequirePermissionAspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 日志系统 LOG-FR-12：审计查询 API 契约测试。
 * <ul>
 *   <li>无认证 → 401；有认证无 system:audit:read → 403；</li>
 *   <li>有权限 → 200 + 分页结构；size &gt; 100 被强制截到 100（防拉全表）。</li>
 * </ul>
 */
@WebMvcTest(controllers = AuditLogController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(RequirePermissionAspect.class)
@EnableAspectJAutoProxy  // @WebMvcTest 不装配 AOP 自动代理，须显式开启否则切面不生效
class AuditLogControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AuditLogMapper auditLogMapper;
    @MockBean AuditChainVerifyService auditChainVerifyService;
    @MockBean PermissionEvaluator permissionEvaluator;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String username) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_admin")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void stubEmptyPage() {
        when(auditLogMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<AuditLogEntity> p = inv.getArgument(0);
            p.setRecords(List.of());
            p.setTotal(0);
            return p;
        });
    }

    @Test
    void list_noAuth_401() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/audit/logs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_noPermission_403() throws Exception {
        loginAs("plain-user");
        when(permissionEvaluator.hasPermission(any(), eq("system:audit:read"))).thenReturn(false);
        mvc.perform(get("/api/audit/logs"))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_withPermission_200AndPaged() throws Exception {
        loginAs("admin");
        when(permissionEvaluator.hasPermission(any(), eq("system:audit:read"))).thenReturn(true);
        stubEmptyPage();
        mvc.perform(get("/api/audit/logs")
                        .param("module", "auth")
                        .param("result", "FAIL")
                        .param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void list_sizeCappedAt100() throws Exception {
        loginAs("admin");
        when(permissionEvaluator.hasPermission(any(), eq("system:audit:read"))).thenReturn(true);
        stubEmptyPage();
        mvc.perform(get("/api/audit/logs").param("size", "9999"))
                .andExpect(status().isOk());

        ArgumentCaptor<Page<AuditLogEntity>> captor = ArgumentCaptor.forClass(Page.class);
        verify(auditLogMapper).selectPage(captor.capture(), any());
        assertEquals(100, captor.getValue().getSize());
    }

    @Test
    void list_usernameFilterAccepted_200() throws Exception {
        // 问题修复 #4：username 参数被接受且不报错（LIKE 条件由 MyBatis-Plus 参数化生成）
        loginAs("admin");
        when(permissionEvaluator.hasPermission(any(), eq("system:audit:read"))).thenReturn(true);
        stubEmptyPage();
        mvc.perform(get("/api/audit/logs").param("username", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
