package com.superprogrammer.knowledge.attachment;

import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.knowledge.config.RagRecallProperties;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C2 附件注入三路分流（WP1 Step6，规格 §4.3）：文本直注 / 图片缓存命中 / 图片实时 VLM+写缓存 /
 * 超时·故障·空返回·缺 visionModel 降级 / 非图片无全文不注入 / 计费归户 docOwner。
 */
@ExtendWith(MockitoExtension.class)
class AttachmentContentInjectorTest {

    private static final Long DOC_ID = 5L;
    private static final Long OWNER = 7L;

    @Mock private AttachmentVisionCache visionCache;
    @Mock private FileStorageService fileStorageService;
    @Mock private LlmGateway llmGateway;
    @Spy private RagRecallProperties recallProps = new RagRecallProperties();

    @InjectMocks private AttachmentContentInjector injector;

    private KnowledgeDocument attachDoc(String parseOptions) {
        KnowledgeDocument d = new KnowledgeDocument();
        d.setId(DOC_ID);
        d.setKbId(1L);
        d.setTitle("架构图");
        d.setDocType("FILE");
        d.setFileRef("/api/files/f1.png");
        d.setCreatedBy(OWNER);
        d.setParseOptions(parseOptions);
        return d;
    }

    private Map<String, Object> textMeta(String attachmentText) {
        Map<String, Object> m = new HashMap<>();
        m.put("attachMode", true);
        m.put("fileRef", "/api/files/f1.txt");
        m.put("originalName", "部署手册.txt");
        m.put("attachmentText", attachmentText);
        return m;
    }

    private Map<String, Object> imageMeta() {
        Map<String, Object> m = new HashMap<>();
        m.put("attachMode", true);
        m.put("fileRef", "/api/files/f1.png");
        m.put("originalName", "架构图.png");
        m.put("mime", "image/png");
        return m;
    }

    private static String sha256(String input) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ---- 路径1：文本类直注（零 IO 零 LLM）----

    @Test
    void textAttachment_directInject_noIoNoLlm() {
        String block = injector.inject(attachDoc(null), textMeta("第一章 部署拓扑……"));

        assertNotNull(block);
        assertTrue(block.startsWith("[附件 部署手册.txt] 内容："));
        assertTrue(block.contains("部署拓扑"));
        verifyNoInteractions(visionCache, fileStorageService, llmGateway);
    }

    @Test
    void textAttachment_overCap_truncatedWithMark() {
        recallProps.getAttachment().setMaxInjectChars(10);
        String block = injector.inject(attachDoc(null), textMeta("长".repeat(50)));

        assertTrue(block.contains("长".repeat(10)));
        assertFalse(block.contains("长".repeat(11)));
        assertTrue(block.contains("已截断，可下载原件"));
    }

    @Test
    void textAttachment_preTruncatedFlag_markEvenAtEqualLength() {
        // Phase4 实测修复：解析侧已截 8000 → metadata 透传标志；注入文本长度恰好=上限时
        // 比长度判不出截断（实测证据静默断句），标志命中也必须补「已截断」标注
        recallProps.getAttachment().setMaxInjectChars(8000);
        Map<String, Object> m = textMeta("长".repeat(8000));
        m.put("attachmentTruncated", true);
        String block = injector.inject(attachDoc(null), m);

        assertTrue(block.endsWith("（已截断，可下载原件查看全文）"));
    }

    @Test
    void nonImage_noText_returnsNull() {
        Map<String, Object> m = imageMeta();
        m.put("fileRef", "/api/files/f1.pdf");
        m.remove("mime");
        assertNull(injector.inject(attachDoc(null), m));
        verifyNoInteractions(visionCache, fileStorageService, llmGateway);
    }

    // ---- 路径2：图片——缓存命中不调 VLM ----

    @Test
    void imageCacheHit_noVlmNoFileLoad() throws Exception {
        String key = sha256("/api/files/f1.png|qwen-vl|v1");
        when(visionCache.get(key)).thenReturn("图中有三个模块");

        String block = injector.inject(attachDoc("{\"visionModel\":\"qwen-vl\"}"), imageMeta());

        assertTrue(block.contains("图中有三个模块"));
        verify(llmGateway, never()).chat(any(), any());
        verify(fileStorageService, never()).load(anyString(), any(), anyBoolean());
        verify(visionCache, never()).put(anyString(), anyString());
    }

    // ---- 路径2：图片——miss 实时 VLM + 写缓存 + 计费归户 docOwner ----

