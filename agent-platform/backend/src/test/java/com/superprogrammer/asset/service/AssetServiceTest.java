package com.superprogrammer.asset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.asset.dto.AssetCreateRequest;
import com.superprogrammer.asset.dto.AssetUpdateRequest;
import com.superprogrammer.asset.dto.AssetVO;
import com.superprogrammer.asset.dto.MatrixCountVO;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
                new ObjectMapper(), fileStorageService);
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

    @Test
    void list_roleFilter_returnsEmptyWhenNoLink() {
        when(aclService.loadAccessible(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(roleLinkMapper.selectList(any())).thenReturn(List.of());
        PageResult<AssetVO> result = service.list(PROJECT_ID, OWNER_ID, false,
                null, "人物", null, null, 1, 20);
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

        PageResult<AssetVO> result = service.list(PROJECT_ID, OWNER_ID, false,
                Asset.MEDIA_IMAGE, null, null, null, 1, 20);

        assertEquals(2, result.getRecords().size());
        // 单次 IN 批查（防 N+1）
        verify(roleLinkMapper).selectList(any());
        assertEquals(List.of("人物"), result.getRecords().get(0).getRoleKeys());
        assertEquals(List.of("场景"), result.getRecords().get(1).getRoleKeys());
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
        when(aclService.requireWrite(PROJECT_ID, VIEWER_ID, false))
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
        when(aclService.requireWrite(PROJECT_ID, EDITOR_ID, false)).thenReturn(null);
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

    private AssetProject projectWithRoles() {
        AssetProject p = new AssetProject();
        p.setId(PROJECT_ID);
        p.setNarrativeRoles("[\"人物\",\"道具\",\"场景\",\"风格\",\"通用\"]");
        return p;
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
