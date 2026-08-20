package com.superprogrammer.canvas.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.JsonbStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 画布版本快照（canvas_versions，V135，2x 五轮「版本保存」）。
 *
 * <p>用户手动「存版本」的快照点：跨会话、可命名、可恢复——区别于前端撤销栈（会话内 50 步）。
 * snapshot 形状同 {@link Canvas#getSnapshot()}（{nodes,edges,...}，只存 fileId 引用不嵌 base64）。
 *
 * <p>ownership：经画布 {@code CanvasService.loadOwned} 咽喉点间接校验（版本必挂在归属画布下），
 * 本表不再单独存 user_id 过滤列。每画布保留最近 30 个（service 插入后修剪）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "canvas_versions", autoResultMap = true)
public class CanvasVersion extends BaseEntity {

    /** 归属画布 id（ownership 经画布校验）。 */
    private Long canvasId;

    /** 版本名（用户命名；空则服务端补「版本 yyyy-MM-dd HH:mm」）。 */
    private String label;

    /** 画布结构 JSON（同 canvases.snapshot 形状）。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String snapshot;

    /** 冗余摘要：节点数（列表免解析 JSONB）。 */
    private Integer nodeCount;
}
