package com.superprogrammer.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.billing.entity.PaymentOrderEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 充值订单 Mapper（MVP admin grant 直写 PAID；Phase2 支付回调补状态机）。
 */
@Mapper
public interface PaymentOrderMapper extends BaseMapper<PaymentOrderEntity> {
}
