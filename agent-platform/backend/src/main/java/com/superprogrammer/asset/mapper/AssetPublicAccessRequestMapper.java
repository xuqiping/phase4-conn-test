package com.superprogrammer.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.asset.entity.AssetPublicAccessRequest;
import com.superprogrammer.asset.dto.PublicAccessRequestVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Insert;

import java.util.List;

@Mapper
public interface AssetPublicAccessRequestMapper extends BaseMapper<AssetPublicAccessRequest> {

    /** 项目所有者审批列表：只联查展示所需的用户名，不暴露邮箱或凭据字段。 */
    @Select("""
            SELECT r.id,
                   r.project_id,
                   r.applicant_id,
                   u.username AS applicant_username,
                   r.status,
                   r.decided_by,
                   r.decided_at,
                   r.created_at,
                   r.updated_at
            FROM asset_public_access_requests r
            JOIN users u ON u.id = r.applicant_id
            WHERE r.project_id = #{projectId} AND r.deleted = 0
            ORDER BY r.created_at DESC
            """)
    List<PublicAccessRequestVO> selectOwnerViewByProjectId(@Param("projectId") Long projectId);

    @Insert("""
            INSERT INTO asset_public_access_requests
                (project_id, applicant_id, status, created_by, updated_by)
            VALUES (#{projectId}, #{applicantId}, 'PENDING', #{applicantId}, #{applicantId})
            ON CONFLICT (project_id, applicant_id) WHERE deleted = 0 DO NOTHING
            """)
    int insertPendingIfAbsent(@Param("projectId") Long projectId,
                              @Param("applicantId") Long applicantId);

    @Select("""
            SELECT * FROM asset_public_access_requests
            WHERE project_id = #{projectId} AND applicant_id = #{applicantId} AND deleted = 0
            FOR UPDATE
            """)
    AssetPublicAccessRequest selectForUpdate(@Param("projectId") Long projectId,
                                             @Param("applicantId") Long applicantId);

    @Update("""
            UPDATE asset_public_access_requests
            SET status = #{status}, decided_by = #{decidedBy}, decided_at = NOW(),
                updated_by = #{decidedBy}, updated_at = NOW(), version = version + 1
            WHERE id = #{id} AND status = 'PENDING' AND deleted = 0
            """)
    int decidePending(@Param("id") Long id,
                      @Param("status") String status,
                      @Param("decidedBy") Long decidedBy);

    @Update("""
            UPDATE asset_public_access_requests
            SET status = 'PENDING', decided_by = NULL, decided_at = NULL,
                updated_by = #{applicantId}, updated_at = NOW(), version = version + 1
            WHERE id = #{id} AND applicant_id = #{applicantId}
              AND status IN ('REJECTED', 'REVOKED') AND deleted = 0
            """)
    int resetToPending(@Param("id") Long id, @Param("applicantId") Long applicantId);

    @Update("""
            UPDATE asset_public_access_requests
            SET status = 'REVOKED', decided_by = #{decidedBy}, decided_at = NOW(),
                updated_by = #{decidedBy}, updated_at = NOW(), version = version + 1
            WHERE id = #{id} AND status = 'APPROVED' AND deleted = 0
            """)
    int revokeApproved(@Param("id") Long id, @Param("decidedBy") Long decidedBy);
}
