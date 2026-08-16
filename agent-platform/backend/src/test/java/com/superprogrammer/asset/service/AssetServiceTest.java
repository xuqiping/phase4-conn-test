package com.superprogrammer.asset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.asset.dto.AssetCreateRequest;
import com.superprogrammer.asset.dto.AssetCopyRequest;
import com.superprogrammer.asset.dto.AssetUpdateRequest;
import com.superprogrammer.asset.dto.AssetVO;
import com.superprogrammer.asset.dto.MatrixCountVO;
import com.superprogrammer.asset.dto.StoryboardSaveRequest;
import com.superprogrammer.asset.dto.VersionCreateRequest;
import com.superprogrammer.asset.entity.Asset;
import com.superprogrammer.asset.entity.AssetProject;
import com.superprogrammer.asset.entity.AssetRoleLink;
import com.superprogrammer.asset.entity.AssetVersion;
import com.superprogrammer.asset.mapper.AssetMapper;
import com.superprogrammer.asset.mapper.AssetProjectMapper;
import com.superprogrammer.asset.mapper.AssetRoleLinkMapper;
import com.superprogrammer.asset.mapper.AssetVersionMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.file.service.StoredFile;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AssetService 单测：CRUD + 矩阵筛选/搜索 + 计数聚合 + N+1 防护 + 受控词汇校验（plan §S4 验证）。
 */