    @Test
    void imageCacheMiss_vlmCalled_billedToOwner_cacheWritten() throws Exception {
        String key = sha256("/api/files/f1.png|qwen-vl|v1");
        when(visionCache.get(key)).thenReturn(null);
        when(fileStorageService.load(eq("f1.png"), eq(OWNER), anyBoolean()))
                .thenReturn(new ByteArrayResource("IMG".getBytes(StandardCharsets.UTF_8)));
        when(llmGateway.chat(any(), eq(OWNER))).thenReturn(
                LlmResponse.builder().content("该图展示三层架构").build());

        String block = injector.inject(attachDoc("{\"visionModel\":\"qwen-vl\"}"), imageMeta());

        assertTrue(block.contains("该图展示三层架构"));
        // 计费归户 docOwner：chat 第二参=文档 owner，非检索请求者
        ArgumentCaptor<LlmRequest> reqCap = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmGateway).chat(reqCap.capture(), eq(OWNER));
        assertEquals("qwen-vl", reqCap.getValue().getModel());
        assertEquals(30000, reqCap.getValue().getTimeoutMs());   // Phase4 实测：真实 VL 首图 24s
        // 识图文本写缓存（下次同图同模型直取）
        verify(visionCache).put(eq(key), eq("该图展示三层架构"));
    }

    // ---- 降级四态 ----

    @Test
    void imageVlmTimeout_degradesToPlaceholder() throws Exception {
        when(visionCache.get(anyString())).thenReturn(null);
        when(fileStorageService.load(eq("f1.png"), eq(OWNER), anyBoolean()))
                .thenReturn(new ByteArrayResource("IMG".getBytes(StandardCharsets.UTF_8)));
        when(llmGateway.chat(any(), any())).thenThrow(new RuntimeException("read timeout"));

        String block = injector.inject(attachDoc("{\"visionModel\":\"qwen-vl\"}"), imageMeta());

        assertTrue(block.contains("原件内容暂缺"));
        verify(visionCache, never()).put(anyString(), anyString());
    }

    @Test
    void imageNoVisionModel_degrades_noGatewayCall() {
        String block = injector.inject(attachDoc("{\"indexMode\":\"ATTACHMENT\"}"), imageMeta());

        assertTrue(block.contains("原件内容暂缺"));
        verifyNoInteractions(llmGateway, fileStorageService);
    }

    @Test
    void imageVlmBlankResult_degrades() throws Exception {
        when(visionCache.get(anyString())).thenReturn(null);
        when(fileStorageService.load(eq("f1.png"), eq(OWNER), anyBoolean()))
                .thenReturn(new ByteArrayResource("IMG".getBytes(StandardCharsets.UTF_8)));
        when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder().content("  ").build());

        assertTrue(injector.inject(attachDoc("{\"visionModel\":\"qwen-vl\"}"), imageMeta())
                .contains("原件内容暂缺"));
    }

    @Test
    void imageVlmPoliteRefusal_degradesAndNotCached() throws Exception {
        // Phase4 实测修复（Bug #7）：纯文本模型被配成 visionModel → 礼貌回绝「无法查看」非空文本，
        // 不得当描述入缓存/注入，须降级占位
        when(visionCache.get(anyString())).thenReturn(null);
        when(fileStorageService.load(eq("f1.png"), eq(OWNER), anyBoolean()))
                .thenReturn(new ByteArrayResource("IMG".getBytes(StandardCharsets.UTF_8)));
        when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder()
                .content("抱歉，我无法查看这张图片。虽然消息中包含了图片链接，但该图像内容未能实际加载。").build());

        String block = injector.inject(attachDoc("{\"visionModel\":\"glm-5.1\"}"), imageMeta());

        assertTrue(block.contains("原件内容暂缺"));
        assertFalse(block.contains("抱歉"));
        verify(visionCache, never()).put(anyString(), anyString());
    }

    @Test
    void imageVlmEnglishRefusal_degrades() throws Exception {
        when(visionCache.get(anyString())).thenReturn(null);
        when(fileStorageService.load(eq("f1.png"), eq(OWNER), anyBoolean()))
                .thenReturn(new ByteArrayResource("IMG".getBytes(StandardCharsets.UTF_8)));
        when(llmGateway.chat(any(), any())).thenReturn(LlmResponse.builder()
                .content("Sorry, I cannot view this image as it was provided as an external URL.").build());

        assertTrue(injector.inject(attachDoc("{\"visionModel\":\"qwen-vl\"}"), imageMeta())
                .contains("原件内容暂缺"));
        verify(visionCache, never()).put(anyString(), anyString());
    }

    @Test
    void imageFileLoadFails_degrades() throws Exception {
        when(visionCache.get(anyString())).thenReturn(null);
        when(fileStorageService.load(eq("f1.png"), eq(OWNER), anyBoolean()))
                .thenThrow(new RuntimeException("存储不可用"));

        assertTrue(injector.inject(attachDoc("{\"visionModel\":\"qwen-vl\"}"), imageMeta())
                .contains("原件内容暂缺"));
        verify(llmGateway, never()).chat(any(), any());
    }
}
