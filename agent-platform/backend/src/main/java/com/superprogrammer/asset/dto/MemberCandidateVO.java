package com.superprogrammer.asset.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 资产项目邀请候选的最小公开身份。 */
@Data
@AllArgsConstructor
public class MemberCandidateVO {
    private Long id;
    private String username;
}
