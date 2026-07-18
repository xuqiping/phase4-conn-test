package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

/**
 * M2:per-key 时序事实标记 VO。
 * <p>
 * 前端:isTemporal=null(无行)= 首次待询问;true=时序(value 带日期段);false=非时序(中文逗号 join)。
 * source:LLM_ASK / USER_OVERRIDE(panel 手改)。
 */
@Data
@Builder
public class MemoryKeyMetaVO {
    private String memoryKey;
    private Boolean isTemporal;
    private String source;
}
