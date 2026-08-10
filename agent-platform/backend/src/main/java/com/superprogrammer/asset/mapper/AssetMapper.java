package com.superprogrammer.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.asset.dto.MatrixCountVO;
import com.superprogrammer.asset.dto.ProjectAssetCountVO;
import com.superprogrammer.asset.entity.Asset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 项目资产库·资产 Mapper。
 *
 * <p>纯 BaseMapper 满足 CRUD；额外 {@link #countMatrixByRole} 单条 GROUP BY 聚合
 * 支撑矩阵每格计数徽章（plan 坑点预判：每格一次 count = 几十次查询 → 单条聚合返全图）。
 */
@Mapper
public interface AssetMapper extends BaseMapper<Asset> {

    /** 复制当前版本时锁住源资产行，防止并发建版改变 current_version。 */
    @Select("SELECT id FROM assets WHERE id = #{assetId} AND deleted = 0 FOR UPDATE")
    Long lockByIdForUpdate(@Param("assetId") Long assetId);

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

    /**
     * 项目内某 media_type 的资产数（V60 §C1b：删 type 时判迁移数 / 阻删依据）。
     */
    @Select("SELECT COUNT(*) FROM assets WHERE project_id = #{projectId} AND media_type = #{mediaType} AND deleted = 0")
    long countByMediaType(@Param("projectId") Long projectId, @Param("mediaType") String mediaType);

    /** 公众池项目资产数一次 GROUP BY 批查，避免项目列表 N+1。 */
    @Select({"<script>",
            "SELECT project_id AS projectId, COUNT(*) AS assetCount FROM assets ",
            "WHERE deleted = 0 AND project_id IN ",
            "<foreach collection='projectIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> ",
            "GROUP BY project_id",
            "</script>"})
    List<ProjectAssetCountVO> countByProjectIds(@Param("projectIds") List<Long> projectIds);

    /**
     * 乐观锁并发建版（plan §S5 坑点预判：两人同时提交版本号撞车）。
     * 当前版本号匹配才 +1，受影响行数=0 即并发冲突。
     * 原生 SQL 绕过 MP 行版本锁（current_version 是域版本号，与 BaseEntity.version 不同）。
     *
     * @return 受影响行数（1=成功，0=版本号已被他人改过→冲突）
     */
    @Update("UPDATE assets SET current_version = current_version + 1, "
            + "updated_at = NOW(), updated_by = #{userId} "
            + "WHERE id = #{assetId} AND current_version = #{expected} AND deleted = 0")
    int bumpVersionOptimistic(@Param("assetId") Long assetId,
                              @Param("expected") int expected,
                              @Param("userId") Long userId);

    /**
     * 写当前正文（文本类建版/一致性包保存时同步 assets.content=最新版本正文）。
     * 原生 SQL 避免与乐观锁行版本纠缠。
     */
    @Update("UPDATE assets SET content = CAST(#{content} AS jsonb), "
            + "updated_at = NOW(), updated_by = #{userId} "
            + "WHERE id = #{assetId} AND deleted = 0")
    int updateContent(@Param("assetId") Long assetId,
                      @Param("content") String content,
                      @Param("userId") Long userId);

    /**
     * 写生成谱系（画布产出入库时同步 assets.gen_meta=最新产出谱系，plan §S7）。
     * 原生 SQL 避免与乐观锁行版本纠缠（同 {@link #updateContent}）。
     */
    @Update("UPDATE assets SET gen_meta = CAST(#{genMeta} AS jsonb), "
            + "updated_at = NOW(), updated_by = #{userId} "
            + "WHERE id = #{assetId} AND deleted = 0")
    int updateGenMeta(@Param("assetId") Long assetId,
                      @Param("genMeta") String genMeta,
                      @Param("userId") Long userId);

    /**
     * 媒体类型迁移（V60 §C1b L10'）：删某 media_type 时，该 type 下资产批量改挂到同 category 的另一 type。
     * 原生 SQL 批量 UPDATE（避免逐行 + 触发审计列）。受影响行数=迁移资产数。
     */
    @Update("UPDATE assets SET media_type = #{newType}, updated_at = NOW(), updated_by = #{userId} "
            + "WHERE project_id = #{projectId} AND media_type = #{oldType} AND deleted = 0")
    int reassignMediaType(@Param("projectId") Long projectId,
                          @Param("oldType") String oldType,
                          @Param("newType") String newType,
                          @Param("userId") Long userId);
}
