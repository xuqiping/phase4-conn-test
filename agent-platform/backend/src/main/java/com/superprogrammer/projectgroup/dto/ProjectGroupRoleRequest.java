package com.superprogrammer.projectgroup.dto;

import lombok.Data;

/** 任免组内角色请求（17x#2，V139）：role=MANAGER/MEMBER（OWNER 不可任免）。 */
@Data
public class ProjectGroupRoleRequest {

    /** 目标角色：MANAGER / MEMBER。 */
    private String role;
}
