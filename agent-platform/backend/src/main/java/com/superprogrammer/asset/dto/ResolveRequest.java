package com.superprogrammer.asset.dto;

import lombok.Data;

/**
 * 资产引用解析请求（plan §S7 / FR-009）。
 *
 * <p>{@link #version} 缺省=资产当前版本；指定则解析该版本快照（版本隔离，设计方案 §六）。
 *
 * <p>{@link #canvasId}+{@link #nodeId} 同时给定时落 REFERENCE 绑定（双向追溯「被引用」台账），
 * 缺省（如详情页预览解析）不落绑定。
 */
@Data
public class ResolveRequest {

    /** 指定版本号（可空=当前版本）。 */
    private Integer version;
    /** 引用方画布 id（库→画布引用时传，落 REFERENCE 绑定；可空=仅解析不记账）。 */
    private Long canvasId;
    /** 引用方画布节点 id（同 canvasId 配对，可空）。 */
    private String nodeId;
}
