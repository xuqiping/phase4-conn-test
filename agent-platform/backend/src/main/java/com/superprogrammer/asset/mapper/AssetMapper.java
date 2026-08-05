package com.superprogrammer.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.asset.dto.MatrixCountVO;
import com.superprogrammer.asset.entity.Asset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 项目资产库·资产 Mapper。
 *
 * <p>纯 BaseMapper 满足 CRUD；额外 {@link #countMatrixByRole} 单条 GROUP BY 聚合
 * 支撑矩阵每格计数徽章（plan 坑点预判：每格一次 count = 几十次查询 → 单条聚合返全图）。
 */
@Mapper
public interface AssetMapper extends BaseMapper<Asset> {

    /**
     * 矩阵每格计数：按 (media_type, role_key) 聚合（单条 SQL，防 N+1）。
     * LEFT JOIN 保留未挂角色的资产（roleKey=null）；默认排除 ARCHIVED。
     */
    @Select("SELECT a.media_type AS mediaType, r.role_key AS roleKey, COUNT(*) AS count "
            + "FROM assets a LEFT JOIN asset_role_links r ON r.asset_id = a.id "
            + "WHERE a.project_id = #{projectId} AND a.deleted = 0 AND a.status <> 'ARCHIVED' "
            + "GROUP BY a.media_type, r.role_key")
    List<MatrixCountVO.Cell> countMatrixByRole(@Param("projectId") Long projectId);

    /**
     * 每个内容类型总数（顶 Tab 徽标，排除 ARCHIVED）。
     */
    @Select("SELECT media_type AS mediaType, NULL AS roleKey, COUNT(*) AS count "
            + "FROM assets WHERE project_id = #{projectId} AND deleted = 0 AND status <> 'ARCHIVED' "
            + "GROUP BY media_type")
    List<MatrixCountVO.Cell> countByType(@Param("projectId") Long projectId);
}
