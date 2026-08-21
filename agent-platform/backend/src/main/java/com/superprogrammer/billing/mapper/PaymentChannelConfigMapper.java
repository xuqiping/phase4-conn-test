package com.superprogrammer.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.billing.entity.PaymentChannelConfigEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 支付渠道配置 mapper（V143）。
 */
@Mapper
public interface PaymentChannelConfigMapper extends BaseMapper<PaymentChannelConfigEntity> {

    /** 按渠道查未软删行（部分唯一索引保证至多一行）。 */
    @Select("SELECT * FROM payment_channel_config WHERE channel = #{channel} AND deleted = 0")
    PaymentChannelConfigEntity selectByChannel(@Param("channel") String channel);
}
