package com.superprogrammer.asset.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 成员视图（含 owner 行）。
 *
 * <p>列表首行固定为 owner（{@link #isOwner}=true），便于分享弹窗区分所有者与成员。
 * userId 与 username 由资产域一次返回，分享弹窗不再依赖管理员用户查询。
 */
@Data
@Builder
public class MemberVO {

    private Long userId;
    private String username;
    /** 项目角色：OWNER/EDITOR/VIEWER（owner 行由本服务合成，不在成员表）。 */
    private String role;
    /** 是否所有者（owner 不落成员表，列表合成）。 */
    private boolean isOwner;
    private Long grantedBy;
    private OffsetDateTime grantedAt;
}
