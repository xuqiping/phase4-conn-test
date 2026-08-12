package com.superprogrammer.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 二期 P3a：用户主动新建标签请求体（主动建；与写时归一被动建并行，被动路径不变）。
 * <p>
 * 用户在标签库手动新增一个标签：选定大类 topic（大类词表）+ 自填对外名 label（+可选别名）。
 * 写入时 {@code needs_review=false}（用户显式选定大类 = 已裁决）。
 * <p>
 * <b>归一兜底</b>：若该 (user, subject, topic) 已有标签（UNIQUE），则把 label 滚进既有标签的 aliases
 * 并复用之——与写时归一路径①同语义，不破坏「同 user 同 (subject,topic) 唯一」铁律。
 * 仍无 merge/split/re-extract 语义（{@link MemoryTagEditRequest} 同边界）。
 */
@Data
public class MemoryTagCreateRequest {

    /** L0 主体（默认「我」）；留空 → 后端补「我」。 */
    @Size(max = 32, message = "主体长度不能超过 32")
    private String subject;

    /** L0 大类主题（必须从大类词表选一个，如「旅行出行」「财务理财」）。 */
    @NotBlank(message = "大类 topic 不能为空")
    @Size(max = 32, message = "大类长度不能超过 32")
    private String topic;

    /** 对外展示名（规范名，后续同类内容命中即落此标签）。 */
    @NotBlank(message = "标签名 label 不能为空")
    @Size(max = 64, message = "标签名长度不能超过 64")
    private String label;

    /** 同义别名（可选，写入即滚进 aliases 集；归一/召回用）。单条 ≤64，≤20 条。 */
    @Size(max = 20, message = "别名不能超过 20 条")
    private List<@Size(max = 64, message = "单条别名长度不能超过 64") String> aliases;
}
