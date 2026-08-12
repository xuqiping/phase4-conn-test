// agent-platform/backend/src/main/java/com/superprogrammer/auth/entity/UserCredential.java
package com.superprogrammer.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 用户凭证（user_credential）：一个用户可持多条登录凭证，每条对应一种登录方式。
 *
 * <p>生活化比喻：{@code User} 是「人」，本实体是「这人手里的几把钥匙」——
 * 密码、邮箱、手机、微信、钉钉各是一把。有任意一把且 {@code verified=TRUE} 的钥匙就能开门（登录）。
 * 找回密码、绑定/解绑、凭证验证状态都精确到「某一把钥匙」，而不是整个账号。
 *
 * <p>{@code identifier} 语义随 {@code credentialType} 变化：
 * <ul>
 *   <li>PASSWORD → username（secret 存 BCrypt 哈希）</li>
 *   <li>EMAIL    → 邮箱地址（secret 为 null；<b>未验证不可用于找回密码</b>）</li>
 *   <li>PHONE    → 手机号（secret 为 null）</li>
 *   <li>WECHAT   → unionid（优先，无则 openid）</li>
 *   <li>DINGTALK → unionid</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_credential")
public class UserCredential extends BaseEntity {

    /** 凭证类型：账密（identifier=username，secret=BCrypt 哈希）。 */
    public static final String TYPE_PASSWORD = "PASSWORD";
    /** 凭证类型：邮箱（identifier=邮箱地址）。 */
    public static final String TYPE_EMAIL = "EMAIL";
    /** 凭证类型：手机号（identifier=手机号）。 */
    public static final String TYPE_PHONE = "PHONE";
    /** 凭证类型：微信（identifier=unionid 优先，无则 openid）。 */
    public static final String TYPE_WECHAT = "WECHAT";
    /** 凭证类型：钉钉（identifier=unionid）。 */
    public static final String TYPE_DINGTALK = "DINGTALK";

    /** 归属用户 users.id。 */
    private Long userId;

    /** 凭证类型（PASSWORD/EMAIL/PHONE/WECHAT/DINGTALK）。 */
    private String credentialType;

    /** 该凭证的唯一标识（语义随 credentialType 变化，见类注释）。 */
    private String identifier;

    /** 密钥材料：仅 PASSWORD 存 BCrypt 哈希，其余类型为 null。 */
    private String secret;

    /** 是否已验证真实性（未验证邮箱不可用于找回密码）。 */
    private Boolean verified;

    /** 首次验证通过时间；未验证为 null。 */
    private OffsetDateTime verifiedAt;
}
