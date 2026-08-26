package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.asset.dto.MediaImportRequest;
import com.superprogrammer.asset.dto.MediaImportVO;
import com.superprogrammer.asset.entity.Asset;
import com.superprogrammer.asset.entity.AssetVersion;
import com.superprogrammer.asset.mapper.AssetMapper;
import com.superprogrammer.asset.mapper.AssetVersionMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.service.MediaGenQueryService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AssetMediaBridgeService 生图入库桥单测。
 *
 * <p>覆盖：① 正常入库→IMAGE 资产 v1(fileId 复用, genMeta.source=MEDIA) + 返回 created；② viewer 不可入库（requireWrite 抛）；
 * ③ 媒体非归属（loadImageForImport 抛）透传；④ 名称兜底（空→「图片产出」）；
 * 修复III F1（17x#1）：⑤ 同项目同任务判重→duplicate 复用既有资产不重复建；⑥ genMeta 落 imageIdx 判重键。
 */
@ExtendWith(MockitoExtension.class)
class AssetMediaBridgeServiceTest {

    @Mock private AssetMapper assetMapper;
    @Mock private AssetVersionMapper versionMapper;
    @Mock private AssetAclService aclService;
    @Mock private AssetService assetService;
    @Mock private MediaGenQueryService mediaGenQueryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AssetMediaBridgeService bridge;

