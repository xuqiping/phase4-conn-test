package com.superprogrammer.asset.service;

import com.superprogrammer.asset.entity.AssetProject;
import com.superprogrammer.asset.entity.AssetProjectMember;
import com.superprogrammer.asset.entity.AssetPublicAccessRequest;
import com.superprogrammer.asset.enums.AssetRole;
import com.superprogrammer.asset.mapper.AssetProjectMapper;
import com.superprogrammer.asset.mapper.AssetProjectMemberMapper;
import com.superprogrammer.asset.mapper.AssetPublicAccessRequestMapper;
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
    @Mock
    private AssetPublicAccessRequestMapper publicRequestMapper;

    private AssetAclService acl;

    @BeforeEach
    void setUp() {
        acl = new AssetAclService(projectMapper, memberMapper, publicRequestMapper);
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
    void publicOpen_outsiderGetsReadOnlyViewer() {
        AssetProject project = project(OWNER_ID);
        project.setPublicPool(true);
        project.setPublicAccessMode(AssetProject.PUBLIC_ACCESS_OPEN);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(memberMapper.selectOne(any())).thenReturn(null);

        assertEquals(AssetRole.VIEWER, acl.loadAccessible(PROJECT_ID, OUTSIDER_ID, false));
        assertThrows(BusinessException.class,
                () -> acl.requireWrite(PROJECT_ID, OUTSIDER_ID, false));
    }

    @Test
    void publicApproval_approvedGetsViewer_pendingDenied() {
        AssetProject project = project(OWNER_ID);
        project.setPublicPool(true);
        project.setPublicAccessMode(AssetProject.PUBLIC_ACCESS_APPROVAL_REQUIRED);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(memberMapper.selectOne(any())).thenReturn(null);
        AssetPublicAccessRequest approved = new AssetPublicAccessRequest();
        approved.setStatus(AssetPublicAccessRequest.STATUS_APPROVED);
        when(publicRequestMapper.selectOne(any())).thenReturn(
                approved, (AssetPublicAccessRequest) null);

        assertEquals(AssetRole.VIEWER, acl.loadAccessible(PROJECT_ID, OUTSIDER_ID, false));

        assertThrows(BusinessException.class,
                () -> acl.loadAccessible(PROJECT_ID, OUTSIDER_ID, false));
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

    // ---------- requireAssetOperate（2x第三轮C6 · PERSONAL 所有权隔离矩阵） ----------

    @Test
    void personal_editorOtherCreator_forbidden403() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(personalProject());
        when(memberMapper.selectOne(any())).thenReturn(member(EDITOR_ID, "EDITOR"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> acl.requireAssetOperate(asset(77L, 999L), EDITOR_ID, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void personal_editorOwnCreator_allowed() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(personalProject());
        when(memberMapper.selectOne(any())).thenReturn(member(EDITOR_ID, "EDITOR"));
        assertEquals(AssetRole.EDITOR, acl.requireAssetOperate(asset(77L, EDITOR_ID), EDITOR_ID, false));
    }

    @Test
    void personal_ownerAllowed_onOthersContent() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(personalProject());
        assertEquals(AssetRole.OWNER, acl.requireAssetOperate(asset(77L, 999L), OWNER_ID, false));
    }

    @Test
    void personal_adminBypassAllowed() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(personalProject());
        assertEquals(AssetRole.OWNER, acl.requireAssetOperate(asset(77L, 999L), OUTSIDER_ID, true));
    }

    @Test
    void shared_editorOtherCreator_allowed_zeroRegression() {
        AssetProject shared = project(OWNER_ID);
        shared.setContentMode(AssetProject.CONTENT_MODE_SHARED);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(shared);
        when(memberMapper.selectOne(any())).thenReturn(member(EDITOR_ID, "EDITOR"));
        assertEquals(AssetRole.EDITOR, acl.requireAssetOperate(asset(77L, 999L), EDITOR_ID, false));
    }

    @Test
    void personal_viewerDeniedByWriteRule_first() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(personalProject());
        when(memberMapper.selectOne(any())).thenReturn(member(VIEWER_ID, "VIEWER"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> acl.requireAssetOperate(asset(77L, VIEWER_ID), VIEWER_ID, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void personal_nullCreator_editorForbidden_failClosed() {
        // 存量 NULL createdBy（回填兜底后理论不存在）：fail-closed 视为他人内容
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(personalProject());
        when(memberMapper.selectOne(any())).thenReturn(member(EDITOR_ID, "EDITOR"));
        assertThrows(BusinessException.class,
                () -> acl.requireAssetOperate(asset(77L, null), EDITOR_ID, false));
    }

    @Test
    void personal_projectMissing_404() {
        when(projectMapper.selectById(99L)).thenReturn(null);
        com.superprogrammer.asset.entity.Asset orphan = new com.superprogrammer.asset.entity.Asset();
        orphan.setId(77L);
        orphan.setProjectId(99L); // 项目已删（selectById null）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> acl.requireAssetOperate(orphan, EDITOR_ID, false));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    private AssetProject personalProject() {
        AssetProject p = project(OWNER_ID);
        p.setContentMode(AssetProject.CONTENT_MODE_PERSONAL);
        return p;
    }

    private com.superprogrammer.asset.entity.Asset asset(Long assetId, Long creatorId) {
        com.superprogrammer.asset.entity.Asset a = new com.superprogrammer.asset.entity.Asset();
        if (assetId != null) {
            a.setId(assetId);
        }
        a.setProjectId(PROJECT_ID);
        a.setCreatedBy(creatorId);
        return a;
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
