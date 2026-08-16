package com.superprogrammer.asset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.asset.dto.MediaTypeDef;
import com.superprogrammer.asset.dto.ProjectCreateRequest;
import com.superprogrammer.asset.dto.ProjectUpdateRequest;
import com.superprogrammer.asset.dto.ProjectVO;
import com.superprogrammer.asset.entity.Asset;
import com.superprogrammer.asset.entity.AssetBinding;
import com.superprogrammer.asset.entity.AssetProject;
import com.superprogrammer.asset.entity.AssetProjectMember;
import com.superprogrammer.asset.entity.AssetRoleLink;
import com.superprogrammer.asset.enums.AssetRole;
import com.superprogrammer.asset.mapper.AssetBindingMapper;
import com.superprogrammer.asset.mapper.AssetMapper;
import com.superprogrammer.asset.mapper.AssetProjectMapper;
import com.superprogrammer.asset.mapper.AssetProjectMemberMapper;
import com.superprogrammer.asset.mapper.AssetRoleLinkMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AssetProjectService 单测：CRUD + 两视图列表 + L4 级联软删 + L10 删桶资产归「通用」（plan §S2 验证）。
 */
@ExtendWith(MockitoExtension.class)
class AssetProjectServiceTest {

    private static final Long OWNER_ID = 10L;
    private static final Long EDITOR_ID = 20L;
    private static final Long PROJECT_ID = 1L;

    @Mock private AssetProjectMapper projectMapper;
    @Mock private AssetProjectMemberMapper memberMapper;
    @Mock private AssetMapper assetMapper;
    @Mock private AssetRoleLinkMapper roleLinkMapper;
    @Mock private AssetBindingMapper bindingMapper;
    @Mock private AssetAclService aclService;

    private AssetProjectService service;

