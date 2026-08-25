// agent-platform/backend/src/test/java/com/superprogrammer/auth/controller/UserControllerRemarkTest.java
package com.superprogrammer.auth.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.mapper.UserRoleMapper;
import com.superprogrammer.auth.service.AuthService;
import com.superprogrammer.common.security.BanService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * D1（12x-1）单测：
 * 1. LIKE 通配符转义（escapeLike——防「输入 % 全表命中」）；
 * 2. 管理员改备注端点（长度边界/404/落 wrapper）；
 * 3. listUsers keyword/status 入 wrapper（捕获断言传入 selectPage 的 wrapper 非空——
 *    三字段 OR 的 SQL 组装由 MP 保证，此处钉住「带 keyword 时不丢筛选」的接线）。
 */
@ExtendWith(MockitoExtension.class)
class UserControllerRemarkTest {

    /** getSqlSegment 解析 lambda 列名需 TableInfo 缓存（纯单测无 Spring 容器，手动初始化） */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
    }

    @Mock
    private UserMapper userMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private AuthService authService;
    @Mock
    private BanService banService;

    @InjectMocks
    private UserController controller;

    @Test
    void escapeLike_wildcardsAndBackslashPrefixed() {
        assertEquals("\\%100\\%", UserController.escapeLike("%100%"));
        assertEquals("A\\_班", UserController.escapeLike("A_班"));
        assertEquals("C:\\\\path", UserController.escapeLike("C:\\path"));
        assertEquals("A 班", UserController.escapeLike("A 班"));
    }

    @Test
    void remarkTooLong_400() {
        var resp = controller.updateUserRemark(2L, Map.of("remark", "长".repeat(129)));
        assertEquals(400, resp.getStatusCode().value());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void remark_userNotFound_404() {
        when(userMapper.selectById(99L)).thenReturn(null);
        var resp = controller.updateUserRemark(99L, Map.of("remark", "A 班"));
        assertEquals(404, resp.getStatusCode().value());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void remark_savedViaUpdateWrapper() {
        User target = new User();
        target.setId(2L);
        target.setUsername("u2");
        when(userMapper.selectById(2L)).thenReturn(target);

        var resp = controller.updateUserRemark(2L, Map.of("remark", " A 班 "));

        assertEquals(200, resp.getStatusCode().value());
        // 显式 wrapper 写（updateById 的 NOT_NULL 策略对「清除=null」会静默跳过）
        verify(userMapper).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
    }

    @Test
    void listUsers_keywordAndStatus_passIntoQuery() {
        User u = new User();
        u.setId(2L);
        u.setUsername("u2");
        u.setStatus("ACTIVE");
        Page<User> page = new Page<>(1, 10);
        page.setRecords(List.of(u));
        page.setTotal(1);
        when(userMapper.selectPage(any(), any(LambdaQueryWrapper.class))).thenReturn(page);
        // VO 组装走 authService.getCurrentUser（已另有测试，此处只钉筛选接线）
        when(authService.getCurrentUser(2L)).thenReturn(null);

        var resp = controller.listUsers(1, 10, "A 班", "ACTIVE");

        assertEquals(200, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<User>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(userMapper).selectPage(any(Page.class), captor.capture());
        // 带 keyword/status 的非空条件段（防「筛选拼了但没接到查询上」回归）
        String sqlSegment = captor.getValue().getSqlSegment();
        assertTrue(sqlSegment.contains("LIKE"), "keyword 三字段 OR 应含 LIKE：实际 " + sqlSegment);
        assertTrue(sqlSegment.contains("="), "status 应为等值条件：实际 " + sqlSegment);
    }
}
