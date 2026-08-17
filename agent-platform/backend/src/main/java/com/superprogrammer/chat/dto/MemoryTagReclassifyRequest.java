package com.superprogrammer.chat.dto;

import lombok.Data;

/**
 * 5x 四轮 C8（U7）：标签「重新归类」请求体。
 * <p>
 * 筛选范围内<b>未挂目标标签</b>的本人流水账，LLM 判定命中后 tag_ids <b>只增补不删旧</b>（拍板⑤）。
 */
@Data
public class MemoryTagReclassifyRequest {

    /** true=只扫标签创建之前的行（建标签前产生的记忆不可能已挂上，默认 true）。 */
    private Boolean olderThanTag;

    /** 时间窗下界（ISO-8601，可选；与 olderThanTag 叠加取交集）。 */
    private String start;

    /** 时间窗上界（ISO-8601，可选）。 */
    private String end;

    /** 单次扫描上限（缺省/超限一律压到服务端上限 200，防刷 LLM）。 */
    private Integer limit;

    /** true=仅预估扫描条数（不调 LLM 不落库），前端 modal「预估条数」用。 */
    private Boolean dryRun;
}
