package com.superprogrammer.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.asset.entity.AssetScore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 资产评分 mapper（asset_scores，V124 · 2x第三轮C5/C6）。
 *
 * <p>upsert 走 ON CONFLICT (asset_id, scorer_user_id)：每人每资产一票，改分覆盖；
 * 软删行复活（deleted 置回 0）——唯一索引是普通索引（非 partial），软删行占位防撞键。
 */
@Mapper
public interface AssetScoreMapper extends BaseMapper<AssetScore> {

    /**
     * 打分/改分（upsert）：不存在则插入；存在（含软删行）则覆盖分数并复活。
     * 冲突仲裁目标 = uk_asset_scores_asset_scorer(asset_id, scorer_user_id)。
     */
    @Insert("""
            INSERT INTO asset_scores
                (asset_id, project_id, scorer_user_id, score, is_owner_score,
                 created_by, created_at, updated_by, updated_at, deleted, version)
            VALUES (#{assetId}, #{projectId}, #{scorerUserId}, #{score}, #{isOwnerScore},
                    #{scorerUserId}, NOW(), #{scorerUserId}, NOW(), 0, 0)
            ON CONFLICT (asset_id, scorer_user_id) DO UPDATE
            SET score          = EXCLUDED.score,
                is_owner_score = EXCLUDED.is_owner_score,
                deleted        = 0,
                updated_by     = EXCLUDED.updated_by,
                updated_at     = NOW(),
                version        = asset_scores.version + 1
            """)
    int upsertScore(@Param("assetId") Long assetId,
                    @Param("projectId") Long projectId,
                    @Param("scorerUserId") Long scorerUserId,
                    @Param("score") Integer score,
                    @Param("isOwnerScore") Boolean isOwnerScore);

    /**
     * 按项目聚合每资产双轨分（C6 列表批量装配用，一次 GROUP BY 免逐资产查询）：
     * member_avg_score/member_count = 被授权者均分与人数（is_owner_score=FALSE，含被移除成员——D4）；
     * owner_score = 拥有者分（每人每资产一票故 MAX 即唯一值；未打分为 NULL）。
     */
    @Select("""
            SELECT asset_id                                            AS "assetId",
                   AVG(score) FILTER (WHERE is_owner_score = FALSE)   AS "memberAvgScore",
                   COUNT(*)   FILTER (WHERE is_owner_score = FALSE)   AS "memberCount",
                   MAX(score) FILTER (WHERE is_owner_score = TRUE)    AS "ownerScore"
            FROM asset_scores
            WHERE project_id = #{projectId} AND deleted = 0
            GROUP BY asset_id
            """)
    List<Map<String, Object>> selectAggregatesByProject(@Param("projectId") Long projectId);

    /** 我对项目内各资产的当前评分（assetId → score，C6 列表 myScore 装配用）。 */
    @Select("""
            SELECT asset_id AS "assetId", score AS "score"
            FROM asset_scores
            WHERE project_id = #{projectId} AND scorer_user_id = #{userId} AND deleted = 0
            """)
    List<Map<String, Object>> selectMyScores(@Param("projectId") Long projectId,
                                             @Param("userId") Long userId);
}
