package com.superprogrammer.projectgroup.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 调整成员限额请求：null=改为不限。 */
@Data
public class ProjectGroupQuotaRequest {
    private BigDecimal quotaLimitPoints;
}
