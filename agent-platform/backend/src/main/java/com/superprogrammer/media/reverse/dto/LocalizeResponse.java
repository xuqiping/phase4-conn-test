package com.superprogrammer.media.reverse.dto;

import java.util.List;
import java.util.Map;

/**
 * 本土化转绘响应（spec §4.2）。localizedScript=改写剧本（JSON 文本，与输入同结构）；
 * changeLog=文化元素替换清单供人工核对；warning=结构校验告警（镜头/场景数不一致时非空，结果仍可用）。
 */
public record LocalizeResponse(
        String localizedScript,
        List<Map<String, Object>> changeLog,
        String warning) {
}
