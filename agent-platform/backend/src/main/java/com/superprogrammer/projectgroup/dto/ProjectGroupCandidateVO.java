package com.superprogrammer.projectgroup.dto;

/** 候选用户行（复用资产库模式：排除组长/已有成员）。修复III E3：+remark（备注 tag 展示/筛选）。 */
public record ProjectGroupCandidateVO(Long userId, String username, String name, String remark) {
}
