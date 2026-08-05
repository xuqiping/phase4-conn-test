package com.superprogrammer.asset.service;

import com.superprogrammer.asset.entity.AssetProject;
import com.superprogrammer.asset.entity.AssetProjectMember;
import com.superprogrammer.asset.enums.AssetRole;
import com.superprogrammer.asset.mapper.AssetProjectMapper;
import com.superprogrammer.asset.mapper.AssetProjectMemberMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AssetAclService 单测：权限咽喉点五角色矩阵（plan §S1 验证）。
 * 覆盖：owner / editor / viewer / admin 旁路 / 无关人 / 项目不存在。
 * 安全清单：viewer 写操作 403、editor 管理操作 403。
 */
@ExtendWith(MockitoExtension.class)
class AssetAclServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long OWNER_ID = 10L;
    private static final Long EDITOR_ID = 20L;
    private static final Long VIEWER_ID = 30L;
    private static final Long OUTSIDER_ID = 40L; // 无关人

    @Mock
    private AssetProjectMapper projectMapper;
    @Mock
    private AssetProjectMemberMapper memberMapper;

    private AssetAclService acl;

    @BeforeEach
    void setUp() {
        acl = new AssetAclService(projectMapper, memberMapper);
    }

    @Test
    void owner_returnsOwner() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        AssetRole role = acl.loadAccessible(PROJECT_ID, OWNER_ID, false);
        assertEquals(AssetRole.OWNER, role);
    }

    @Test
    void editorMember_returnsEditor() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        when(memberMapper.selectOne(any())).thenReturn(member(EDITOR_ID, "EDITOR"));
        AssetRole role = acl.loadAccessible(PROJECT_ID, EDITOR_ID, false);
        assertEquals(AssetRole.EDITOR, role);
    }

    @Test
    void viewerMember_returnsViewer() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        when(memberMapper.selectOne(any())).thenReturn(member(VIEWER_ID, "VIEWER"));
        AssetRole role = acl.loadAccessible(PROJECT_ID, VIEWER_ID, false);
        assertEquals(AssetRole.VIEWER, role);
    }

    @Test
    void adminBypass_returnsOwner_evenOutsider() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        // admin 旁路：非 owner、非 member 仍全权
        AssetRole role = acl.loadAccessible(PROJECT_ID, OUTSIDER_ID, true);
        assertEquals(AssetRole.OWNER, role);
    }

    @Test
    void outsider_forbidden() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        when(memberMapper.selectOne(any())).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> acl.loadAccessible(PROJECT_ID, OUTSIDER_ID, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void projectNotFound_throws404() {
        when(projectMapper.selectById(99L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> acl.loadAccessible(99L, OWNER_ID, false));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void requireWrite_viewerDenied() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        when(memberMapper.selectOne(any())).thenReturn(member(VIEWER_ID, "VIEWER"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> acl.requireWrite(PROJECT_ID, VIEWER_ID, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void requireWrite_editorAllowed() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        when(memberMapper.selectOne(any())).thenReturn(member(EDITOR_ID, "EDITOR"));
        assertEquals(AssetRole.EDITOR, acl.requireWrite(PROJECT_ID, EDITOR_ID, false));
    }

    @Test
    void requireManage_editorDenied() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        when(memberMapper.selectOne(any())).thenReturn(member(EDITOR_ID, "EDITOR"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> acl.requireManage(PROJECT_ID, EDITOR_ID, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void requireManage_ownerAllowed() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        assertEquals(AssetRole.OWNER, acl.requireManage(PROJECT_ID, OWNER_ID, false));
    }

    private AssetProject project(Long ownerId) {
        AssetProject p = new AssetProject();
        p.setId(PROJECT_ID);
        p.setOwnerId(ownerId);
        return p;
    }

    private AssetProjectMember member(Long userId, String role) {
        AssetProjectMember m = new AssetProjectMember();
        m.setProjectId(PROJECT_ID);
        m.setUserId(userId);
        m.setRole(role);
        return m;
    }
}
