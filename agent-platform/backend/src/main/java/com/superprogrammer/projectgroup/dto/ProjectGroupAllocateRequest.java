package com.superprogrammer.projectgroup.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 划拨/回收请求：points 必填 >0；remark 可空。 */
@Data
public class ProjectGroupAllocateRequest {
    private BigDecimal points;
    private String remark;
}
