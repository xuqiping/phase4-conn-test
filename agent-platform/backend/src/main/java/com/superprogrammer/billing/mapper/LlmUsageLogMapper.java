package com.superprogrammer.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * LLM 调用审计日志 Mapper。
 * <p>异步攒批写走 {@link #batchInsert}（XML foreach）；聚合查询见 BillingQueryService（Chunk I）。
 */
@Mapper
public interface LlmUsageLogMapper extends BaseMapper<LlmUsageLogEntity> {

    /**
     * 批量插入 usage 日志（UsageWriter 攒批 flush 调）。
     * <p>XML 实现见 resources/com/superprogrammer/billing/mapper/xml/LlmUsageLogMapper.xml。
     */
    void batchInsert(@Param("rows") List<LlmUsageLogEntity> rows);
}
