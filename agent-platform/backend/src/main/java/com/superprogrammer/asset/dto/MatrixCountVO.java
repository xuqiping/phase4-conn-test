package com.superprogrammer.asset.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 矩阵计数（设计方案 §2.2 每格计数徽章）。
 *
 * <p>{@link #cells} = 每个 (mediaType × roleKey) 格的计数（单条 GROUP BY 聚合，plan 坑点预判）。
 * {@link #typeTotals} = 每个内容类型总数（顶 Tab 徽标）。
 * roleKey=null 表示「未挂角色」的资产计数。
 * 默认排除 ARCHIVED（归档资产不进默认列表，L3）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MatrixCountVO {

    private List<Cell> cells;
    private List<Cell> typeTotals;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Cell {
        private String mediaType;
        private String roleKey;
        private Long count;
    }
}
