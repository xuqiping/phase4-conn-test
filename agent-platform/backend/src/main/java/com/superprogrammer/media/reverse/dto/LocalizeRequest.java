package com.superprogrammer.media.reverse.dto;

import lombok.Data;

/**
 * 本土化转绘请求（spec §4.2）。纯文本 LLM 改写：剧情/分镜结构不变，源文化元素→目标文化元素。
 */
@Data
public class LocalizeRequest {

    /** 剧本（反推所得 JSON 文本或用户改后文本），≤8000 字（对齐提示词上限）。 */
    private String script;

    /** 目标国家/地区（如「美国/西方」），≤32 字。 */
    private String targetLocale;

    /** 可选：额外保留要求（如「保留春节团圆情节」），≤500 字。 */
    private String notes;

    /** 可选：改写模型（空=管理员默认对话模型）。 */
    private String model;

    /** 可选：参与项目组 id（组池计费）。 */
    private Long projectGroupId;
}
