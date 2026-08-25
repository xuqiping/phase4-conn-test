// agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/User.java
package com.superprogrammer.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("users")
public class User extends BaseEntity {

    private String username;

    /** 显示名/真实姓名（钉钉 nick 或手动填写），可空；为空时前端回退 username */
    private String name;

    private String password;

    private String email;

    private String avatar;

    private String status;

    /** 封禁/禁用/锁定原因（11x 加固 V104，status=BANNED/DISABLED/LOCKED 时由管理员填写，ACTIVE 时置空） */
    private String banReason;

    /** 自动锁定到期时间（11x 加固 V104，P3 冷规则锁账号用；null=永久/非自动锁） */
    private OffsetDateTime lockedUntil;

    private OffsetDateTime lastLoginAt;

    /** 登录方式：password=账密，dingtalk=钉钉免登 */
    private String bindType;

    /** 钉钉 unionId（跨应用稳定标识，账密用户为 null） */
    private String dingtalkUnionId;

    /** 钉钉 openId（应用内标识） */
    private String dingtalkOpenId;

    /** 手机号（手机验证码注册/绑定时填），可空 */
    private String phone;

    /** 微信开放平台 unionId（跨应用稳定标识，账密用户为 null） */
    private String wechatUnionid;

    /** 微信开放平台 openId（应用内标识） */
    private String wechatOpenid;

    /** 账号备注（≤128 字，V157/D1）：注册/个人资料/管理员三处可维护，管理列表 keyword 可筛 */
    private String remark;
}
