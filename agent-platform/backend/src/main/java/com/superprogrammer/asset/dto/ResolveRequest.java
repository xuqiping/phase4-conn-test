package com.superprogrammer.asset.dto;

import lombok.Data;

/**
 * 资产引用解析请求（plan §S7 / FR-009）。
 *
 * <p>{@link #version} 缺省=资产当前版本；指定则解析该版本快照（版本隔离，设计方案 §六）。
 */
@Data
public class ResolveRequest {

    /** 指定版本号（可空=当前版本）。 */
    private Integer version;
}
