package com.superprogrammer.asset.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 一键分镜结果（plan §S19）。
 *
 * <p>返回新建分镜资产数 + 资产 id 列表（供前端 reload 显新卡）+ 实际模型 + 剧本新版本号。
 */
@Data
@Builder
public class StoryboardBreakdownVO {

    /** 新建分镜资产数。 */
    private Integer count;

    /** 新建分镜资产 id 列表（审计/前端定位）。 */
    private List<Long> createdAssetIds;

    /** 实际使用的模型。 */
    private String model;

    /** 剧本本次产出的新版本号（meta：storyboardModel/storyboardAt，不存 shots）。 */
    private Integer version;
}
