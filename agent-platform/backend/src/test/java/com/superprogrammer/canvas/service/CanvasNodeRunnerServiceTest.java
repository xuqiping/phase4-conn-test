package com.superprogrammer.canvas.service;

import com.superprogrammer.canvas.dto.CanvasNodeDTO;
import com.superprogrammer.canvas.dto.NodeRunResult;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * CanvasNodeRunnerService 单测：文本节点走 LlmGateway + image/video 分发话术 + 输入校验。
 * LlmGateway 用 Mockito；defaultTextModel 用 ReflectionTestUtils 注入（绕 @Value）。
 */
@ExtendWith(MockitoExtension.class)
class CanvasNodeRunnerServiceTest {

    @Mock
    private LlmGateway llmGateway;

    private CanvasNodeRunnerService runner;

    @BeforeEach
    void setUp() {
        runner = new CanvasNodeRunnerService(llmGateway);
        ReflectionTestUtils.setField(runner, "defaultTextModel", "doubao-seed-2.0-code");
    }

    @Test
    void run_text_success_picksModelFromData_andReturnsOutput() {
        CanvasNodeDTO node = nodeOf("n1", CanvasNodeDTO.TYPE_TEXT,
                Map.of("prompt", "写一句slogan", "model", "doubao-seed-2.0-code"));
        when(llmGateway.chat(any(), eq(7L))).thenReturn(
                LlmResponse.builder().content("你好世界").build());

        NodeRunResult r = runner.run(node, 7L);

        assertEquals("n1", r.getNodeId());
        assertEquals("success", r.getStatus());
        assertEquals("你好世界", r.getDataPatch().get("outputText"));
        assertEquals("success", r.getDataPatch().get("status"));
    }

    @Test
    void run_text_blankPrompt_throws400() {
        CanvasNodeDTO node = nodeOf("n2", CanvasNodeDTO.TYPE_TEXT, Map.of("prompt", "   "));
        BusinessException ex = assertThrows(BusinessException.class, () -> runner.run(node, 7L));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void run_text_usesDefaultModel_whenDataModelMissing() {
        CanvasNodeDTO node = nodeOf("n3", CanvasNodeDTO.TYPE_TEXT, Map.of("prompt", "hi"));
        when(llmGateway.chat(any(), eq(7L))).thenReturn(
                LlmResponse.builder().content("ok").build());
        NodeRunResult r = runner.run(node, 7L);
        assertEquals("doubao-seed-2.0-code", r.getDataPatch().get("model"),
                "缺省 model 应走 defaultTextModel");
    }

    @Test
    void run_text_llmFailure_returnsFailedResult_notThrow() {
        CanvasNodeDTO node = nodeOf("n4", CanvasNodeDTO.TYPE_TEXT, Map.of("prompt", "hi"));
        when(llmGateway.chat(any(), eq(7L))).thenThrow(new RuntimeException("provider down"));
        NodeRunResult r = runner.run(node, 7L);
        assertEquals("failed", r.getStatus());
        assertEquals("failed", r.getDataPatch().get("status"));
        assertEquals("文本生成失败，请稍后重试", r.getErrorMsg());
    }

    @Test
    void run_image_throws422_noGenProvider() {
        CanvasNodeDTO node = nodeOf("n5", CanvasNodeDTO.TYPE_IMAGE, Map.of("prompt", "a cat"));
        BusinessException ex = assertThrows(BusinessException.class, () -> runner.run(node, 7L));
        assertEquals(ErrorCode.UNPROCESSABLE.getCode(), ex.getCode());
    }

    @Test
    void run_video_notYetImplemented_throws422() {
        CanvasNodeDTO node = nodeOf("n6", CanvasNodeDTO.TYPE_VIDEO, Map.of("prompt", "x"));
        BusinessException ex = assertThrows(BusinessException.class, () -> runner.run(node, 7L));
        assertEquals(ErrorCode.UNPROCESSABLE.getCode(), ex.getCode());
    }

    @Test
    void run_unknownType_throws400() {
        CanvasNodeDTO node = nodeOf("n7", "weird", Map.of());
        BusinessException ex = assertThrows(BusinessException.class, () -> runner.run(node, 7L));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    private CanvasNodeDTO nodeOf(String id, String type, Map<String, Object> data) {
        CanvasNodeDTO n = new CanvasNodeDTO();
        n.setId(id);
        n.setType(type);
        n.setData(data);
        return n;
    }
}
