package com.superprogrammer.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资产评分（asset_scores，V124 · 2x第三轮C5）。
 *
 * <p>百分制双轨：OWNER 的分（{@link #isOwnerScore}=true）单值独立展示；
 * 被授权成员的分参与均分。每人每资产一票（uk_asset_scores_asset_scorer 唯一），
 * 改分走 upsert（ON CONFLICT 复活软删行），不产生第二行。
 *
 * <p>被移除成员的历史评分保留并参与均分（决策 D4：均分反映「所有评过的人」的口味，
 * 移除成员 ≠ 撤回观点；行不物理删，软删仅用于撤销评分场景）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "asset_scores", autoResultMap = true)
public class AssetScore extends BaseEntity {

    /** 所属资产 id（assets.id）。 */
    private Long assetId;

    /** 冗余项目 id（= assets.project_id，按项目批量聚合均分免回表）。 */
    private Long projectId;

    /** 打分人（项目成员或 OWNER）。 */
    private Long scorerUserId;

    /** 百分制 0-100（ck_asset_score_range 兜底）。 */
    private Integer score;

    /** TRUE=拥有者分（独立展示）；FALSE=被授权者分（参与均分）。 */
    private Boolean isOwnerScore;
}
