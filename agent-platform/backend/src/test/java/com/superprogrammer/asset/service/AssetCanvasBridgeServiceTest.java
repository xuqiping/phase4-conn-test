package com.superprogrammer.asset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.asset.dto.CanvasImportRequest;
import com.superprogrammer.asset.dto.CanvasImportVO;
import com.superprogrammer.asset.dto.ResolveVO;
import com.superprogrammer.asset.dto.VersionCreateRequest;
import com.superprogrammer.asset.entity.Asset;
import com.superprogrammer.asset.entity.AssetBinding;
import com.superprogrammer.asset.entity.AssetVersion;
import com.superprogrammer.asset.mapper.AssetMapper;
import com.superprogrammer.asset.mapper.AssetVersionMapper;
import com.superprogrammer.canvas.entity.Canvas;
import com.superprogrammer.canvas.service.CanvasService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AssetCanvasBridgeService 单测：画布双向打通（plan §S7 验证）。
 *
 * <p>覆盖：节点类型映射、文本/文件产出提取、空节点拦截、重复入库三态（提示/新版本/新建）、
 * resolve 版本快照（viewer 可读）、使用记录、跨包 canvas loadOwned 复用。
 */
@ExtendWith(MockitoExtension.class)
class AssetCanvasBridgeServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long CANVAS_ID = 7L;
    private static final String NODE_ID = "n1";
    private static final Long USER_ID = 10L;
    private static final Long NEW_ASSET_ID = 500L;

    @Mock private AssetMapper assetMapper;
    @Mock private AssetVersionMapper versionMapper;
    @Mock private AssetAclService aclService;
    @Mock private AssetService assetService;
    @Mock private AssetVersionService versionService;
    @Mock private AssetBindingService bindingService;
    @Mock private CanvasService canvasService;

    private AssetCanvasBridgeService service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper（resolve 内）须 MP lambda 缓存
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AssetVersion.class);
    }

    @BeforeEach
    void setUp() {
        service = new AssetCanvasBridgeService(assetMapper, versionMapper, aclService,
                assetService, versionService, bindingService, canvasService, new ObjectMapper());
    }

    // ==================== 画布 → 库 ====================

    @Test
    void importFromCanvas_textNode_createsNewPromptAsset() {
        CanvasImportRequest req = newReq(null); // 无 mode
        stubCanvasOwnership(snapshot(
                "{\"nodes\":[{\"id\":\"n1\",\"type\":\"text\",\"data\":{\"outputText\":\"扩写结果\",\"prompt\":\"p\",\"model\":\"m\"}}]}"));
        when(bindingService.findProduced(CANVAS_ID, NODE_ID)).thenReturn(null);
        when(assetService.validateAssetName(any())).thenAnswer(inv -> inv.getArgument(0));
        // 模拟 insert 回填 id
        doAnswer(inv -> {
            ((Asset) inv.getArgument(0)).setId(NEW_ASSET_ID);
            return 1;
        }).when(assetMapper).insert(any());

        CanvasImportVO vo = service.importFromCanvas(req, USER_ID, false);

        assertTrue(vo.isCreated());
        assertEquals(NEW_ASSET_ID, vo.getAssetId());
        assertEquals(Asset.MEDIA_PROMPT, vo.getMediaType());
        assertEquals(1, vo.getVersion());
        // 落 v1 + 角色 + PRODUCED 绑定
        ArgumentCaptor<AssetVersion> vc = ArgumentCaptor.forClass(AssetVersion.class);
        verify(versionMapper).insert(vc.capture());
        assertEquals(1, vc.getValue().getVersion());
        assertTrue(vc.getValue().getContent().contains("扩写结果"), "正文取 outputText");
        assertNull(vc.getValue().getFileId(), "文本类无 fileId");
        verify(assetService).attachRoles(eq(PROJECT_ID), eq(NEW_ASSET_ID), any());
        verify(bindingService).recordProduced(eq(NEW_ASSET_ID), eq(1), eq(CANVAS_ID), eq(NODE_ID), eq(USER_ID));
    }

    @Test
    void importFromCanvas_imageNode_createsNewImageAssetWithFileId() {
        CanvasImportRequest req = newReq(null);
        stubCanvasOwnership(snapshot(
                "{\"nodes\":[{\"id\":\"n1\",\"type\":\"image\",\"data\":{\"fileId\":\"img-1\",\"prompt\":\"a girl\"}}]}"));
        when(bindingService.findProduced(CANVAS_ID, NODE_ID)).thenReturn(null);
        when(assetService.validateAssetName(any())).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            ((Asset) inv.getArgument(0)).setId(NEW_ASSET_ID);
            return 1;
        }).when(assetMapper).insert(any());

        CanvasImportVO vo = service.importFromCanvas(req, USER_ID, false);

        assertEquals(Asset.MEDIA_IMAGE, vo.getMediaType());
        ArgumentCaptor<AssetVersion> vc = ArgumentCaptor.forClass(AssetVersion.class);
        verify(versionMapper).insert(vc.capture());
        assertEquals("img-1", vc.getValue().getFileId());
        assertEquals("{}", vc.getValue().getContent(), "文件类 content 占位");
    }

    @Test
    void importFromCanvas_emptyTextNode_rejected400() {
        CanvasImportRequest req = newReq(null);
        stubCanvasOwnership(snapshot(
                "{\"nodes\":[{\"id\":\"n1\",\"type\":\"text\",\"data\":{}}]}"));
        when(bindingService.findProduced(CANVAS_ID, NODE_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.importFromCanvas(req, USER_ID, false));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(assetMapper, never()).insert(any());
        verify(bindingService, never()).recordProduced(anyLong(), anyInt(), anyLong(), any(), anyLong());
    }

    @Test
    void importFromCanvas_duplicateNoMode_returnsHintWithoutCreating() {
        CanvasImportRequest req = newReq(null);
        stubCanvasOwnership(snapshot(
                "{\"nodes\":[{\"id\":\"n1\",\"type\":\"text\",\"data\":{\"outputText\":\"x\"}}]}"));
        AssetBinding dup = new AssetBinding();
        dup.setAssetId(200L);
        when(bindingService.findProduced(CANVAS_ID, NODE_ID)).thenReturn(dup);
        Asset dupAsset = new Asset();
        dupAsset.setId(200L);
        dupAsset.setCurrentVersion(2);
        when(assetMapper.selectById(200L)).thenReturn(dupAsset);

        CanvasImportVO vo = service.importFromCanvas(req, USER_ID, false);

        assertFalse(vo.isCreated());
        assertEquals(200L, vo.getDuplicateAssetId());
        assertEquals(2, vo.getDuplicateVersion());
        verify(assetMapper, never()).insert(any());
        verify(versionService, never()).createVersion(anyLong(), anyLong(), anyBoolean(), any());
    }

    @Test
    void importFromCanvas_duplicateNewVersion_delegatesToVersionService() {
        CanvasImportRequest req = newReq(AssetCanvasBridgeService.MODE_NEW_VERSION);
        stubCanvasOwnership(snapshot(
                "{\"nodes\":[{\"id\":\"n1\",\"type\":\"image\",\"data\":{\"fileId\":\"img-2\"}}]}"));
        AssetBinding dup = new AssetBinding();
        dup.setAssetId(200L);
        when(bindingService.findProduced(CANVAS_ID, NODE_ID)).thenReturn(dup);
        when(versionService.createVersion(eq(200L), eq(USER_ID), eq(false), any(VersionCreateRequest.class)))
                .thenReturn(3);
        Asset dupAsset = asset(200L, Asset.MEDIA_IMAGE, 2, "老板娘");
        when(assetMapper.selectById(200L)).thenReturn(dupAsset);

        CanvasImportVO vo = service.importFromCanvas(req, USER_ID, false);

        assertTrue(vo.isCreated());
        assertEquals(200L, vo.getAssetId());
        assertEquals(3, vo.getVersion());
        // 复用 versionService 建版（传 fileId）
        ArgumentCaptor<VersionCreateRequest> vcap = ArgumentCaptor.forClass(VersionCreateRequest.class);
        verify(versionService).createVersion(eq(200L), eq(USER_ID), eq(false), vcap.capture());
        assertEquals("img-2", vcap.getValue().getFileId());
        verify(assetMapper).updateGenMeta(eq(200L), any(), eq(USER_ID));
        verify(bindingService).recordProduced(eq(200L), eq(3), eq(CANVAS_ID), eq(NODE_ID), eq(USER_ID));
        verify(assetMapper, never()).insert(any());
    }

    // ==================== 库 → 画布（resolve，viewer 可读） ====================

    @Test
    void resolve_textAsset_returnsContentAndViewerCanRead() {
        when(assetMapper.selectById(NEW_ASSET_ID)).thenReturn(asset(NEW_ASSET_ID, Asset.MEDIA_PROMPT, 2, "p"));
        when(versionMapper.selectOne(any())).thenReturn(version(2, "{\"body\":\"hi\"}", null));
        // viewer：loadAccessible 通过（不要求 requireWrite）
        when(aclService.loadAccessible(PROJECT_ID, USER_ID, false)).thenReturn(null);

        ResolveVO vo = service.resolve(NEW_ASSET_ID, null, USER_ID, false);

        assertEquals(2, vo.getVersion());
        assertEquals("{\"body\":\"hi\"}", vo.getContent());
        assertNull(vo.getFileId());
        assertNull(vo.getUrl());
        verify(aclService, never()).requireWrite(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void resolve_fileAsset_returnsUrl() {
        when(assetMapper.selectById(NEW_ASSET_ID)).thenReturn(asset(NEW_ASSET_ID, Asset.MEDIA_IMAGE, 1, "img"));
        when(versionMapper.selectOne(any())).thenReturn(version(1, "{}", "file-abc"));
        when(aclService.loadAccessible(PROJECT_ID, USER_ID, false)).thenReturn(null);

        ResolveVO vo = service.resolve(NEW_ASSET_ID, null, USER_ID, false);

        assertEquals("file-abc", vo.getFileId());
        assertEquals("/api/files/file-abc", vo.getUrl());
        assertNull(vo.getContent(), "文件类不返 content");
    }

    @Test
    void resolve_versionNotFound_404() {
        when(assetMapper.selectById(NEW_ASSET_ID)).thenReturn(asset(NEW_ASSET_ID, Asset.MEDIA_PROMPT, 1, "p"));
        when(versionMapper.selectOne(any())).thenReturn(null);
        when(aclService.loadAccessible(PROJECT_ID, USER_ID, false)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resolve(NEW_ASSET_ID, 99, USER_ID, false));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void listUsages_delegatesAfterAcl() {
        when(assetMapper.selectById(NEW_ASSET_ID)).thenReturn(asset(NEW_ASSET_ID, Asset.MEDIA_PROMPT, 1, "p"));
        when(aclService.loadAccessible(PROJECT_ID, USER_ID, false)).thenReturn(null);
        when(bindingService.listUsages(NEW_ASSET_ID)).thenReturn(List.of());

        service.listUsages(NEW_ASSET_ID, USER_ID, false);

        verify(bindingService).listUsages(NEW_ASSET_ID);
    }

    // ==================== fixtures ====================

    private CanvasImportRequest newReq(String mode) {
        CanvasImportRequest req = new CanvasImportRequest();
        req.setCanvasId(CANVAS_ID);
        req.setNodeId(NODE_ID);
        req.setProjectId(PROJECT_ID);
        req.setName("画布产出");
        req.setMode(mode);
        return req;
    }

    private void stubCanvasOwnership(String snapshot) {
        Canvas c = new Canvas();
        c.setId(CANVAS_ID);
        c.setUserId(USER_ID);
        c.setSnapshot(snapshot);
        when(canvasService.loadOwned(CANVAS_ID, USER_ID, false)).thenReturn(c);
    }

    private String snapshot(String json) {
        return json;
    }

    private Asset asset(Long id, String mediaType, int curVer, String name) {
        Asset a = new Asset();
        a.setId(id);
        a.setProjectId(PROJECT_ID);
        a.setMediaType(mediaType);
        a.setCurrentVersion(curVer);
        a.setName(name);
        a.setStatus(Asset.STATUS_DRAFT);
        return a;
    }

    private AssetVersion version(int ver, String content, String fileId) {
        AssetVersion v = new AssetVersion();
        v.setAssetId(NEW_ASSET_ID);
        v.setVersion(ver);
        v.setContent(content);
        v.setFileId(fileId);
        return v;
    }
}
