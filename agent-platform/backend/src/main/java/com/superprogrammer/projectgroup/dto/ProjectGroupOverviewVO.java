package com.superprogrammer.projectgroup.dto;

import com.superprogrammer.common.result.PageResult;

/**
 * 组长总览 VO（计划5 Step7，GET /project-groups/{id}/overview）。
 * 组基本信息+组池+成员表复用 {@link ProjectGroupDetailVO}，另携流水分页。
 */
public record ProjectGroupOverviewVO(
        ProjectGroupDetailVO group,
        PageResult<ProjectGroupLedgerRowVO> ledger) {
}
