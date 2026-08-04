package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.dto.MemoryRecallAclVO;
import com.superprogrammer.chat.entity.MemoryRecallAcl;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 项目记忆读取授权 mapper（V49 计划12·迭代 I1 读侧 + I2 写侧/矩阵）。
 * <p>
 * 读侧（I1）：{@link #findGrantedTargetIds} 取 reader 在项目内的全部 target 作者集（resolver 用）。
 * 写侧（I2）：{@link #deleteByProjectAndReader} 全量替换先删 + {@link BaseMapper#insert} 插新（{@code created_by} 审计）。
 * 矩阵（I2）：{@link #findGrantedDetails} 带 username 的 VO 查询（GET /recall-acl 返）。
 * <p>
 * 强制 scope：所有查询内联 {@code project_id}（向量 14），无全表扫入口。
 *
 * @see com.superprogrammer.chat.service.internal.MemoryRecallAclResolver
 */
@Mapper
public interface MemoryRecallAclMapper extends BaseMapper<MemoryRecallAcl> {

    /**
     * 取 reader 在 projectId 内被授权可读的全部【作者】user_id（向量 14 scope 强制）。
     * <p>
     * 含 DEPARTED 曾赋权的 target（保交接）；是否纳入召回由 L10 离职开关在 I3 接入时过滤，本查询不滤。
     * 命中 {@code idx_memory_recall_acl_project_reader} 索引。
     */
    @Select("SELECT target_user_id FROM memory_recall_acl " +
            "WHERE project_id = #{projectId} AND reader_user_id = #{readerUserId}")
    List<Long> findGrantedTargetIds(@Param("projectId") Long projectId,
                                    @Param("readerUserId") Long readerUserId);

    /**
     * 删 reader 在 projectId 的全部授权行（I2 全量替换先删步，向量 14 scope 强制）。
     * <p>
     * 撤销 = DELETE 行（表无 deleted 列，同 members/coverage 风格）。返实删条数。
     */
    @Delete("DELETE FROM memory_recall_acl " +
            "WHERE project_id = #{projectId} AND reader_user_id = #{readerUserId}")
    int deleteByProjectAndReader(@Param("projectId") Long projectId,
                                 @Param("readerUserId") Long readerUserId);

    /**
     * 项目内全部授权矩阵行（带 reader/target username，GET /recall-acl 返）。
     * <p>
     * join users 两次取 reader/target 显示名；{@code name} 可空（前端回退 username）。
     * 仅 owner / recall_admin 可见（controller 判 403）。
     */
    @Select("SELECT a.reader_user_id AS readerUserId, ur.username AS readerUsername, ur.name AS readerName, " +
            "       a.target_user_id AS targetUserId, ut.username AS targetUsername, ut.name AS targetName, " +
            "       a.created_by AS createdBy, a.created_at AS createdAt " +
            "FROM memory_recall_acl a " +
            "LEFT JOIN users ur ON ur.id = a.reader_user_id " +
            "LEFT JOIN users ut ON ut.id = a.target_user_id " +
            "WHERE a.project_id = #{projectId} " +
            "ORDER BY a.reader_user_id, a.target_user_id")
    List<MemoryRecallAclVO> findGrantedDetails(@Param("projectId") Long projectId);
}
