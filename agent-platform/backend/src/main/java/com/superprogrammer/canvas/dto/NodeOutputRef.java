package com.superprogrammer.canvas.dto;

import lombok.Data;

/**
 * 节点产出物引用（产出物契约）。
 *
 * <p>节点生成完成后，产出物落 {@code stored_files}（source={@code SOURCE_CANVAS}），快照里只存
 * 此引用（fileId）——不嵌 base64（plan R-5 防撑爆）。下游节点通过 {@link #fileId} 引用上游产出。
 *
 * <p>联动（plan 功能联动清单）：上游产出完成 → 下游参考图下拉出现该 fileId；上游 FAILED →
 * 下游选项不出现 + 标红。
 */
@Data
public class NodeOutputRef {

    /** 产出该文件的节点 id。 */
    private String nodeId;
    /** 节点类型（决定 outputKind 语义）。 */
    private String nodeType;
    /** → stored_files.file_id（产出物咽喉点，load 时强校验 ownership）。 */
    private String fileId;
    /** 产出物种类：image/video/audio/text。 */
    private String outputKind;
}
