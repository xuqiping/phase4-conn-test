package com.superprogrammer.system.dto;

import lombok.Builder;
import lombok.Data;

/** RAG 召回 query 扩展全局开关视图。
 *  4 条检索路径（/retrieve、/ask、Chat 注入、Agent/工作流）同读，保证调试与真实一致。 */
@Data
@Builder
public class RagRecallSettingsVO {
    /** 扩展开关：true=改写+HyDE/切块多路；false=单 query 直接 embed。默认 true。 */
    private Boolean enabled;
    /** 切块触发阈值（字数）。输入 > 阈值 → 切块多路召回（多主题不丢内容）；≤ 阈值 → 改写+HyDE。默认 200。 */
    private Integer threshold;
}
