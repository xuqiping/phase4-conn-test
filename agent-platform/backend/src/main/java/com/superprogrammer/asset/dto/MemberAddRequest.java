package com.superprogrammer.asset.dto;

import lombok.Data;

/**
 * 成员邀请请求（FR-002）。
 * userId 必填（被邀请用户）；role=VIEWER/EDITOR（owner 不通过此入口）。
 */
@Data
public class MemberAddRequest {

    /** 被邀请用户 id。 */
    private Long userId;

    /** 项目角色：VIEWER/EDITOR。 */
    private String role;
}
