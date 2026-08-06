package com.superprogrammer.asset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.asset.dto.SceneVO;
import com.superprogrammer.asset.dto.ScriptBreakdownRequest;
import com.superprogrammer.asset.dto.ScriptBreakdownVO;
import com.superprogrammer.asset.dto.StoryboardBreakdownRequest;
import com.superprogrammer.asset.dto.StoryboardBreakdownVO;
import com.superprogrammer.asset.entity.Asset;
import com.superprogrammer.asset.mapper.AssetMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AssetScriptService 单测：AI 拆分场 三态容错（正常 JSON / 带围栏 / 纯文本兜底）+ 类型校验（plan §S6 验证）。
 */
@ExtendWith(MockitoExtension.class)
class AssetScriptServiceTest {

    private static final Long ASSET_ID = 50L;
    private static final Long OWNER_ID = 10L;

    @Mock private AssetMapper assetMapper;
    @Mock private AssetAclService aclService;
    @Mock private AssetVersionService versionService;
    @Mock private LlmGateway llmGateway;
    @Mock private AssetService assetService;
    @Mock private AssetProjectService assetProjectService;

    private AssetScriptService service;

    @BeforeEach
    void setUp() {
        service = new AssetScriptService(assetMapper, aclService, versionService, llmGateway,
                new ObjectMapper(), assetService, assetProjectService);
        ReflectionTestUtils.setField(service, "defaultModel", "doubao-seed-2.0-code");
    }

    @Test
    void breakdown_normalJson_parsesAndCreatesVersion() {
        // 规范键 synopsis（与 AssetCanvasBridgeService.extractTextContent / 前端新建剧本一致）
        scriptAsset("{\"synopsis\":\"老板娘进门\"}");
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);
        when(llmGateway.chat(any(), eq(OWNER_ID))).thenReturn(
                LlmResponse.builder().content("[{\"index\":1,\"description\":\"开场\"},{\"index\":2,\"description\":\"进门\"}]").build());
        when(versionService.createVersion(eq(ASSET_ID), eq(OWNER_ID), eq(false), any())).thenReturn(2);

        ScriptBreakdownRequest req = new ScriptBreakdownRequest();
        req.setModel("gpt-4o-mini");
        ScriptBreakdownVO vo = service.breakdown(ASSET_ID, OWNER_ID, false, req);

