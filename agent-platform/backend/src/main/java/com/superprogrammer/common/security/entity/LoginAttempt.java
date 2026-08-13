// agent-platform/backend/src/main/java/com/superprogrammer/common/security/entity/LoginAttempt.java
package com.superprogrammer.common.security.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 登录尝试取证（V104 login_attempts，11x 加固）。
 * 不继承 BaseEntity：30 天滚动物理删，检测计数走 Redis（本表只取证 + 异地检测数据源）。
 */
@Data
@TableName("login_attempts")
public class LoginAttempt {

    @TableId(type = IdType.AUTO)
    private Long id;

    private OffsetDateTime createdAt;

    /** 试登的用户名/邮箱（小写归一）。 */
    private String identifier;

    /** 命中用户（user_not_found 时空）。 */
    private Long userId;

    private String clientIp;

    private Boolean success;

    /** bad_password/no_such_user/locked。 */
    private String failReason;

    /** ip2region 归属地（异地检测用；库缺失/内网 IP 时为空）。 */
    private String geo;
}
