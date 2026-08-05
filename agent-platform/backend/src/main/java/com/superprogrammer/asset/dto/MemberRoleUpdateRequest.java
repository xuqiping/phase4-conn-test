package com.superprogrammer.asset.dto;

import lombok.Data;

/** 成员改角色请求（FR-002）。role=VIEWER/EDITOR。 */
@Data
public class MemberRoleUpdateRequest {

    private String role;
}
