package com.superprogrammer.billing.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** admin 充值记录行（7x#1 六字段 + 用户账号列）。D2（20x-1）：+name（昵称/姓名，可空）。
 *  修复IV E2（12x-1）：+remark（组织备注，可空）。 */
public record AdminRechargeRecordVO(Long id,
                                    Long userId,
                                    String username,
                                    String name,
                                    String remark,
                                    OffsetDateTime createdAt,
                                    String channel,
                                    String payerAccount,
                                    BigDecimal amountYuan,
                                    BigDecimal pointsGranted,
                                    BigDecimal balanceAfter,
                                    String status) {
}
