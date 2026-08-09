package com.superprogrammer.chat.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 计划12 B：owner 改 label / 补 aliases 请求体（L12 边界：误并不可逆，仅 owner 可改，tag_id 不变）。
 * <p>
 * 两字段皆可选：填 label → 改对外展示名（anchor 随之重生属预期纠错）；
 * 填 addAliases → 把新别名滚进 aliases 集（去重，不覆盖既有）。
 * 至少填一项，否则 BAD_REQUEST。
 * 无 merge/split/重抽 字段——误并不可逆，保护已生成 summary 的 tag_id 不漂移。
 */
@Data
public class MemoryTagEditRequest {

    /** 新对外展示名（owner 改 label）。null = 不改。 */
    @Size(max = 64, message = "标签名长度不能超过 64")
    private String label;

    /** 追加进 aliases 的同义别名（去重）。null/空 = 不补。单条 ≤64。 */
    @Size(max = 20, message = "单次补别名不能超过 20 条")
    private List<@Size(max = 64, message = "单条别名长度不能超过 64") String> addAliases;

    /** V77：接受为新大类（清 needs_review + 消解 TAG_NEEDS_REVIEW 通知）。true = 接受裁决。
     *  label/addAliases 任一改动也会顺带清 needs_review；此字段供「不改名直接接受」场景。 */
    private Boolean accept;
}
