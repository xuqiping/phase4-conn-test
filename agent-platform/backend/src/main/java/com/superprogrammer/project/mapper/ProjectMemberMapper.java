package com.superprogrammer.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.project.entity.ProjectMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectMemberMapper extends BaseMapper<ProjectMember> {

    /** 查成员行（含软删，绕 @TableLogic）——addMember 复活软删行用，
     *  否则 re-add 撞 uk_project_members_project_user（唯一约束含软删行）。 */
    ProjectMember findAnyState(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
