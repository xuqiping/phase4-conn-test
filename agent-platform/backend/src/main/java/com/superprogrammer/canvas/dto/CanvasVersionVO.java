package com.superprogrammer.canvas.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 版本 VO：列表带摘要（无 snapshot 重字段），详情/恢复才带 snapshot（withSnapshot 控制）。
 */
@Data
@Builder
public class CanvasVersionVO {

    private Long id;

    /** 归属画布 id。 */
    private Long canvasId;

    /** 版本名。 */
    private String label;

    /** 节点数摘要。 */
    private Integer nodeCount;

    /** 快照 JSON（仅详情/恢复返回；列表省略）。 */
    private String snapshot;

    private OffsetDateTime createdAt;
}
