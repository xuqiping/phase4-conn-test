package com.superprogrammer.asset.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 公众池批量项目资产计数查询结果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectAssetCountVO {
    private Long projectId;
    private Long assetCount;
}
