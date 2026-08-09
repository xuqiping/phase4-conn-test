package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.dto.MemoryProjectLinkVO;
import com.superprogrammer.chat.entity.MemoryProjectLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 项目授权链 Mapper（记忆二期 P2 · FR-101）。
 * 状态翻转走条件 UPDATE（LambdaUpdateWrapper，service 层）；listInvolving 带双方项目名 join。
 */
@Mapper
public interface MemoryProjectLinkMapper extends BaseMapper<MemoryProjectLink> {

    /** 我相关的授权链（我是任一侧 ACTIVE owner/admin），带项目名/发起人名。 */
    List<MemoryProjectLinkVO> listInvolving(@Param("userId") Long userId);

    /** 一批 parent 项目的 ACTIVE child 项目 id 集（召回合流用，单级一跳）。 */
    List<Long> findActiveChildIds(@Param("parentIds") List<Long> parentIds);
}
