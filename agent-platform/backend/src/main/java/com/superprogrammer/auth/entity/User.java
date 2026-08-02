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

    private OffsetDateTime lastLoginAt;

    /** 登录方式：password=账密，dingtalk=钉钉免登 */
    private String bindType;

    /** 钉钉 unionId（跨应用稳定标识，账密用户为 null） */
    private String dingtalkUnionId;

    /** 钉钉 openId（应用内标识） */
    private String dingtalkOpenId;
}
