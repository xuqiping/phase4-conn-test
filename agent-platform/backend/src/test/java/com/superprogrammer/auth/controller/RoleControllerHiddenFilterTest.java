package com.superprogrammer.auth.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.entity.Permission;
import com.superprogrammer.auth.entity.Role;
import com.superprogrammer.auth.mapper.PermissionMapper;
import com.superprogrammer.auth.mapper.RoleMapper;
import com.superprogrammer.auth.mapper.RolePermissionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V147：内置角色 llm_config（大模型配置员）+ 权限码 llm:config（配置大模型）
 * 在角色管理/分配角色/权限配置树中对 admin 隐藏（DB 行保留，仅预置账号持有）。
 * 过滤在下发给 MyBatis-Plus 的查询条件里，故断言 Wrapper 含「不等」条件与对应参数值。
 */
@ExtendWith(MockitoExtension.class)
class RoleControllerHiddenFilterTest {

    @Mock
    private RoleMapper roleMapper;
    @Mock
    private RolePermissionMapper rolePermissionMapper;
    @Mock
    private PermissionMapper permissionMapper;
    @InjectMocks
    private RoleController roleController;

    /** 纯单测无 MyBatis 上下文，Lambda 列解析需手动初始化实体表信息 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Role.class);
        TableInfoHelper.initTableInfo(assistant, Permission.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listRoles_excludesHiddenLlmConfigRole() {
        ArgumentCaptor<LambdaQueryWrapper<Role>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(roleMapper.selectPage(any(Page.class), captor.capture())).thenReturn(new Page<>());

        roleController.listRoles(1, 10);

        LambdaQueryWrapper<Role> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("<>");
        assertThat(wrapper.getParamNameValuePairs()).containsValue("llm_config");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listAllRoles_excludesHiddenLlmConfigRole() {
        ArgumentCaptor<LambdaQueryWrapper<Role>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(roleMapper.selectList(captor.capture())).thenReturn(List.of());

        roleController.listAllRoles();

        LambdaQueryWrapper<Role> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("<>");
        assertThat(wrapper.getParamNameValuePairs()).containsValue("llm_config");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listAllPermissions_excludesHiddenLlmConfigPermission() {
        ArgumentCaptor<LambdaQueryWrapper<Permission>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(permissionMapper.selectList(captor.capture())).thenReturn(List.of());

        roleController.listAllPermissions();

        LambdaQueryWrapper<Permission> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("<>");
        assertThat(wrapper.getParamNameValuePairs()).containsValue("llm:config");
    }
}
