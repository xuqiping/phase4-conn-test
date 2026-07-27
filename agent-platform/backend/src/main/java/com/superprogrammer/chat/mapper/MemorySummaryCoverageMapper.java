package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemorySummaryCoverage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 记忆覆盖表 mapper（V47 计划12）。
 * 召回恒只认 user_id=召回者自己的行（allCovered 判定依据，向量 1）。
 */
@Mapper
public interface MemorySummaryCoverageMapper extends BaseMapper<MemorySummaryCoverage> {

    /** 作者在给定 turn 集上的覆盖行。防 N+1：批量 IN 一次取。 */
    List<MemorySummaryCoverage> findByUserAndTurns(@Param("userId") Long userId,
                                                   @Param("turnIds") List<Long> turnIds);
}