        assertEquals(2, vo.getScenes().size());
        assertEquals("开场", vo.getScenes().get(0).getDescription());
        assertEquals(2, vo.getVersion());
        // 写入版本
        verify(versionService).createVersion(eq(ASSET_ID), eq(OWNER_ID), eq(false), any());
    }

    @Test
    void breakdown_legacyBodyKey_stillRead() {
        // 旧态/历史数据用 body 键 → 回退兼容读取
        scriptAsset("{\"body\":\"剧本\"}");
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);
        when(llmGateway.chat(any(), eq(OWNER_ID))).thenReturn(LlmResponse.builder()
                .content("[{\"index\":1,\"description\":\"x\"}]").build());
        when(versionService.createVersion(any(), eq(OWNER_ID), eq(false), any())).thenReturn(2);

        ScriptBreakdownVO vo = service.breakdown(ASSET_ID, OWNER_ID, false, new ScriptBreakdownRequest());

        assertEquals(1, vo.getScenes().size());
    }

    @Test
    void breakdown_fencedJson_stripsFence() {
        scriptAsset("{\"synopsis\":\"剧本\"}");
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);
        when(llmGateway.chat(any(), eq(OWNER_ID))).thenReturn(LlmResponse.builder()
                .content("```json\n[{\"index\":1,\"description\":\" fenced \"}]\n```").build());
        when(versionService.createVersion(any(), eq(OWNER_ID), eq(false), any())).thenReturn(2);

        ScriptBreakdownVO vo = service.breakdown(ASSET_ID, OWNER_ID, false, new ScriptBreakdownRequest());

        assertEquals(1, vo.getScenes().size());
        assertTrue(vo.getScenes().get(0).getDescription().contains("fenced"));
    }

    @Test
    void breakdown_plainText_fallbackSingleScene() {
        scriptAsset("{\"synopsis\":\"剧本\"}");
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);
        // LLM 返回纯文本（非 JSON）→ 兜底单分场，不阻断
        when(llmGateway.chat(any(), eq(OWNER_ID))).thenReturn(LlmResponse.builder()
                .content("今天天气不错").build());
        when(versionService.createVersion(any(), eq(OWNER_ID), eq(false), any())).thenReturn(2);

        ScriptBreakdownVO vo = service.breakdown(ASSET_ID, OWNER_ID, false, new ScriptBreakdownRequest());

        assertEquals(1, vo.getScenes().size());
        assertEquals("今天天气不错", vo.getScenes().get(0).getDescription());
    }

    @Test
    void breakdown_nonScriptType_throws400() {
        Asset a = asset(Asset.MEDIA_PROMPT, "{\"body\":\"x\"}");
        when(assetMapper.selectById(ASSET_ID)).thenReturn(a);
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.breakdown(ASSET_ID, OWNER_ID, false, new ScriptBreakdownRequest()));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(llmGateway, org.mockito.Mockito.never()).chat(any(), any());
    }

    @Test
    void breakdown_emptyBody_throws400() {
        scriptAsset("{\"synopsis\":\"  \"}");
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.breakdown(ASSET_ID, OWNER_ID, false, new ScriptBreakdownRequest()));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void breakdown_usesDefaultModelWhenAbsent() {
        scriptAsset("{\"synopsis\":\"剧本\"}");
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);
        when(llmGateway.chat(any(), eq(OWNER_ID))).thenReturn(LlmResponse.builder()
                .content("[{\"index\":1,\"description\":\"x\"}]").build());
        when(versionService.createVersion(any(), eq(OWNER_ID), eq(false), any())).thenReturn(2);

        service.breakdown(ASSET_ID, OWNER_ID, false, new ScriptBreakdownRequest()); // model 缺省

        ArgumentCaptor<LlmRequest> cap = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmGateway).chat(cap.capture(), eq(OWNER_ID));
        assertEquals("doubao-seed-2.0-code", cap.getValue().getModel());
    }

    // ==================== 一键分镜（S19） ====================

    @Test
    void storyboard_normalJson_createsOneAssetPerShot() {
        scriptAsset("{\"synopsis\":\"老板娘进门\"}");
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);
        when(assetService.getImageCatalog(eq(1L), anyInt())).thenReturn(List.of());
        when(llmGateway.chat(any(), eq(OWNER_ID))).thenReturn(LlmResponse.builder()
                .content("[{\"index\":1,\"prompt\":\"开场镜\"},{\"index\":2,\"prompt\":\"进门镜\",\"entities\":[]}]").build());
        when(assetService.internalCreateText(eq(1L), eq(Asset.MEDIA_STORYBOARD), any(), any(), any(), any()))
                .thenReturn(stubAsset(100L)).thenReturn(stubAsset(101L));
        when(versionService.createVersion(eq(ASSET_ID), eq(OWNER_ID), eq(false), any())).thenReturn(3);

        StoryboardBreakdownVO vo = service.breakdownStoryboard(ASSET_ID, OWNER_ID, false, new StoryboardBreakdownRequest());

        assertEquals(2, vo.getCount());
        assertEquals(List.of(100L, 101L), vo.getCreatedAssetIds());
        assertEquals(3, vo.getVersion());
        verify(assetProjectService).ensureMediaType(1L, Asset.MEDIA_STORYBOARD, Asset.CATEGORY_TEXT);
        verify(assetService, times(2)).internalCreateText(eq(1L), eq(Asset.MEDIA_STORYBOARD), any(), any(), any(), any());
    }

    @Test
    void storyboard_illegalAssetId_nulledInContent() {
        scriptAsset("{\"synopsis\":\"剧本\"}");
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);
        // 目录仅含 id=100；LLM 返幻觉 id=999（应被置 null，L16）
        when(assetService.getImageCatalog(eq(1L), anyInt())).thenReturn(
                List.of(new AssetService.ImageCatalogItem(100L, "主角图", Asset.MEDIA_IMAGE, List.of("人物"))));
        when(llmGateway.chat(any(), eq(OWNER_ID))).thenReturn(LlmResponse.builder()
                .content("[{\"index\":1,\"prompt\":\"x\",\"entities\":[{\"key\":\"主角\",\"assetId\":999}]}]").build());
        when(assetService.internalCreateText(eq(1L), eq(Asset.MEDIA_STORYBOARD), any(), any(), any(), any()))
                .thenReturn(stubAsset(200L));
        when(versionService.createVersion(eq(ASSET_ID), eq(OWNER_ID), eq(false), any())).thenReturn(2);

        service.breakdownStoryboard(ASSET_ID, OWNER_ID, false, new StoryboardBreakdownRequest());

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(assetService).internalCreateText(eq(1L), eq(Asset.MEDIA_STORYBOARD), any(), any(), cap.capture(), any());
        // content 中 entityRefs[0].assetId 应为 null（非法 id 被剔除，不崩、留 key 痕迹）
        assertFalse(cap.getValue().contains("999"), "非法 assetId 不应写入 content");
        assertTrue(cap.getValue().contains("\"key\":\"主角\""), "实体 key 痕迹保留");
    }

    @Test
    void storyboard_validAssetId_enrichedFromCatalog() {
        scriptAsset("{\"synopsis\":\"剧本\"}");
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);
        when(assetService.getImageCatalog(eq(1L), anyInt())).thenReturn(
                List.of(new AssetService.ImageCatalogItem(100L, "主角图", Asset.MEDIA_IMAGE, List.of("人物"))));
        when(llmGateway.chat(any(), eq(OWNER_ID))).thenReturn(LlmResponse.builder()
                .content("[{\"index\":1,\"prompt\":\"x\",\"entities\":[{\"key\":\"主角\",\"assetId\":100}]}]").build());
        when(assetService.internalCreateText(eq(1L), eq(Asset.MEDIA_STORYBOARD), any(), any(), any(), any()))
                .thenReturn(stubAsset(300L));
        when(versionService.createVersion(eq(ASSET_ID), eq(OWNER_ID), eq(false), any())).thenReturn(2);

        service.breakdownStoryboard(ASSET_ID, OWNER_ID, false, new StoryboardBreakdownRequest());

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(assetService).internalCreateText(eq(1L), eq(Asset.MEDIA_STORYBOARD), any(), any(), cap.capture(), any());
        assertTrue(cap.getValue().contains("\"assetId\":100"), "合法 assetId 保留");
        assertTrue(cap.getValue().contains("主角图"), "name 取自目录富化");
        assertTrue(cap.getValue().contains("shotIndex"), "content 含 shotIndex");
    }

    @Test
    void storyboard_nonScript_throws400() {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset(Asset.MEDIA_PROMPT, "{\"body\":\"x\"}"));
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.breakdownStoryboard(ASSET_ID, OWNER_ID, false, new StoryboardBreakdownRequest()));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(assetService, org.mockito.Mockito.never()).internalCreateText(any(), any(), any(), any(), any(), any());
    }

    @Test
    void storyboard_emptyBody_throws400() {
        scriptAsset("{\"synopsis\":\"  \"}");
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.breakdownStoryboard(ASSET_ID, OWNER_ID, false, new StoryboardBreakdownRequest()));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void storyboard_llmFailure_fixedMessageNoLeak() {
        scriptAsset("{\"synopsis\":\"剧本\"}");
        when(aclService.requireWrite(1L, OWNER_ID, false)).thenReturn(null);
        when(assetService.getImageCatalog(eq(1L), anyInt())).thenReturn(List.of());
        when(llmGateway.chat(any(), eq(OWNER_ID))).thenThrow(new RuntimeException("upstream timeout detail"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.breakdownStoryboard(ASSET_ID, OWNER_ID, false, new StoryboardBreakdownRequest()));
        assertEquals(ErrorCode.INTERNAL_ERROR.getCode(), ex.getCode());
        // 固定话术，不透传 e.getMessage()
        assertTrue(ex.getMessage() == null || !ex.getMessage().contains("upstream timeout detail"));
        verify(assetService, org.mockito.Mockito.never()).internalCreateText(any(), any(), any(), any(), any(), any());
    }

    private Asset stubAsset(long id) {
        Asset a = new Asset();
        a.setId(id);
        a.setProjectId(1L);
        a.setMediaType(Asset.MEDIA_STORYBOARD);
        return a;
    }

    private void scriptAsset(String content) {
        when(assetMapper.selectById(ASSET_ID)).thenReturn(asset(Asset.MEDIA_SCRIPT, content));
    }

    private Asset asset(String mediaType, String content) {
        Asset a = new Asset();
        a.setId(ASSET_ID);
        a.setProjectId(1L);
        a.setMediaType(mediaType);
        a.setName("s");
        a.setStatus(Asset.STATUS_DRAFT);
        a.setCurrentVersion(1);
        a.setContent(content);
        return a;
    }
}
