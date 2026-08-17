package com.superprogrammer.media.reverse.dto;

import com.superprogrammer.media.reverse.VideoReverseService;

import java.util.List;
import java.util.Map;

/**
 * 反推分析响应（spec §4.1）。keyframes 恒有（另两模式的前置）；storyboard/script 按请求 modes 带。
 *
 * <p>LLM 产物用 {@code Map} 透传而非强类型 record：LLM 字段开放（多字段/漏字段均可能），
 * 强类型在未知字段上解析脆；前端按宽松类型消费（提示词已约束核心字段名）。
 */
public record ReverseAnalyzeResponse(
        List<VideoReverseService.KeyFrame> keyframes,
        double durationSeconds,
        String mode,
        int sceneHits,
        List<Map<String, Object>> storyboard,
        Map<String, Object> script,
        String model) {
}
