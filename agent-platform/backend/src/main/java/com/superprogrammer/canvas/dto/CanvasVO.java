package com.superprogrammer.canvas.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 画布视图（返回前端）。
 *
 * <p>列表接口省略 {@link #snapshot}（重字段），仅返回摘要；详情接口才带 snapshot。
 * {@link #nodeCount} 从 snapshot.nodes 数组长度派生，供列表展示规模感。
 */
@Data
@Builder
public class CanvasVO {

    private Long id;
    private String name;
    /** 画布结构 JSON（列表接口为 null，详情接口才填）。 */
    private String snapshot;
    /** 节点数（从 snapshot.nodes 派生，解析失败为 0）。 */
    private Integer nodeCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
