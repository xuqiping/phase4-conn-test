package com.superprogrammer.canvas.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 无限画布快照（canvases，V55）。
 *
 * <p>LibTV 式创作页：一张画布的结构（节点/连线/位置）整存整取进 {@link #snapshot} JSONB。
 * 图/视频/音频等**产出物**走 {@code stored_files}（source={@code SOURCE_CANVAS}），快照只存 fileId 引用，
 * 避免 JSONB 撑爆、支持增量（plan R-5）。
 *
 * <p>ownership：{@link #userId} 硬过滤（用户只能编/删自己的画布），与 {@code createdBy} 解耦便于未来转移归属。
 * 软删（{@code deleted}）不级联清产出物（历史/复用，plan 联动清单）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "canvases", autoResultMap = true)
public class Canvas extends BaseEntity {

    /** 画布归属用户（ownership 硬过滤列）。 */
    private Long userId;

    /** 画布名（用户可重命名）。 */
    private String name;

    /**
     * 画布结构 JSON：{@code {nodes:[...], edges:[...], viewport?}}。
     * 用 {@link JsonbStringTypeHandler} 做 String↔jsonb 转换（同 ChatMessage.metadata / MediaGenTask.requestConfig）。
     * 产出物只存 fileId 引用，不嵌 base64（防撑爆）。
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String snapshot;
}