    /** 填充 MP lambda 缓存，使 LambdaQueryWrapper 能把 SFunction 解析为列名（承 MemoryLifecycleServiceTest 范式）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AssetProject.class);
        TableInfoHelper.initTableInfo(assistant, AssetProjectMember.class);
        TableInfoHelper.initTableInfo(assistant, Asset.class);
        TableInfoHelper.initTableInfo(assistant, AssetRoleLink.class);
        TableInfoHelper.initTableInfo(assistant, AssetBinding.class);
    }

    @BeforeEach
    void setUp() {
        service = new AssetProjectService(projectMapper, memberMapper, assetMapper,
                roleLinkMapper, bindingMapper, aclService, new ObjectMapper());
    }

    @Test
    void create_setsOwnerAndDefaultRoles() {
        when(projectMapper.insert(any(AssetProject.class))).thenAnswer(inv -> {
            ((AssetProject) inv.getArgument(0)).setId(PROJECT_ID);
            return 1;
        });
        ProjectCreateRequest req = new ProjectCreateRequest();
        req.setName("武侠短片");
        req.setDescription("测试项目");
        ProjectVO vo = service.create(OWNER_ID, req);

        assertEquals(PROJECT_ID, vo.getId());
        assertEquals(OWNER_ID, vo.getOwnerId());
        assertEquals(AssetRole.OWNER, vo.getRole());
        assertEquals(AssetProjectService.DEFAULT_NARRATIVE_ROLES, vo.getNarrativeRoles());
        // V60：新建项目默认媒体类型受控词汇 5 项
        assertEquals(AssetProjectService.DEFAULT_MEDIA_TYPES, vo.getMediaTypes());

        ArgumentCaptor<AssetProject> captor = ArgumentCaptor.forClass(AssetProject.class);
        verify(projectMapper).insert(captor.capture());
        assertEquals(OWNER_ID, captor.getValue().getOwnerId());
    }

    @Test
    void create_blankName_throws() {
        ProjectCreateRequest req = new ProjectCreateRequest();
        req.setName("   ");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(OWNER_ID, req));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void list_combinesOwnedAndSharedWithRoles() {
        AssetProject owned = project(PROJECT_ID, OWNER_ID, "[\"人物\",\"道具\",\"场景\",\"风格\",\"通用\"]");
        AssetProject shared = project(2L, 99L, "[\"人物\",\"道具\",\"场景\",\"风格\",\"通用\"]");
        when(projectMapper.selectList(any())).thenReturn(List.of(owned), List.of(shared));
        AssetProjectMember mb = new AssetProjectMember();
        mb.setProjectId(2L);
        mb.setRole("EDITOR");
        when(memberMapper.selectList(any())).thenReturn(List.of(mb));

        List<ProjectVO> vos = service.list(OWNER_ID, false);

        assertEquals(2, vos.size());
        // owned → OWNER
        assertTrue(vos.stream().anyMatch(v -> v.getId() == PROJECT_ID && v.getRole() == AssetRole.OWNER));
        // shared → EDITOR
        assertTrue(vos.stream().anyMatch(v -> v.getId() == 2L && v.getRole() == AssetRole.EDITOR));
    }

    @Test
    void list_admin_returnsAllAsOwner() {
        when(projectMapper.selectList(any())).thenReturn(List.of(project(PROJECT_ID, OWNER_ID, "[\"人物\"]")));
        List<ProjectVO> vos = service.list(OWNER_ID, true);
        assertEquals(1, vos.size());
        assertEquals(AssetRole.OWNER, vos.get(0).getRole());
    }

    @Test
    void list_returnsPublicationSnapshotFields() {
        AssetProject published = project(PROJECT_ID, OWNER_ID, "[\"人物\"]");
        OffsetDateTime publishedAt = OffsetDateTime.parse("2026-08-10T10:00:00+08:00");
        published.setPublicPool(true);
        published.setPublicAccessMode(AssetProject.PUBLIC_ACCESS_APPROVAL_REQUIRED);
        published.setPublishedBy(99L);
        published.setPublishedAt(publishedAt);
        published.setPublishedByAdmin(true);
        when(projectMapper.selectList(any())).thenReturn(List.of(published), List.of());
        when(memberMapper.selectList(any())).thenReturn(List.of());

        ProjectVO result = service.list(OWNER_ID, false).get(0);

        assertEquals(true, result.getPublicPool());
        assertEquals(AssetProject.PUBLIC_ACCESS_APPROVAL_REQUIRED, result.getPublicAccessMode());
        assertEquals(99L, result.getPublishedBy());
        assertEquals(publishedAt, result.getPublishedAt());
        assertEquals(true, result.getPublishedByAdmin());
    }

    @Test
    void update_narrativeRolesRemoval_reassignsAssetsToFallback() {
        when(aclService.requireWrite(PROJECT_ID, OWNER_ID, false)).thenReturn(AssetRole.OWNER);
        AssetProject p = project(PROJECT_ID, OWNER_ID, "[\"人物\",\"道具\",\"通用\"]");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        // 项目内一个资产
        Asset a = new Asset();
        a.setId(5L);
        when(assetMapper.selectList(any())).thenReturn(List.of(a));
        // 该资产挂了「道具」
        AssetRoleLink link = new AssetRoleLink();
        link.setId(1L);
        link.setAssetId(5L);
        link.setRoleKey("道具");
        when(roleLinkMapper.selectList(any())).thenReturn(List.of(link));
        when(roleLinkMapper.selectCount(any())).thenReturn(0L); // 尚无「通用」link

        ProjectUpdateRequest req = new ProjectUpdateRequest();
        req.setNarrativeRoles(List.of("人物", "通用")); // 移除「道具」
        service.update(PROJECT_ID, OWNER_ID, false, req);

        // 删除「道具」link
        verify(roleLinkMapper).delete(any());
        // 确保补「通用」link（资产 5 归通用）
        ArgumentCaptor<AssetRoleLink> ins = ArgumentCaptor.forClass(AssetRoleLink.class);
        verify(roleLinkMapper).insert(ins.capture());
        assertEquals(5L, ins.getValue().getAssetId());
        assertEquals("通用", ins.getValue().getRoleKey());
    }

    @Test
    void update_viewerDenied() {
        when(aclService.requireWrite(PROJECT_ID, EDITOR_ID, false))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "需要编辑权限"));
        ProjectUpdateRequest req = new ProjectUpdateRequest();
        req.setName("x");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(PROJECT_ID, EDITOR_ID, false, req));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(projectMapper, never()).updateById(any());
    }

    @Test
    void delete_cascadeSoftDeletesAssetsMembersBindings() {
        when(aclService.requireManage(PROJECT_ID, OWNER_ID, false)).thenReturn(AssetRole.OWNER);
        Asset a = new Asset();
        a.setId(7L);
        when(assetMapper.selectList(any())).thenReturn(List.of(a));

        service.delete(PROJECT_ID, OWNER_ID, false);

        // 绑定 + 角色 + 资产 + 成员 + 项目 均软删
        verify(bindingMapper).delete(any());
        verify(roleLinkMapper).delete(any());
        verify(assetMapper).delete(any());
        verify(memberMapper).delete(any());
        verify(projectMapper).deleteById((java.io.Serializable) any());
    }

    @Test
    void delete_nonOwnerDenied() {
        when(aclService.requireManage(PROJECT_ID, EDITOR_ID, false))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "仅所有者"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete(PROJECT_ID, EDITOR_ID, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(projectMapper, never()).deleteById((java.io.Serializable) any());
    }

    private AssetProject project(long id, long ownerId, String rolesJson) {
        AssetProject p = new AssetProject();
        p.setId(id);
        p.setOwnerId(ownerId);
        p.setName("p" + id);
        p.setNarrativeRoles(rolesJson);
        return p;
    }

    // ==================== V60 §C1b：媒体类型受控词汇 + L10' 迁移 ====================

    @Test
    void update_mediaTypesRemoval_reassignsToSameCategory() {
        when(aclService.requireWrite(PROJECT_ID, OWNER_ID, false)).thenReturn(AssetRole.OWNER);
        // 旧受控词汇：IMAGE + 自定义 MAP(均 IMAGE 类别)
        AssetProject p = project(PROJECT_ID, OWNER_ID, "[\"人物\",\"通用\"]");
        p.setMediaTypes("[{\"key\":\"IMAGE\",\"category\":\"IMAGE\"},{\"key\":\"MAP\",\"category\":\"IMAGE\"}]");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(assetMapper.countByMediaType(PROJECT_ID, "MAP")).thenReturn(1L); // MAP 下有 1 资产

        ProjectUpdateRequest req = new ProjectUpdateRequest();
        req.setMediaTypes(List.of(new MediaTypeDef("IMAGE", "IMAGE"))); // 移除 MAP，保留 IMAGE
        service.update(PROJECT_ID, OWNER_ID, false, req);

        // MAP 下资产迁到同类别首个保留 type（IMAGE）
        verify(assetMapper).reassignMediaType(PROJECT_ID, "MAP", "IMAGE", OWNER_ID);
    }

    @Test
    void update_mediaTypesRemovalOrphanedCategory_blocks() {
        when(aclService.requireWrite(PROJECT_ID, OWNER_ID, false)).thenReturn(AssetRole.OWNER);
        AssetProject p = project(PROJECT_ID, OWNER_ID, "[\"人物\",\"通用\"]");
        p.setMediaTypes("[{\"key\":\"IMAGE\",\"category\":\"IMAGE\"}]");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(assetMapper.countByMediaType(PROJECT_ID, "IMAGE")).thenReturn(2L); // IMAGE 下仍有资产

        ProjectUpdateRequest req = new ProjectUpdateRequest();
        req.setMediaTypes(List.of(new MediaTypeDef("PROMPT", "TEXT"))); // 移除 IMAGE，无其他 IMAGE 类别可迁移
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(PROJECT_ID, OWNER_ID, false, req));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(assetMapper, never()).reassignMediaType(any(), any(), any(), any());
    }

    @Test
    void update_mediaTypesInvalidCategory_throws() {
        when(aclService.requireWrite(PROJECT_ID, OWNER_ID, false)).thenReturn(AssetRole.OWNER);
        AssetProject p = project(PROJECT_ID, OWNER_ID, "[\"通用\"]");
        p.setMediaTypes("[{\"key\":\"IMAGE\",\"category\":\"IMAGE\"}]");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);

        ProjectUpdateRequest req = new ProjectUpdateRequest();
        req.setMediaTypes(List.of(new MediaTypeDef("X", "WEIRD"))); // 非法 category
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(PROJECT_ID, OWNER_ID, false, req));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    // ==================== 2x第三轮C6：项目设置（成员打分开关 + 内容模式） ====================

    @Test
    void updateSettings_owner_switchBoth() {
        when(aclService.requireManage(PROJECT_ID, OWNER_ID, false)).thenReturn(AssetRole.OWNER);
        AssetProject p = project(PROJECT_ID, OWNER_ID, "[\"通用\"]");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);

        com.superprogrammer.asset.dto.ProjectSettingsRequest req =
                new com.superprogrammer.asset.dto.ProjectSettingsRequest();
        req.setMemberScoringEnabled(true);
        req.setContentMode(AssetProject.CONTENT_MODE_PERSONAL);
        ProjectVO vo = service.updateSettings(PROJECT_ID, OWNER_ID, false, req);

        ArgumentCaptor<AssetProject> captor = ArgumentCaptor.forClass(AssetProject.class);
        verify(projectMapper).updateById(captor.capture());
        assertEquals(Boolean.TRUE, captor.getValue().getMemberScoringEnabled());
        assertEquals(AssetProject.CONTENT_MODE_PERSONAL, captor.getValue().getContentMode());
        // VO 同步返新值（前端即时生效）
        assertEquals(Boolean.TRUE, vo.getMemberScoringEnabled());
        assertEquals(AssetProject.CONTENT_MODE_PERSONAL, vo.getContentMode());
    }

    @Test
    void updateSettings_editor_forbidden() {
        when(aclService.requireManage(PROJECT_ID, EDITOR_ID, false))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "仅项目所有者可执行此操作"));
        com.superprogrammer.asset.dto.ProjectSettingsRequest req =
                new com.superprogrammer.asset.dto.ProjectSettingsRequest();
        req.setContentMode(AssetProject.CONTENT_MODE_PERSONAL);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateSettings(PROJECT_ID, EDITOR_ID, false, req));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(projectMapper, never()).updateById(any());
    }

    @Test
    void updateSettings_invalidContentMode_400() {
        when(aclService.requireManage(PROJECT_ID, OWNER_ID, false)).thenReturn(AssetRole.OWNER);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PROJECT_ID, OWNER_ID, "[\"通用\"]"));
        com.superprogrammer.asset.dto.ProjectSettingsRequest req =
                new com.superprogrammer.asset.dto.ProjectSettingsRequest();
        req.setContentMode("MIXED");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateSettings(PROJECT_ID, OWNER_ID, false, req));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(projectMapper, never()).updateById(any());
    }

    @Test
    void updateSettings_allNull_noWrite() {
        // 局部更新语义：全 null 不改不写库
        when(aclService.requireManage(PROJECT_ID, OWNER_ID, false)).thenReturn(AssetRole.OWNER);
        AssetProject p = project(PROJECT_ID, OWNER_ID, "[\"通用\"]");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        ProjectVO vo = service.updateSettings(PROJECT_ID, OWNER_ID, false,
                new com.superprogrammer.asset.dto.ProjectSettingsRequest());
        verify(projectMapper, never()).updateById(any());
        assertEquals(Boolean.FALSE, vo.getMemberScoringEnabled()); // 空值 → FALSE 归一
    }
}
