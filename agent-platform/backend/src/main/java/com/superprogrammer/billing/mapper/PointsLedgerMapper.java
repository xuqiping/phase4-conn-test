package com.superprogrammer.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.billing.entity.PointsLedgerEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 积分流水 Mapper（append-only，写走 BaseMapper.insert；查询在 service 用 LambdaQueryWrapper）。
 */
@Mapper
public interface PointsLedgerMapper extends BaseMapper<PointsLedgerEntity> {
}
