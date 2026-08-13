// agent-platform/backend/src/main/java/com/superprogrammer/common/security/entity/IpBlacklist.java
package com.superprogrammer.common.security.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * IP 封禁（V104 ip_blacklist，11x 加固）。
 * 不继承 BaseEntity：无 deleted/version 列，过期行由定时任务物理 DELETE。
 */
@Data
@TableName("ip_blacklist")
public class IpBlacklist {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归一化后的 IP（InetAddress 标准形态，防多写法绕过）。 */
    private String ip;

    /** AUTO=规则自动封 / MANUAL=admin 手动封。 */
    private String source;

    /** 触发规则码 / 人工填写原因。 */
    private String reason;

    /** 到期自动失效；NULL=永久。 */
    private OffsetDateTime bannedUntil;

    /** 创建人（AUTO 时填规则码）。 */
    private String createdBy;

    private OffsetDateTime createdAt;
}
