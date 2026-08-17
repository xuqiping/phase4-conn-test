package com.superprogrammer.asset.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 资产评分视图（2x第三轮C6）——我的评分 + 双轨聚合（单资产）。
 *
 * <p>双轨：拥有者分独立展示（{@link #ownerScore}，每人每资产一票故唯一）；
 * 成员分参与均分（{@link #memberAvgScore}/{@link #memberCount}，含被移除成员——决策 D4）。
 * 未打分轨位为 null / 0。
 */
@Data
@Builder
public class AssetScoreVO {

    /** 我的评分（未评过为 null）。 */
    private Integer myScore;

    /** 拥有者分（独立轨；未评为 null）。 */
    private Integer ownerScore;

    /** 成员均分（四舍五入取整；无成员分为 null）。 */
    private Integer memberAvgScore;

    /** 参与均分的成员分票数（含被移除成员的历史评分）。 */
    private Integer memberCount;

    /** 拥有者分等级（2x#7，AssetGrade 派生；未评为 null）。 */
    private String ownerGrade;

    /** 成员均分等级（2x#7，先取整再映射；无成员分为 null）。 */
    private String memberAvgGrade;
}