@ExtendWith(MockitoExtension.class)
class AssetServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long OWNER_ID = 10L;
    private static final Long EDITOR_ID = 20L;
    private static final Long VIEWER_ID = 30L;

    @Mock private AssetMapper assetMapper;
    @Mock private AssetVersionMapper versionMapper;
    @Mock private AssetRoleLinkMapper roleLinkMapper;
    @Mock private AssetProjectMapper projectMapper;
    @Mock private AssetAclService aclService;
    @Mock private FileStorageService fileStorageService;
    @Mock private AssetVersionService versionService;
    @Mock private com.superprogrammer.asset.mapper.AssetScoreMapper scoreMapper;
    @Mock private com.superprogrammer.auth.mapper.UserMapper userMapper;

    private AssetService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Asset.class);
        TableInfoHelper.initTableInfo(assistant, AssetRoleLink.class);
        TableInfoHelper.initTableInfo(assistant, AssetVersion.class);
        TableInfoHelper.initTableInfo(assistant, AssetProject.class);
    }

    @BeforeEach
    void setUp() {
        service = new AssetService(assetMapper, versionMapper, roleLinkMapper, projectMapper, aclService,
                new ObjectMapper(), fileStorageService, versionService, scoreMapper, userMapper);
    }

    @Test
    void create_textAsset_insertsVersion1AndRoleLinks() {
        when(aclService.requireWrite(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(assetMapper.insert(any(Asset.class))).thenAnswer(inv -> {
            ((Asset) inv.getArgument(0)).setId(100L);
            return 1;
        });
        AssetCreateRequest req = new AssetCreateRequest();
        req.setMediaType(Asset.MEDIA_PROMPT);
        req.setName("文生图提示词");
        req.setContent("{\"body\":\"a cat\"}");
        req.setRoleKeys(List.of("人物"));
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(projectWithRoles());

        AssetVO vo = service.create(PROJECT_ID, OWNER_ID, false, req);

        assertEquals(100L, vo.getId());
        assertEquals(Asset.MEDIA_PROMPT, vo.getMediaType());
        assertEquals(1, vo.getCurrentVersion());
        // 版本 1 落库
        ArgumentCaptor<AssetVersion> vc = ArgumentCaptor.forClass(AssetVersion.class);
        verify(versionMapper).insert(vc.capture());
        assertEquals(100L, vc.getValue().getAssetId());
        assertEquals(1, vc.getValue().getVersion());
        // 角色挂载（清旧 + 插新）
        verify(roleLinkMapper).delete(any());
        ArgumentCaptor<AssetRoleLink> rc = ArgumentCaptor.forClass(AssetRoleLink.class);
        verify(roleLinkMapper).insert(rc.capture());
        assertEquals("人物", rc.getValue().getRoleKey());
    }

    @Test
    void create_textAsset_blankContent_throws() {
        when(aclService.requireWrite(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        AssetCreateRequest req = new AssetCreateRequest();
        req.setMediaType(Asset.MEDIA_SCRIPT);
        req.setName("剧本");
        req.setContent("  ");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(PROJECT_ID, OWNER_ID, false, req));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void create_viewerDenied() {
        when(aclService.requireWrite(PROJECT_ID, VIEWER_ID, false))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "需编辑权限"));
        AssetCreateRequest req = new AssetCreateRequest();
        req.setMediaType(Asset.MEDIA_PROMPT);
        req.setName("x");
        req.setContent("{}");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(PROJECT_ID, VIEWER_ID, false, req));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(assetMapper, never()).insert(any());
    }

    @Test
    void create_roleNotInVocab_throws() {
        when(aclService.requireWrite(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(assetMapper.insert(any(Asset.class))).thenAnswer(inv -> {
            ((Asset) inv.getArgument(0)).setId(101L);
            return 1;
        });
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(projectWithRoles());
        AssetCreateRequest req = new AssetCreateRequest();
        req.setMediaType(Asset.MEDIA_PROMPT);
        req.setName("x");
        req.setContent("{}");
        req.setRoleKeys(List.of("不存在的桶"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(PROJECT_ID, OWNER_ID, false, req));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    // ---------- F19 公众池资产复制 ----------

    @Test
    void copyCurrent_sourceWithoutReadPermission_createsNothing() {
        Asset source = asset(100L, "MAP");
        source.setMediaCategory(Asset.CATEGORY_IMAGE);
        when(assetMapper.selectById(100L)).thenReturn(source);
        when(aclService.loadAccessible(PROJECT_ID, VIEWER_ID, false))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "无权读取源项目"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.copyCurrent(100L, VIEWER_ID, false, copyRequest(2L)));

        assertEquals(ErrorCode.FORBIDDEN.getCode(), error.getCode());
        verify(aclService, never()).requireWrite(eq(2L), any(), anyBoolean());
        verify(assetMapper, never()).insert(any());
    }

    @Test
    void copyCurrent_targetWithoutWritePermission_createsNothing() {
        Asset source = asset(100L, "MAP");
        source.setMediaCategory(Asset.CATEGORY_IMAGE);
        when(assetMapper.selectById(100L)).thenReturn(source);
        when(aclService.loadAccessible(PROJECT_ID, VIEWER_ID, false)).thenReturn(null);
        when(aclService.requireWrite(2L, VIEWER_ID, false))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "目标项目不可写"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.copyCurrent(100L, VIEWER_ID, false, copyRequest(2L)));

        assertEquals(ErrorCode.FORBIDDEN.getCode(), error.getCode());
        verify(assetMapper, never()).insert(any());
    }

    @Test
    void copyCurrent_missingCurrentVersion_createsNothing() {
        Asset source = asset(100L, "MAP");
        source.setMediaCategory(Asset.CATEGORY_IMAGE);
        source.setCurrentVersion(3);
        when(assetMapper.selectById(100L)).thenReturn(source);
        when(aclService.loadAccessible(PROJECT_ID, VIEWER_ID, false)).thenReturn(null);
        when(aclService.requireWrite(2L, VIEWER_ID, false)).thenReturn(null);
        when(assetMapper.lockByIdForUpdate(100L)).thenReturn(100L);
        when(versionMapper.selectOne(any())).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.copyCurrent(100L, VIEWER_ID, false, copyRequest(2L)));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), error.getCode());
        verify(assetMapper, never()).insert(any());
    }

    @Test
    void copyCurrent_copiesOnlyCurrentSnapshotAndCompatibleRoles() {
        Asset source = asset(100L, "MAP");
        source.setMediaCategory(Asset.CATEGORY_IMAGE);
        source.setCurrentVersion(3);
        source.setName("World map");
        source.setDescription("source description");
        source.setTags("[\"tag-a\"]");
        source.setGenMeta("{\"seed\":7}");
        source.setContent("{\"stale\":true}");
        AssetVersion current = new AssetVersion();
        current.setAssetId(100L);
        current.setVersion(3);
        current.setFileId("file-shared-1");
        current.setContent("{\"snapshot\":3}");
        AssetProject target = targetProject(2L, "[\"CHARACTER\",\"SCENE\"]", "[]");
        when(assetMapper.selectById(100L)).thenReturn(source);
        when(assetMapper.lockByIdForUpdate(100L)).thenReturn(100L);
        when(aclService.loadAccessible(PROJECT_ID, VIEWER_ID, false)).thenReturn(null);
        when(aclService.requireWrite(2L, VIEWER_ID, false)).thenReturn(null);
        when(versionMapper.selectOne(any())).thenReturn(current);
        when(projectMapper.selectById(2L)).thenReturn(target);
        when(roleLinkMapper.selectList(any())).thenReturn(List.of(
                roleLink(100L, "CHARACTER"), roleLink(100L, "PROP")));
        when(assetMapper.insert(any(Asset.class))).thenAnswer(invocation -> {
            ((Asset) invocation.getArgument(0)).setId(200L);
            return 1;
        });

        AssetVO result = service.copyCurrent(100L, VIEWER_ID, false, copyRequest(2L));

        assertEquals(200L, result.getId());
        assertEquals(2L, result.getProjectId());
        assertEquals(1, result.getCurrentVersion());
        assertEquals(Asset.STATUS_DRAFT, result.getStatus());
        assertEquals("{\"snapshot\":3}", result.getContent());
        assertEquals("file-shared-1", result.getFileId());

        ArgumentCaptor<Asset> assetCaptor = ArgumentCaptor.forClass(Asset.class);
        verify(assetMapper).insert(assetCaptor.capture());
        assertEquals("MAP", assetCaptor.getValue().getMediaType());
        assertEquals("{\"snapshot\":3}", assetCaptor.getValue().getContent());
        assertEquals("{\"seed\":7}", assetCaptor.getValue().getGenMeta());

        ArgumentCaptor<AssetVersion> versionCaptor = ArgumentCaptor.forClass(AssetVersion.class);
        verify(versionMapper).insert(versionCaptor.capture());
        assertEquals(200L, versionCaptor.getValue().getAssetId());
        assertEquals(1, versionCaptor.getValue().getVersion());
        assertEquals("file-shared-1", versionCaptor.getValue().getFileId());
        assertEquals("{\"snapshot\":3}", versionCaptor.getValue().getContent());

        ArgumentCaptor<AssetRoleLink> roleCaptor = ArgumentCaptor.forClass(AssetRoleLink.class);
        verify(roleLinkMapper).insert(roleCaptor.capture());
        assertEquals("CHARACTER", roleCaptor.getValue().getRoleKey());
        verify(fileStorageService, never()).store(any(), any(), any());
        assertTrue(target.getMediaTypes().contains("\"key\":\"MAP\""));
        assertTrue(target.getMediaTypes().contains("\"category\":\"IMAGE\""));
        verify(projectMapper).updateById(target);
    }

    @Test
    void copyCurrent_versionInsertFailure_doesNotContinueToRoles() {
        Asset source = asset(100L, Asset.MEDIA_IMAGE);
        source.setMediaCategory(Asset.CATEGORY_IMAGE);
        AssetVersion current = new AssetVersion();
        current.setAssetId(100L);
        current.setVersion(1);
        current.setFileId("file-shared-1");
        current.setContent("{}");
        AssetProject target = targetProject(2L, "[\"CHARACTER\"]",
                "[{\"key\":\"图片\",\"category\":\"IMAGE\"}]");
        when(assetMapper.selectById(100L)).thenReturn(source);
        when(aclService.loadAccessible(PROJECT_ID, VIEWER_ID, false)).thenReturn(null);
        when(aclService.requireWrite(2L, VIEWER_ID, false)).thenReturn(null);
        when(assetMapper.lockByIdForUpdate(100L)).thenReturn(100L);
        when(versionMapper.selectOne(any())).thenReturn(current);
        when(projectMapper.selectById(2L)).thenReturn(target);
        when(roleLinkMapper.selectList(any())).thenReturn(List.of(roleLink(100L, "CHARACTER")));
        when(assetMapper.insert(any(Asset.class))).thenAnswer(invocation -> {
            ((Asset) invocation.getArgument(0)).setId(200L);
            return 1;
        });
        when(versionMapper.insert(any())).thenThrow(new IllegalStateException("version insert failed"));

        assertThrows(IllegalStateException.class,
                () -> service.copyCurrent(100L, VIEWER_ID, false, copyRequest(2L)));

        verify(roleLinkMapper, never()).insert(any());
    }

    @Test
    void copyCurrent_sameMediaTypeWithDifferentCategory_returnsConflict() {
        Asset source = asset(100L, "MAP");
        source.setMediaCategory(Asset.CATEGORY_IMAGE);
        source.setCurrentVersion(2);
        AssetVersion current = new AssetVersion();
        current.setAssetId(100L);
        current.setVersion(2);
        current.setContent("{}");
        AssetProject target = targetProject(2L, "[]",
                "[{\"key\":\"MAP\",\"category\":\"TEXT\"}]");
        when(assetMapper.selectById(100L)).thenReturn(source);
        when(aclService.loadAccessible(PROJECT_ID, VIEWER_ID, false)).thenReturn(null);
        when(aclService.requireWrite(2L, VIEWER_ID, false)).thenReturn(null);
        when(assetMapper.lockByIdForUpdate(100L)).thenReturn(100L);
        when(versionMapper.selectOne(any())).thenReturn(current);
        when(projectMapper.selectById(2L)).thenReturn(target);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.copyCurrent(100L, VIEWER_ID, false, copyRequest(2L)));

        assertEquals(ErrorCode.CONFLICT.getCode(), error.getCode());
        verify(assetMapper, never()).insert(any());
    }

    @Test
    void list_roleFilter_returnsEmptyWhenNoLink() {
        when(aclService.loadAccessible(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(roleLinkMapper.selectList(any())).thenReturn(List.of());
        PageResult<AssetVO> result = service.list(PROJECT_ID, OWNER_ID, false,
                null, "人物", null, null, null, null, null, null, 1, 20);
        assertTrue(result.getRecords().isEmpty());
    }

    @Test
    void list_assemblesRolesBatched() {
        when(aclService.loadAccessible(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        Asset a1 = asset(1L, Asset.MEDIA_IMAGE);
        Asset a2 = asset(2L, Asset.MEDIA_IMAGE);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Asset> page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setRecords(List.of(a1, a2));
        page.setTotal(2);
        when(assetMapper.selectPage(any(), any())).thenReturn(page);
        AssetRoleLink l1 = roleLink(1L, "人物");
        AssetRoleLink l2 = roleLink(2L, "场景");
        when(roleLinkMapper.selectList(any())).thenReturn(List.of(l1, l2));
        // C2：当前版本 fileId 批查（文件类卡片缩略图）
        AssetVersion vv1 = new AssetVersion();
        vv1.setAssetId(1L);
        vv1.setVersion(1);
        vv1.setFileId("fid-1");
        AssetVersion vv2 = new AssetVersion();
        vv2.setAssetId(2L);
        vv2.setVersion(1);
        vv2.setFileId("fid-2");
        when(versionMapper.selectList(any())).thenReturn(List.of(vv1, vv2));
        // C6：双轨评分/我的分批查（无评分→空聚合）
        when(scoreMapper.selectAggregatesByProject(PROJECT_ID)).thenReturn(List.of());
        when(scoreMapper.selectMyScores(PROJECT_ID, OWNER_ID)).thenReturn(List.of());

        PageResult<AssetVO> result = service.list(PROJECT_ID, OWNER_ID, false,
                Asset.MEDIA_IMAGE, null, null, null, null, null, null, null, 1, 20);

        assertEquals(2, result.getRecords().size());
        // 单次 IN 批查角色 + fileId（防 N+1）
        verify(roleLinkMapper).selectList(any());
        verify(versionMapper).selectList(any());
        assertEquals(List.of("人物"), result.getRecords().get(0).getRoleKeys());
        assertEquals(List.of("场景"), result.getRecords().get(1).getRoleKeys());
        assertEquals("fid-1", result.getRecords().get(0).getFileId());
        assertEquals("fid-2", result.getRecords().get(1).getFileId());
    }

    // ---------- C6 列表筛选/装配 ----------

    @Test
    void list_creatorUsernameMiss_returnsEmptyPage() {
        when(aclService.loadAccessible(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(userMapper.selectList(any())).thenReturn(List.of());
        PageResult<AssetVO> result = service.list(PROJECT_ID, OWNER_ID, false,
                null, null, null, null, "不存在的用户", null, null, null, 1, 20);
        assertTrue(result.getRecords().isEmpty());
        verify(assetMapper, never()).selectPage(any(), any());
    }

    @Test
    void list_creatorUsernameHit_assemblesCreatorUsername() {
        when(aclService.loadAccessible(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        Asset a1 = asset(1L, Asset.MEDIA_IMAGE);
        a1.setCreatedBy(EDITOR_ID);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Asset> page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setRecords(List.of(a1));
        page.setTotal(1);
        when(assetMapper.selectPage(any(), any())).thenReturn(page);
        when(roleLinkMapper.selectList(any())).thenReturn(List.of());
        when(versionMapper.selectList(any())).thenReturn(List.of());
        com.superprogrammer.auth.entity.User u = new com.superprogrammer.auth.entity.User();
        u.setId(EDITOR_ID);
        u.setUsername("editor甲");
        when(userMapper.selectList(any())).thenReturn(List.of(u));
        when(userMapper.selectBatchIds(java.util.Set.of(EDITOR_ID))).thenReturn(List.of(u));
        when(scoreMapper.selectAggregatesByProject(PROJECT_ID)).thenReturn(List.of());
        when(scoreMapper.selectMyScores(PROJECT_ID, OWNER_ID)).thenReturn(List.of());

        PageResult<AssetVO> result = service.list(PROJECT_ID, OWNER_ID, false,
                null, null, null, null, "editor甲", null, null, null, 1, 20);

        assertEquals(1, result.getRecords().size());
        assertEquals("editor甲", result.getRecords().get(0).getCreatedByUsername());
        assertEquals(0, result.getRecords().get(0).getMemberCount());
    }

    @Test
    void list_scoreSourceMember_usesAvgSubquery() {
        when(aclService.loadAccessible(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(scoreMapper.selectAssetIdsByMemberAvg(PROJECT_ID, 80, 100)).thenReturn(List.of(5L));
        Asset a5 = asset(5L, Asset.MEDIA_IMAGE);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Asset> page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setRecords(List.of(a5));
        page.setTotal(1);
        when(assetMapper.selectPage(any(), any())).thenReturn(page);
        when(roleLinkMapper.selectList(any())).thenReturn(List.of());
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(scoreMapper.selectAggregatesByProject(PROJECT_ID)).thenReturn(List.of(
                java.util.Map.of("assetId", 5L, "ownerScore", 88, "memberAvgScore", 90.0, "memberCount", 2L)));
        when(scoreMapper.selectMyScores(PROJECT_ID, OWNER_ID)).thenReturn(List.of(
                java.util.Map.of("assetId", 5L, "score", 95)));

        PageResult<AssetVO> result = service.list(PROJECT_ID, OWNER_ID, false,
                null, null, null, null, null, 80, 100, "member", 1, 20);

        verify(scoreMapper).selectAssetIdsByMemberAvg(PROJECT_ID, 80, 100);
        AssetVO vo = result.getRecords().get(0);
        assertEquals(88, vo.getOwnerScore());
        assertEquals(90, vo.getMemberAvgScore());
        assertEquals(2, vo.getMemberCount());
        assertEquals(95, vo.getMyScore());
    }

    @Test
    void list_scoreSourceOwnerMiss_returnsEmptyPage() {
        when(aclService.loadAccessible(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(scoreMapper.selectAssetIdsByOwnerScore(PROJECT_ID, null, 60)).thenReturn(List.of());
        PageResult<AssetVO> result = service.list(PROJECT_ID, OWNER_ID, false,
                null, null, null, null, null, null, 60, "owner", 1, 20);
        assertTrue(result.getRecords().isEmpty());
        verify(assetMapper, never()).selectPage(any(), any());
    }

    @Test
    void list_scoreSourceInvalid_400() {
        when(aclService.loadAccessible(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.list(PROJECT_ID, OWNER_ID, false,
                        null, null, null, null, null, 80, 100, "both", 1, 20));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void list_scoreRangeInverted_400() {
        when(aclService.loadAccessible(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.list(PROJECT_ID, OWNER_ID, false,
                        null, null, null, null, null, 90, 10, "owner", 1, 20));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void countMatrix_singleAggregateCall() {
        when(aclService.loadAccessible(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(assetMapper.countMatrixByRole(PROJECT_ID)).thenReturn(List.of(
                new MatrixCountVO.Cell(Asset.MEDIA_IMAGE, "人物", 5L)));
        when(assetMapper.countByType(PROJECT_ID)).thenReturn(List.of(
                new MatrixCountVO.Cell(Asset.MEDIA_IMAGE, null, 12L)));

        MatrixCountVO vo = service.countMatrix(PROJECT_ID, OWNER_ID, false);

        assertEquals(1, vo.getCells().size());
        assertEquals(5L, vo.getCells().get(0).getCount());
        assertEquals(12L, vo.getTypeTotals().get(0).getCount());
    }

    @Test
    void delete_viewerDenied() {
        Asset a = asset(1L, Asset.MEDIA_IMAGE);
        a.setProjectId(PROJECT_ID);
        when(assetMapper.selectById(1L)).thenReturn(a);
        when(aclService.requireAssetOperate(any(Asset.class), eq(VIEWER_ID), eq(false)))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "需编辑权限"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete(1L, VIEWER_ID, false));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(assetMapper, never()).deleteById((java.io.Serializable) any());
    }

    @Test
    void update_syncsRoleLinks() {
        Asset a = asset(1L, Asset.MEDIA_PROMPT);
        a.setProjectId(PROJECT_ID);
        when(assetMapper.selectById(1L)).thenReturn(a);
        when(aclService.requireAssetOperate(any(Asset.class), eq(EDITOR_ID), eq(false))).thenReturn(null);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(projectWithRoles());

        AssetUpdateRequest req = new AssetUpdateRequest();
        req.setName("新名");
        req.setRoleKeys(List.of("道具"));
        service.update(1L, EDITOR_ID, false, req);

        verify(assetMapper).updateById(any());
        verify(roleLinkMapper).delete(any());
        ArgumentCaptor<AssetRoleLink> rc = ArgumentCaptor.forClass(AssetRoleLink.class);
        verify(roleLinkMapper).insert(rc.capture());
        assertEquals("道具", rc.getValue().getRoleKey());
    }

    // ---------- S4b 上传 ----------

    /** 1×1 透明 PNG（标准字节序列，ImageIO 可读宽高=1）。 */
    private static final byte[] PNG_1X1 = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/I9NAAAAAElFTkSuQmCC");

    @Test
    void upload_image_storesFileAndExtractsDims() throws Exception {
        when(aclService.requireWrite(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("image/png");
        when(file.getInputStream()).thenAnswer(inv -> new ByteArrayInputStream(PNG_1X1));
        when(fileStorageService.store(any(), any(), any())).thenReturn(
                new StoredFile("file-id-1", "/api/files/file-id-1", "a.png", "image/png", PNG_1X1.length));
        when(assetMapper.insert(any(Asset.class))).thenAnswer(inv -> {
            ((Asset) inv.getArgument(0)).setId(200L);
            return 1;
        });

        AssetVO vo = service.upload(PROJECT_ID, OWNER_ID, false, file, Asset.MEDIA_IMAGE, "老板娘", null, null);

        assertEquals(200L, vo.getId());
        assertEquals(Asset.MEDIA_IMAGE, vo.getMediaType());
        // store 走 SOURCE_ASSET
        verify(fileStorageService).store(any(), any(), org.mockito.ArgumentMatchers.eq(com.superprogrammer.file.entity.StoredFileEntity.SOURCE_ASSET));
        // 版本 1 带 file_id
        ArgumentCaptor<AssetVersion> vc = ArgumentCaptor.forClass(AssetVersion.class);
        verify(versionMapper).insert(vc.capture());
        assertEquals("file-id-1", vc.getValue().getFileId());
        // gen_meta 含图片宽高
        ArgumentCaptor<Asset> ac = ArgumentCaptor.forClass(Asset.class);
        verify(assetMapper).insert(ac.capture());
        assertTrue(ac.getValue().getGenMeta().contains("\"width\":1"), "gen_meta 须含图片宽");
        assertTrue(ac.getValue().getGenMeta().contains("\"height\":1"), "gen_meta 须含图片高");
    }

    @Test
    void upload_typeMismatch_throws() {
        when(aclService.requireWrite(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getContentType()).thenReturn("video/mp4"); // mp4 不可入图片资产
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.upload(PROJECT_ID, OWNER_ID, false, file, Asset.MEDIA_IMAGE, "x", null, null));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(fileStorageService, never()).store(any(), any(), any());
    }

    @Test
    void upload_viewerDenied() {
        when(aclService.requireWrite(PROJECT_ID, VIEWER_ID, false))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "需编辑权限"));
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.upload(PROJECT_ID, VIEWER_ID, false, file, Asset.MEDIA_IMAGE, "x", null, null));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(fileStorageService, never()).store(any(), any(), any());
    }

    // ---------- S16 textPreview 文本正文片段抽取（Bug④） ----------

    /** 文本资产走 get() → toVO：textPreview 按键优先级 body/synopsis/prompt 抽取。 */
    private AssetVO getTextPreview(String content) {
        Asset a = asset(1L, Asset.MEDIA_PROMPT);
        a.setMediaCategory(Asset.CATEGORY_TEXT);
        a.setContent(content);
        when(assetMapper.selectById(1L)).thenReturn(a);
        when(aclService.loadAccessible(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(versionMapper.selectOne(any())).thenReturn(null);
        return service.get(1L, OWNER_ID, false);
    }

    @Test
    void textPreview_fromBodyKey() {
        AssetVO vo = getTextPreview("{\"body\":\"一只猫坐在窗台上\"}");
        assertEquals("一只猫坐在窗台上", vo.getTextPreview());
    }

    @Test
    void textPreview_fromSynopsisKeyWhenNoBody() {
        AssetVO vo = getTextPreview("{\"synopsis\":\"主角登场\"}");
        assertEquals("主角登场", vo.getTextPreview());
    }

    @Test
    void textPreview_fromPromptKeyForStoryboard() {
        // 分镜 content schema 字段1 prompt（无 body/synopsis）
        AssetVO vo = getTextPreview("{\"prompt\":\"远景全景，城市天际线\"}");
        assertEquals("远景全景，城市天际线", vo.getTextPreview());
    }

    @Test
    void textPreview_plainTextFallbackWhenNonJson() {
        AssetVO vo = getTextPreview("裸文本内容不合法 JSON");
        assertEquals("裸文本内容不合法 JSON", vo.getTextPreview());
    }

    @Test
    void textPreview_truncatesOver120() {
        // 150 字 → 截断 120
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 150; i++) sb.append("甲");
        AssetVO vo = getTextPreview("{\"body\":\"" + sb + "\"}");
        assertEquals(120, vo.getTextPreview().length());
    }

    @Test
    void textPreview_collapsesWhitespace() {
        AssetVO vo = getTextPreview("{\"body\":\"第一行\\n第二行  多空格\"}");
        assertEquals("第一行 第二行 多空格", vo.getTextPreview());
    }

    @Test
    void textPreview_nullForNonTextCategory() {
        // IMAGE 类别 → textPreview 不填（null）
        Asset a = asset(1L, Asset.MEDIA_IMAGE);
        a.setMediaCategory(Asset.CATEGORY_IMAGE);
        a.setContent("{\"body\":\"不应被抽取\"}");
        when(assetMapper.selectById(1L)).thenReturn(a);
        when(aclService.loadAccessible(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(versionMapper.selectOne(any())).thenReturn(null);
        AssetVO vo = service.get(1L, OWNER_ID, false);
        assertNull(vo.getTextPreview());
    }

    // ---------- S18 分镜字段保存 ----------

    private StoryboardSaveRequest.EntityRef sbRef(String key, Long assetId) {
        StoryboardSaveRequest.EntityRef r = new StoryboardSaveRequest.EntityRef();
        r.setKey(key);
        r.setAssetId(assetId);
        return r;
    }

    @Test
    void saveStoryboard_mergesFields_enrichesValidRefs_dropsIllegal() {
        Asset a = asset(1L, Asset.MEDIA_STORYBOARD);
        a.setMediaCategory(Asset.CATEGORY_TEXT);
        a.setProjectId(PROJECT_ID);
        a.setContent("{\"shotIndex\":2,\"parentId\":50,\"prompt\":\"旧提示\"}");
        when(assetMapper.selectById(1L)).thenReturn(a);
        when(aclService.requireAssetOperate(any(Asset.class), eq(OWNER_ID), eq(false))).thenReturn(null);
        // 引用资产：id=7 同项目存在（富化），id=999 不存在（非法剔除置 null）
        Asset ref7 = asset(7L, Asset.MEDIA_IMAGE);
        ref7.setName("主角定妆");
        when(assetMapper.selectList(any())).thenReturn(List.of(ref7));
        ArgumentCaptor<VersionCreateRequest> vc = ArgumentCaptor.forClass(VersionCreateRequest.class);
        when(versionService.createVersion(eq(1L), eq(OWNER_ID), eq(false), vc.capture())).thenReturn(3);
        // get() 回读
        when(aclService.loadAccessible(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(versionMapper.selectOne(any())).thenReturn(null);

        StoryboardSaveRequest req = new StoryboardSaveRequest();
        req.setPrompt("新镜头提示词");
        req.setEntityRefs(List.of(sbRef("主角", 7L), sbRef("已删资产", 999L)));
        service.saveStoryboard(1L, OWNER_ID, false, req);

        String merged = vc.getValue().getContent();
        // 字段1 prompt 更新
        assertTrue(merged.contains("\"prompt\":\"新镜头提示词\""), "prompt 须更新");
        // shotIndex/parentId 保留（不被覆盖）
        assertTrue(merged.contains("\"shotIndex\":2"), "shotIndex 须保留");
        assertTrue(merged.contains("\"parentId\":50"), "parentId 须保留");
        // 主角(7) 富化 name
        assertTrue(merged.contains("\"name\":\"主角定妆\""), "有效引用须富化 name");
        // 已删(999) 置 null（剔除 assetId 防越权，保留 key）
        assertTrue(merged.contains("\"assetId\":null"), "非法引用须置 null");
        assertTrue(merged.contains("\"key\":\"已删资产\""), "key 须保留存痕迹");
        assertEquals("编辑分镜字段", vc.getValue().getChangeNote());
    }

    @Test
    void saveStoryboard_nonStoryboardType_throws400() {
        Asset a = asset(1L, Asset.MEDIA_PROMPT);
        a.setMediaCategory(Asset.CATEGORY_TEXT);
        a.setProjectId(PROJECT_ID);
        when(assetMapper.selectById(1L)).thenReturn(a);
        when(aclService.requireAssetOperate(any(Asset.class), eq(OWNER_ID), eq(false))).thenReturn(null);
        StoryboardSaveRequest req = new StoryboardSaveRequest();
        req.setPrompt("x");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.saveStoryboard(1L, OWNER_ID, false, req));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(versionService, never()).createVersion(any(), any(), anyBoolean(), any());
    }

    @Test
    void saveStoryboard_promptOverLimit_throws400() {
        Asset a = asset(1L, Asset.MEDIA_STORYBOARD);
        a.setMediaCategory(Asset.CATEGORY_TEXT);
        a.setProjectId(PROJECT_ID);
        when(assetMapper.selectById(1L)).thenReturn(a);
        when(aclService.requireAssetOperate(any(Asset.class), eq(OWNER_ID), eq(false))).thenReturn(null);
        StoryboardSaveRequest req = new StoryboardSaveRequest();
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 8001; i++) huge.append("a");
        req.setPrompt(huge.toString());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.saveStoryboard(1L, OWNER_ID, false, req));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    // ---------- S5 状态机 ----------

    @Test
    void lock_draftToLocked_ok() {
        Asset a = asset(1L, Asset.MEDIA_PROMPT);
        a.setProjectId(PROJECT_ID);
        when(assetMapper.selectById(1L)).thenReturn(a);
        when(aclService.requireAssetOperate(any(Asset.class), eq(OWNER_ID), eq(false))).thenReturn(null);

        AssetVO vo = service.lock(1L, OWNER_ID, false);

        assertEquals(Asset.STATUS_LOCKED, vo.getStatus());
        verify(assetMapper).updateById(any());
    }

    @Test
    void lock_archived_throws400() {
        Asset a = asset(1L, Asset.MEDIA_PROMPT);
        a.setProjectId(PROJECT_ID);
        a.setStatus(Asset.STATUS_ARCHIVED);
        when(assetMapper.selectById(1L)).thenReturn(a);
        when(aclService.requireAssetOperate(any(Asset.class), eq(OWNER_ID), eq(false))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.lock(1L, OWNER_ID, false));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(assetMapper, never()).updateById(any());
    }

    @Test
    void unlock_lockedToDraft_ok() {
        Asset a = asset(1L, Asset.MEDIA_PROMPT);
        a.setProjectId(PROJECT_ID);
        a.setStatus(Asset.STATUS_LOCKED);
        when(assetMapper.selectById(1L)).thenReturn(a);
        when(aclService.requireAssetOperate(any(Asset.class), eq(OWNER_ID), eq(false))).thenReturn(null);

        AssetVO vo = service.unlock(1L, OWNER_ID, false);

        assertEquals(Asset.STATUS_DRAFT, vo.getStatus());
    }

    @Test
    void archive_thenUnarchiveRestores() {
        Asset a = asset(1L, Asset.MEDIA_IMAGE);
        a.setProjectId(PROJECT_ID);
        when(assetMapper.selectById(1L)).thenReturn(a);
        when(aclService.requireAssetOperate(any(Asset.class), eq(OWNER_ID), eq(false))).thenReturn(null);

        AssetVO archived = service.archive(1L, OWNER_ID, false);
        assertEquals(Asset.STATUS_ARCHIVED, archived.getStatus());

        // 回读为归档态后再 unarchive
        a.setStatus(Asset.STATUS_ARCHIVED);
        AssetVO restored = service.unarchive(1L, OWNER_ID, false);
        assertEquals(Asset.STATUS_DRAFT, restored.getStatus());
    }

    @Test
    void unarchive_nonArchived_throws400() {
        Asset a = asset(1L, Asset.MEDIA_IMAGE);
        a.setProjectId(PROJECT_ID);
        a.setStatus(Asset.STATUS_DRAFT);
        when(assetMapper.selectById(1L)).thenReturn(a);
        when(aclService.requireAssetOperate(any(Asset.class), eq(OWNER_ID), eq(false))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.unarchive(1L, OWNER_ID, false));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    private AssetProject projectWithRoles() {
        AssetProject p = new AssetProject();
        p.setId(PROJECT_ID);
        p.setNarrativeRoles("[\"人物\",\"道具\",\"场景\",\"风格\",\"通用\"]");
        return p;
    }

    private AssetCopyRequest copyRequest(Long targetProjectId) {
        AssetCopyRequest request = new AssetCopyRequest();
        request.setTargetProjectId(targetProjectId);
        return request;
    }

    @Test
    void searchCreatorCandidates_editorReadGate_returnsProjectCreatorsFilteredByKeyword() {
        // EDITOR 走读门 loadAccessible（非 requireManage），候选只含本项目上传者（P4 实测 403 修复）
        when(aclService.loadAccessible(PROJECT_ID, EDITOR_ID, false)).thenReturn(null);
        when(assetMapper.selectCreatorUserIds(PROJECT_ID)).thenReturn(List.of(OWNER_ID, EDITOR_ID));
        com.superprogrammer.auth.entity.User owner = new com.superprogrammer.auth.entity.User();
        owner.setId(OWNER_ID);
        owner.setUsername("p4_owner");
        com.superprogrammer.auth.entity.User editor = new com.superprogrammer.auth.entity.User();
        editor.setId(EDITOR_ID);
        editor.setUsername("p4_editor");
        when(userMapper.selectBatchIds(List.of(OWNER_ID, EDITOR_ID))).thenReturn(List.of(owner, editor));

        var all = service.searchCreatorCandidates(PROJECT_ID, EDITOR_ID, false, "p4_");
        assertEquals(2, all.size());
        assertEquals("p4_editor", all.get(0).getUsername());
        assertEquals("p4_owner", all.get(1).getUsername());

        var hit = service.searchCreatorCandidates(PROJECT_ID, EDITOR_ID, false, "editor");
        assertEquals(1, hit.size());
        assertEquals("p4_editor", hit.get(0).getUsername());

        var miss = service.searchCreatorCandidates(PROJECT_ID, EDITOR_ID, false, "nobody");
        assertEquals(0, miss.size());
        verify(aclService, org.mockito.Mockito.times(3)).loadAccessible(PROJECT_ID, EDITOR_ID, false);
        verify(aclService, never()).requireManage(any(), any(), anyBoolean());
    }

    private AssetProject targetProject(Long id, String roles, String mediaTypes) {
        AssetProject project = new AssetProject();
        project.setId(id);
        project.setNarrativeRoles(roles);
        project.setMediaTypes(mediaTypes);
        return project;
    }

    private Asset asset(long id, String mediaType) {
        Asset a = new Asset();
        a.setId(id);
        a.setProjectId(PROJECT_ID);
        a.setMediaType(mediaType);
        a.setName("a" + id);
        a.setStatus(Asset.STATUS_DRAFT);
        a.setCurrentVersion(1);
        a.setTags("[]");
        a.setGenMeta("{}");
        a.setContent("{}");
        return a;
    }

    private AssetRoleLink roleLink(long assetId, String role) {
        AssetRoleLink l = new AssetRoleLink();
        l.setAssetId(assetId);
        l.setRoleKey(role);
        return l;
    }
}
