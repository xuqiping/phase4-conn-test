package com.superprogrammer.asset.dto;

import lombok.Data;

/** 将可读源资产的当前版本复制到一个可写目标项目。 */
@Data
public class AssetCopyRequest {
    private Long targetProjectId;
}
