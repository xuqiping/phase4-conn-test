package com.superprogrammer.projectgroup.dto;

/** 候选用户行（复用资产库模式：排除组长/已有成员）。 */
public record ProjectGroupCandidateVO(Long userId, String username, String name) {
}
