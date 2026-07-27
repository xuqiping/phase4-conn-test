package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryRecallAcl;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 项目记忆读取授权 mapper（V49 计划12·迭代 I1）。
 * <p>
 * 读侧（本迭代）：{@link #findGrantedTargetIds} 取 reader 在项目内的全部 target 作者集。
 * 写侧（配置/撤销）属 I2 花名册端点，不在本迭代——BaseMapper.insert/delete 足以支撑。
 * <p>
 * 强制 scope：所有读查询内联 {@code project_id + reader_user_id}（向量 14），无全表扫入口。
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
}
