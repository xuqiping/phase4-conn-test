package com.superprogrammer.asset.enums;

/**
 * 项目资产库·项目角色（设计方案 §七 7.2 角色矩阵）。
 *
 * <p>能力矩阵：
 * <ul>
 *   <li>{@link #OWNER} 所有者：全权（含成员管理/转让/删项目）</li>
 *   <li>{@link #EDITOR} 编辑者：上传/编辑/入库/删资产、定稿/归档、维护词汇、浏览/引用</li>
 *   <li>{@link #VIEWER} 查看者：浏览/搜索/下载/只读引用</li>
 * </ul>
 * admin 平台旁路 = {@link #OWNER} 级（全权），但不在本枚举体现（AclService 用 admin 布尔旁路）。
 */
public enum AssetRole {

    OWNER,
    EDITOR,
    VIEWER;

    /** 可写（上传/编辑/入库/删/定稿/归档/维护词汇）：OWNER 或 EDITOR。 */
    public boolean canWrite() {
        return this == OWNER || this == EDITOR;
    }

    /** 可管理项目（成员管理/转让/删项目/维护词汇）：仅 OWNER。 */
    public boolean canManage() {
        return this == OWNER;
    }

    /** 从 asset_project_members.role 字符串解析（owner 不落成员表，由 AclService 单独判定）。 */
    public static AssetRole fromMemberRole(String role) {
        if (role == null) {
            return VIEWER;
        }
        return switch (role) {
            case "EDITOR" -> EDITOR;
            case "VIEWER" -> VIEWER;
            default -> VIEWER;
        };
    }
}
