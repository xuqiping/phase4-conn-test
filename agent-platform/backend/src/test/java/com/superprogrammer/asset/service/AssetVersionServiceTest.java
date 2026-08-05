package com.superprogrammer.asset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.asset.dto.ConsistencyPackRequest;
import com.superprogrammer.asset.dto.VersionCreateRequest;
import com.superprogrammer.asset.dto.VersionVO;
import com.superprogrammer.asset.entity.Asset;
import com.superprogrammer.asset.entity.AssetVersion;
import com.superprogrammer.asset.mapper.AssetMapper;
import com.superprogrammer.asset.mapper.AssetVersionMapper;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AssetVersionService 单测：自动版本（乐观锁）+ 并发冲突 + 列表 meta only + 一致性包合并（plan §S5 验证）。
 */
@ExtendWith(MockitoExtension.class)
class AssetVersionServiceTest {

    private static final Long ASSET_ID = 100L;
    private static final Long OWNER_ID = 10L;

    @Mock private AssetMapper assetMapper;
    @Mock private AssetVersionMapper versionMapper;
    @Mock private AssetAclService aclService;

    private AssetVersionService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Asset.class);
        TableInfoHelper.initTableInfo(assistant, AssetVersion.class);
    }

    @BeforeEach
    void setUp() {
        service = new AssetVersionService(assetMapper, versionMapper, aclService, new ObjectMapper());
    }

    @Test
    void createVersion_textAsset_bumpsAndInsertsAndSyncsContent() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset(Asset.MEDIA_PROMPT, 2));
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);
        when(assetMapper.bumpVersionOptimistic(ASSET_ID, 2, OWNER_ID)).thenReturn(1);

        VersionCreateRequest req = new VersionCreateRequest();
        req.setContent("{\"body\":\"v3 text\"}");
        req.setChangeNote("第三版");
        int newVer = service.createVersion(ASSET_ID, OWNER_ID, false, req);

        assertEquals(3, newVer);
        // 落版本号 = 旧 + 1
        ArgumentCaptor<AssetVersion> vc = ArgumentCaptor.forClass(AssetVersion.class);
        verify(versionMapper).insert(vc.capture());
        assertEquals(3, vc.getValue().getVersion());
        assertEquals("第三版", vc.getValue().getChangeNote());
        // 文本类同步 assets.content
        verify(assetMapper).updateContent(eq(ASSET_ID), eq("{\"body\":\"v3 text\"}"), eq(OWNER_ID));
    }

    @Test
    void createVersion_concurrentConflict_throws409() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset(Asset.MEDIA_PROMPT, 2));
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);
        // 乐观锁：版本号已被他人改过 → 0 行
        when(assetMapper.bumpVersionOptimistic(ASSET_ID, 2, OWNER_ID)).thenReturn(0);

        VersionCreateRequest req = new VersionCreateRequest();
        req.setContent("{\"body\":\"x\"}");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createVersion(ASSET_ID, OWNER_ID, false, req));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(versionMapper, never()).insert(any());
        verify(assetMapper, never()).updateContent(any(), any(), any());
    }

    @Test
    void createVersion_fileAsset_requiresFileId() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset(Asset.MEDIA_IMAGE, 1));
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);
        VersionCreateRequest req = new VersionCreateRequest(); // 无 fileId
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createVersion(ASSET_ID, OWNER_ID, false, req));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(assetMapper, never()).bumpVersionOptimistic(any(), eq(1), any());
    }

    @Test
    void listVersions_metaOnlyAndDesc() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset(Asset.MEDIA_PROMPT, 3));
        when(aclService.loadAccessible(1L, OWNER_ID, false)).thenReturn(null);
        AssetVersion v1 = version(1, "{\"body\":\"v1\"}");
        AssetVersion v3 = version(3, "{\"body\":\"v3\"}");
        AssetVersion v2 = version(2, "{\"body\":\"v2\"}");
        when(versionMapper.selectList(any())).thenReturn(List.of(v1, v3, v2));

        List<VersionVO> result = service.listVersions(ASSET_ID, OWNER_ID, false);

        // 倒序
        assertEquals(3, result.get(0).getVersion());
        assertEquals(1, result.get(2).getVersion());
        // meta only：列表不带 content
        result.forEach(v -> assertFalse(v.getContent() != null, "列表不得带回 content"));
    }

    @Test
    void getVersion_returnsContent() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset(Asset.MEDIA_PROMPT, 2));
        when(aclService.loadAccessible(1L, OWNER_ID, false)).thenReturn(null);
        when(versionMapper.selectOne(any())).thenReturn(version(2, "{\"body\":\"v2\"}"));

        VersionVO vo = service.getVersion(ASSET_ID, 2, OWNER_ID, false);
        assertEquals(2, vo.getVersion());
        assertEquals("{\"body\":\"v2\"}", vo.getContent());
    }

    @Test
    void saveConsistencyPack_partialMergeKeepsExistingFields() {
        // 既有 content：standardDescription=old
        Asset a = asset(Asset.MEDIA_IMAGE, 1);
        a.setContent("{\"consistency\":{\"standardDescription\":\"old\"}}");
        when(assetMapper.selectById(ASSET_ID)).thenReturn(a);
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);
        when(assetMapper.bumpVersionOptimistic(ASSET_ID, 1, OWNER_ID)).thenReturn(1);

        ConsistencyPackRequest req = new ConsistencyPackRequest();
        req.setMainRefImageFileId("file-ref-1"); // 只改主参考图
        req.setGalleryFileIds(List.of("g1", "g2"));
        int newVer = service.saveConsistencyPack(ASSET_ID, OWNER_ID, false, req);

        assertEquals(2, newVer);
        ArgumentCaptor<AssetVersion> vc = ArgumentCaptor.forClass(AssetVersion.class);
        verify(versionMapper).insert(vc.capture());
        String content = vc.getValue().getContent();
        assertTrue(content.contains("\"mainRefImageFileId\":\"file-ref-1\""), "含主参考图");
        assertTrue(content.contains("\"standardDescription\":\"old\""), "保留旧标准描述");
        assertTrue(content.contains("g1") && content.contains("g2"), "含图集");
    }

    @Test
    void mergeConsistencyPack_nullSkipsField() {
        // 空包合并直接构造空 content
        ConsistencyPackRequest req = new ConsistencyPackRequest();
        req.setStandardDescription("30岁女性，旗袍");
        String merged = service.mergeConsistencyPack("{}", req);
        assertTrue(merged.contains("\"standardDescription\":\"30岁女性，旗袍\""));
        assertTrue(merged.contains("\"consistency\""), "落 consistency 键");
        // mainRefImageFileId 未给 → 不出现
        assertFalse(merged.contains("mainRefImageFileId"));
    }

    private Asset asset(String mediaType, int currentVersion) {
        Asset a = new Asset();
        a.setId(ASSET_ID);
        a.setProjectId(1L);
        a.setMediaType(mediaType);
        a.setName("a");
        a.setStatus(Asset.STATUS_DRAFT);
        a.setCurrentVersion(currentVersion);
        a.setContent("{}");
        return a;
    }

    private AssetVersion version(int ver, String content) {
        AssetVersion v = new AssetVersion();
        v.setAssetId(ASSET_ID);
        v.setVersion(ver);
        v.setContent(content);
        return v;
    }
}
