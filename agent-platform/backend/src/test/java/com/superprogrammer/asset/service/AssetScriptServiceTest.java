package com.superprogrammer.asset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.asset.dto.SceneVO;
import com.superprogrammer.asset.dto.ScriptBreakdownRequest;
import com.superprogrammer.asset.dto.ScriptBreakdownVO;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    private AssetScriptService service;

    @BeforeEach
    void setUp() {
        service = new AssetScriptService(assetMapper, aclService, versionService, llmGateway, new ObjectMapper());
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
