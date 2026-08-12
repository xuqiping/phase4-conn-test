package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.dto.MemoryProjectUserGrantVO;
import com.superprogrammer.chat.entity.MemoryProjectUserGrant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 项目↔个人授权 Mapper（记忆二期 P1）。
 * 状态翻转走条件 UPDATE（LambdaUpdateWrapper，service 层）；listInvolving 带项目/人名 join。
 */
@Mapper
public interface MemoryProjectUserGrantMapper extends BaseMapper<MemoryProjectUserGrant> {

    /** 我相关的授权（我是被授权人，或我是项目侧 ACTIVE owner/admin），带项目/人名一次 join 齐。 */
    List<MemoryProjectUserGrantVO> listInvolving(@Param("userId") Long userId);

    /** 被授权个人可召回的 ACTIVE 项目 id 集（召回取数实时算，revoke 即时断召回）。 */
    List<Long> findActiveGrantedProjectIds(@Param("userId") Long userId);
}
