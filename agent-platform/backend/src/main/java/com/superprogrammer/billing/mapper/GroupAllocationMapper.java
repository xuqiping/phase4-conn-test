package com.superprogrammer.billing.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 项目组分配视图 Mapper（D3 · 20x-2，admin 账单页只读聚合）。
 * <p>跨模块只读：行源 project_group_members（projectgroup 域）+ users + project_groups，
 * 聚合源 project_group_ledger 的 MEMBER_* 非资金腿（ref_type='MEMBER'，ref_id=成员 userId）。
 * VO/SQL 归 billing 域（与其余 admin 视图 Mapper 同居），避免 projectgroup→billing 反向依赖。
 * <p>聚合走子查询 GROUP BY 一次算完再 JOIN（行数=成员活行数，无 per-user 循环，spec §坑点 N+1）。
 */
@Mapper
public interface GroupAllocationMapper {

    /**
     * 行总数（keyword 命中 username/name 任一 / groupId 精确；与分页同 WHERE 口径，无 ledger 聚合需求）。
     * 仅统计活行：成员活行 + 组活行（软删组的成员行随组隐藏）。
     */
    @Select("<script>SELECT COUNT(*) FROM project_group_members m "
            + "JOIN users u ON u.id = m.user_id "
            + "JOIN project_groups g ON g.id = m.group_id "
            + "<where> m.deleted = 0 AND g.deleted = 0 AND u.deleted = 0 "
            + "<if test='keyword != null and keyword != \"\"'> AND (u.username LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\' "
            + "OR u.name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\')</if>"
            + "<if test='groupId != null'> AND m.group_id = #{groupId}</if>"
            + "</where></script>")
    long countGroupAllocations(@Param("keyword") String keyword, @Param("groupId") Long groupId);

    /**
     * 分配视图分页行：quota/used/项目内剩余 来自成员行快照；
     * 毛额/收回/净额/最近分配时间 来自 ledger MEMBER_ALLOCATE/MEMBER_RECLAIM 聚合（无流水=0/NULL）。
     * <p>ledger 无软删列（append-only，不继承 BaseEntity）——子查询无 deleted 过滤是有意为之。
     * CAST(ref_id AS BIGINT)：MEMBER 腿 ref_id 恒为成员 userId 字符串（写入侧唯一来源
     * ProjectGroupService#recordMemberQuotaLedger），转型安全。
     * 排序固定 组 id → 用户 id（翻页稳定）。
     */
    @Select("<script>SELECT m.group_id AS groupId, g.name AS groupName, "
            + "m.user_id AS userId, u.username, u.name, m.role, "
            + "m.quota_limit_points AS quotaLimit, m.used_points AS usedPoints, "
            + "CASE WHEN m.quota_limit_points IS NULL THEN NULL "
            + "ELSE m.quota_limit_points - m.used_points END AS remaining, "
            + "COALESCE(a.gross, 0) AS totalAllocated, "
            + "COALESCE(a.reclaimed, 0) AS reclaimed, "
            + "COALESCE(a.gross, 0) - COALESCE(a.reclaimed, 0) AS netAllocated, "
            + "a.lastAt AS lastAllocatedAt "
            + "FROM project_group_members m "
            + "JOIN users u ON u.id = m.user_id "
            + "JOIN project_groups g ON g.id = m.group_id "
            + "LEFT JOIN ("
            + "  SELECT group_id, CAST(ref_id AS BIGINT) AS member_user_id, "
            + "  SUM(CASE WHEN type = 'MEMBER_ALLOCATE' THEN delta_points ELSE 0 END) AS gross, "
            + "  SUM(CASE WHEN type = 'MEMBER_RECLAIM' THEN -delta_points ELSE 0 END) AS reclaimed, "
            + "  MAX(created_at) AS lastAt "
            + "  FROM project_group_ledger "
            + "  WHERE ref_type = 'MEMBER' AND type IN ('MEMBER_ALLOCATE', 'MEMBER_RECLAIM') "
            + "  GROUP BY group_id, CAST(ref_id AS BIGINT)"
            + ") a ON a.group_id = m.group_id AND a.member_user_id = m.user_id "
            + "<where> m.deleted = 0 AND g.deleted = 0 AND u.deleted = 0 "
            + "<if test='keyword != null and keyword != \"\"'> AND (u.username LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\' "
            + "OR u.name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\')</if>"
            + "<if test='groupId != null'> AND m.group_id = #{groupId}</if>"
            + "</where> ORDER BY m.group_id ASC, m.user_id ASC LIMIT #{size} OFFSET #{offset}</script>")
    List<com.superprogrammer.billing.dto.GroupAllocationRowVO> pageGroupAllocations(
            @Param("keyword") String keyword, @Param("groupId") Long groupId,
            @Param("offset") long offset, @Param("size") long size);
}