    /** 纯 Mockito 无 Spring 上下文：LambdaQueryWrapper.eq 需 TableInfo lambda 缓存（F1 判重引入，先例 AuthServiceTest）。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Asset.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // @Mock 字段由 MockitoExtension 在构造后注入，故 bridge 在 @BeforeEach 构建（此时 mock 已就绪）。
        bridge = new AssetMediaBridgeService(
                assetMapper, versionMapper, aclService, assetService, mediaGenQueryService, objectMapper);
    }

    private MediaGenQueryService.ImageImportContext ctx(long taskId, String fileId) {
        MediaGenTask task = new MediaGenTask();
        task.setId(taskId);
        task.setUserId(100L);
        task.setModel("doubao-seedream-5.0-lite");
        task.setRequestConfig("{\"prompt\":\"赛博朋克猫咪\",\"size\":\"2K\"}");
        return new MediaGenQueryService.ImageImportContext(task, fileId);
    }

    @Test
    void importMedia_happyPath_createsImageAssetWithReusedFileId() {
        MediaImportRequest req = new MediaImportRequest();
        req.setTaskId(7L);
        req.setImageIdx(0);
        req.setProjectId(5L);
        req.setName("我的猫图");
        req.setDescription("测试");
        when(assetService.validateAssetName("我的猫图")).thenReturn("我的猫图");
        when(mediaGenQueryService.loadImageForImport(7L, 0, 100L, false)).thenReturn(ctx(7L, "file-img-1"));

        AssetVersion inserted = new AssetVersion();
        inserted.setId(99L);
        AssetImportAnswer answer = new AssetImportAnswer();
        when(assetMapper.insert(any(Asset.class))).thenAnswer(answer);

        MediaImportVO vo = bridge.importFromMediaTask(req, 100L, false);

        assertTrue(vo.isCreated());
        assertEquals(Asset.MEDIA_IMAGE, vo.getMediaType());
        assertEquals(1, vo.getVersion());
        // 资产正确分类 + 项目 + genMeta 标 MEDIA
        Asset captured = answer.captured;
        assertEquals(5L, captured.getProjectId());
        assertEquals(Asset.CATEGORY_IMAGE, captured.getMediaCategory());
        assertEquals(Asset.MEDIA_IMAGE, captured.getMediaType());
        assertTrue(captured.getGenMeta().contains("\"source\":\"MEDIA\""));
        assertTrue(captured.getGenMeta().contains("\"taskId\":7"));
        assertTrue(captured.getGenMeta().contains("\"model\":\"doubao-seedream-5.0-lite\""));
        assertTrue(captured.getGenMeta().contains("\"prompt\":\"赛博朋克猫咪\""));
        // 修复III F1：图片行 genMeta 落 imageIdx（同项目判重键）
        assertTrue(captured.getGenMeta().contains("\"imageIdx\":0"));
        // 版本复用 fileId（不拷贝）
        ArgumentCaptor<AssetVersion> vc = ArgumentCaptor.forClass(AssetVersion.class);
        verify(versionMapper).insert(vc.capture());
        assertEquals("file-img-1", vc.getValue().getFileId());
        assertEquals(1, vc.getValue().getVersion());
        assertEquals(100L, vc.getValue().getCreatedBy());
        // 权限咽喉都过了
        verify(aclService).requireWrite(5L, 100L, false);
    }

    @Test
    void importMedia_emptyName_fallsBackToDefault() {
        MediaImportRequest req = new MediaImportRequest();
        req.setTaskId(7L);
        req.setImageIdx(0);
        req.setProjectId(5L);
        req.setName("   "); // 空白
        when(assetService.validateAssetName("图片产出")).thenReturn("图片产出");
        when(mediaGenQueryService.loadImageForImport(7L, 0, 100L, false)).thenReturn(ctx(7L, "f"));

        MediaImportVO vo = bridge.importFromMediaTask(req, 100L, false);

        assertEquals("图片产出", vo.getName());
    }

    @Test
    void importMedia_viewerCannotWrite_propagates() {
        MediaImportRequest req = new MediaImportRequest();
        req.setTaskId(7L);
        req.setImageIdx(0);
        req.setProjectId(5L);
        // viewer 不可写 → requireWrite 抛
        doThrow(new BusinessException(com.superprogrammer.common.exception.ErrorCode.FORBIDDEN, "无权写入"))
                .when(aclService).requireWrite(5L, 100L, false);

        assertThrows(BusinessException.class, () -> bridge.importFromMediaTask(req, 100L, false));
        // 媒体查询不应被调（项目权限先于媒体归属校验失败）
        verify(mediaGenQueryService, never()).loadImageForImport(anyLong(), anyInt(), anyLong(), anyBoolean());
        verify(assetMapper, never()).insert(any());
    }

    @Test
    void importMedia_notOwner_propagates() {
        MediaImportRequest req = new MediaImportRequest();
        req.setTaskId(7L);
        req.setImageIdx(0);
        req.setProjectId(5L);
        // 项目可写，但媒体任务非归属 → loadImageForImport 抛
        when(mediaGenQueryService.loadImageForImport(7L, 0, 999L, false))
                .thenThrow(new BusinessException(com.superprogrammer.common.exception.ErrorCode.FORBIDDEN, "无权访问"));

        assertThrows(BusinessException.class, () -> bridge.importFromMediaTask(req, 999L, false));
        verify(assetMapper, never()).insert(any());
    }

    @Test
    void importMedia_missingParams_throwsBadRequest() {
        MediaImportRequest req = new MediaImportRequest(); // 全空
        BusinessException ex = assertThrows(BusinessException.class,
                () -> bridge.importFromMediaTask(req, 100L, false));
        assertEquals(com.superprogrammer.common.exception.ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    // ==================== 修复III F1（17x#1）同项目判重 ====================

    @Test
    void importMedia_duplicateSameProject_returnsExistingWithoutInsert() {
        MediaImportRequest req = new MediaImportRequest();
        req.setTaskId(7L);
        req.setImageIdx(0);
        req.setProjectId(5L);
        Asset existing = new Asset();
        existing.setId(88L);
        existing.setName("已入库的猫图");
        existing.setMediaType(Asset.MEDIA_IMAGE);
        existing.setCurrentVersion(1);
        when(assetMapper.selectOne(any())).thenReturn(existing);

        MediaImportVO vo = bridge.importFromMediaTask(req, 100L, false);

        assertFalse(vo.isCreated());
        assertTrue(vo.isDuplicate());
        assertEquals(88L, vo.getAssetId());
        assertEquals("已入库的猫图", vo.getName());
        // 权限闸过 + 不重复建（媒体查询/insert 均不再触达）
        verify(aclService).requireWrite(5L, 100L, false);
        verify(mediaGenQueryService, never()).loadImageForImport(anyLong(), anyInt(), anyLong(), anyBoolean());
        verify(assetMapper, never()).insert(any());
    }

    @Test
    void existsBySource_mapsTaskIdToFirstAssetId() {
        Asset a1 = new Asset();
        a1.setId(11L);
        a1.setGenMeta("{\"source\":\"MEDIA\",\"taskId\":7}");
        Asset a2 = new Asset();
        a2.setId(12L);
        a2.setGenMeta("{\"source\":\"MEDIA\",\"taskId\":7,\"imageIdx\":1}"); // 同任务多图多资产→取首
        Asset a3 = new Asset();
        a3.setId(13L);
        a3.setGenMeta("{\"source\":\"CANVAS\"}"); // 非媒体来源→无 taskId 键，忽略
        when(assetMapper.selectList(any())).thenReturn(List.of(a1, a2, a3));

        Map<Long, Long> map = bridge.existsBySourceTaskIds(List.of(7L, 9L));

        assertEquals(11L, map.get(7L));
        assertNull(map.get(9L));
        assertEquals(1, map.size());
    }

    @Test
    void existsBySource_emptyInput_returnsEmpty() {
        assertTrue(bridge.existsBySourceTaskIds(List.of()).isEmpty());
        assertTrue(bridge.existsBySourceTaskIds(null).isEmpty());
        verify(assetMapper, never()).selectList(any());
    }

    /** 捕获 insert 的 Asset 以断言字段（assetMapper.insert 返回受影响行数，附带捕获实参）。 */
    private static class AssetImportAnswer implements org.mockito.stubbing.Answer<Integer> {
        Asset captured;
        @Override
        public Integer answer(org.mockito.invocation.InvocationOnMock inv) {
            captured = inv.getArgument(0);
            return 1;
        }
    }
}
