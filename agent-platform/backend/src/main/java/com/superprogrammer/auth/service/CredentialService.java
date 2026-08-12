// agent-platform/backend/src/main/java/com/superprogrammer/auth/service/CredentialService.java
package com.superprogrammer.auth.service;

import com.superprogrammer.auth.dto.CredentialVO;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserCredential;
import com.superprogrammer.auth.mapper.UserCredentialMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.security.PasswordPolicy;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 凭证基础 CRUD + 绑定/解绑/改密服务（Chunk A 基础 + Chunk G 业务规则）。
 *
 * <p>职责：登录路径查询、建凭证、标记验证、设置页列表（Chunk A）；
 * 绑定邮箱、解绑（至少留一种）、修改密码（验旧密码 + 踢会话）（Chunk G）。
 *
 * <p>安全语义：
 * <ul>
 *   <li>并发注册/绑定同一凭证 → DB 唯一约束兜底 → 转 CONFLICT 友好话术</li>
 *   <li>设置页 identifier 一律脱敏（手机/邮箱），防前端/日志明文回显</li>
 *   <li>软删（deleted=1）而非物理删——解绑留痕供审计</li>
 *   <li>解绑至少留一种可用凭证（防账号失联：找回密码/登录无可用方式）</li>
 *   <li>改密验旧密码 + PasswordPolicy + 新旧不同 + 踢所有会话（强制重登）</li>
 * </ul>
 *
 * <p>依赖说明：本类不依赖 {@link EmailService}（会形成 EmailService ↔ CredentialService 循环）。
 * 绑定邮箱后的「发激活邮件」副作用由 Controller 层编排（service 只管数据）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialService {

    private final UserCredentialMapper credentialMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;

    /** 邮箱格式正则（大小写不敏感，建凭证时统一转小写归一化）。 */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /**
     * 登录路径：按 (类型 + 标识) 定位唯一可用凭证。无则返回 null（调用方走"未注册/不存在"分支）。
     * 命中 uk_credential_type_identifier 索引，单次查询。
     */
    public UserCredential findForLogin(String credentialType, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        return credentialMapper.findByTypeAndIdentifier(credentialType, identifier);
    }

    /**
     * 建凭证。并发同一 (类型+标识) 注册/绑定 → DB 唯一约束抛 DuplicateKeyException → 转 CONFLICT。
     *
     * @param secret     仅 PASSWORD 类型传 BCrypt 哈希；其余类型传 null
     * @param verified   建时验证状态：密码注册=TRUE（密码即凭证，无需再验证）；
     *                   邮箱注册=FALSE（待激活）；手机/微信收码/授权后=TRUE
     */
    @Transactional
    public UserCredential createCredential(Long userId, String credentialType,
                                           String identifier, String secret, boolean verified) {
        UserCredential c = new UserCredential();
        c.setUserId(userId);
        c.setCredentialType(credentialType);
        c.setIdentifier(identifier);
        c.setSecret(secret);
        c.setVerified(verified);
        c.setVerifiedAt(verified ? OffsetDateTime.now() : null);
        try {
            credentialMapper.insert(c);
        } catch (DuplicateKeyException e) {
            // 并发同凭证注册/绑定：DB 唯一约束兜底，转业务冲突
            throw new BusinessException(ErrorCode.CONFLICT, "该邮箱/手机号/微信已被使用");
        }
        return c;
    }

    /**
     * 标记凭证已验证（邮箱点激活链接 / 手机收码 / 微信授权成功后调用）。
     * verified=FALSE→TRUE + verified_at=now；已验证幂等（重复调用无副作用）。
     */
    @Transactional
    public void markVerified(Long credentialId) {
        UserCredential c = credentialMapper.selectById(credentialId);
        if (c == null || Boolean.TRUE.equals(c.getVerified())) {
            return; // 已验证幂等
        }
        c.setVerified(true);
        c.setVerifiedAt(OffsetDateTime.now());
        credentialMapper.updateById(c);
    }

    /**
     * 按 (类型+标识) 找到该用户的同类凭证并标记验证（激活/绑定时用）。
     * 找不到返回 false（调用方走统一话术，不泄露"凭证不存在"）。
     */
    @Transactional
    public boolean markVerifiedByIdentifier(String credentialType, String identifier) {
        UserCredential c = credentialMapper.findByTypeAndIdentifier(credentialType, identifier);
        if (c == null) {
            return false;
        }
        markVerified(c.getId());
        return true;
    }

    /**
     * 设置页：列出该用户所有可用凭证（identifier 脱敏）。命中 idx_credential_user 索引，单次查询。
     */
    public List<CredentialVO> listByUserId(Long userId) {
        return credentialMapper.findByUserId(userId).stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    /**
     * 内部反查：列出该用户所有凭证的明文实体（不脱敏）。
     * 供 EmailService.verifyEmail 激活时用（激活需明文邮箱做 markVerifiedByIdentifier 匹配）。
     * <b>仅限内部调用</b>，不得暴露给前端/Controller。
     */
    public List<UserCredential> findByUserIdRaw(Long userId) {
        return credentialMapper.findByUserId(userId);
    }

    /** 该用户当前可用的凭证数量（解绑时校验"至少留一种"用）。 */
    public long countAvailable(Long userId) {
        return credentialMapper.findByUserId(userId).size();
    }

    /** 该用户是否已有同类凭证（绑定判重）。 */
    public boolean existsByUserIdAndType(Long userId, String credentialType) {
        Long n = credentialMapper.countByUserIdAndType(userId, credentialType);
        return n != null && n > 0;
    }

    // ==================== Chunk G：绑定 / 解绑 / 改密 ====================

    /**
     * 绑定邮箱：校验格式 + 未被他人绑 → 建 EMAIL 凭证 verified=FALSE。
     *
     * <p>建凭证后 {@code verified=FALSE}——激活邮件由 Controller 层调用 {@link EmailService#sendVerifyEmail}
     * 触发（避免本类与 EmailService 循环依赖）。用户点激活链接后才 {@code verified=TRUE}，
     * 未验证邮箱不可用于找回密码。
     *
     * @throws BusinessException 邮箱格式错(BAD_REQUEST) / 该账号已绑邮箱(CONFLICT) / 被他人绑定(CREDENTIAL_ALREADY_BOUND)
     */
    @Transactional
    public UserCredential bindEmail(Long userId, String email) {
        if (email == null || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式不正确");
        }
        email = email.toLowerCase(); // 归一化：邮箱大小写不敏感
        // 该用户已绑邮箱 → 拒（一人一邮箱）
        if (existsByUserIdAndType(userId, UserCredential.TYPE_EMAIL)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该账号已绑定邮箱");
        }
        // 被他人绑定 → 拒（并发场景由 DB 唯一约束兜底转 CONFLICT）
        if (findForLogin(UserCredential.TYPE_EMAIL, email) != null) {
            throw new BusinessException(ErrorCode.CREDENTIAL_ALREADY_BOUND);
        }
        log.info("绑定邮箱 userId={} email={}", userId, mask(UserCredential.TYPE_EMAIL, email));
        return createCredential(userId, UserCredential.TYPE_EMAIL, email, null, false);
    }

    /**
     * 解绑凭证：至少留一种可用凭证（防账号失联），PASSWORD 不可解绑（密码是账号根基，用改密而非解绑）。
     * 软删（{@code @TableLogic} 自动置 deleted=1），保留审计痕迹。
     *
     * @throws BusinessException PASSWORD 不可解绑(BAD_REQUEST) / 仅剩一种(CREDENTIAL_LAST_ONE) / 未找到(NOT_FOUND)
     */
    @Transactional
    public void unbind(Long userId, String credentialType) {
        if (UserCredential.TYPE_PASSWORD.equals(credentialType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码凭证不可解绑，请使用修改密码功能");
        }
        // 至少留一种可用凭证（解绑后归零将导致账号无法登录/找回）
        if (countAvailable(userId) <= 1) {
            throw new BusinessException(ErrorCode.CREDENTIAL_LAST_ONE);
        }
        UserCredential target = credentialMapper.findByUserId(userId).stream()
                .filter(c -> credentialType.equals(c.getCredentialType()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该类型凭证"));
        credentialMapper.deleteById(target.getId()); // 逻辑删（@TableLogic → deleted=1）
        log.info("解绑凭证 userId={} type={}", userId, credentialType);
    }

    /**
     * 修改密码：验旧密码 → PasswordPolicy → 新旧不同 → 更新 users.password + PASSWORD 凭证 secret → 踢所有会话。
     *
     * <p>登录校验读 {@code users.password}（{@link AuthService#login} 直接用 user.getPassword()），
     * PASSWORD 凭证 secret 镜像同步仅为凭证表与 users 表一致性（非登录必需）。
     * 改密后踢该用户所有会话（{@link SessionService#kickAllSessions}）——旧 token 全失效，强制重登。
     *
     * @throws BusinessException 用户不存在(NOT_FOUND) / 旧密码错(BAD_REQUEST) / 策略不过(BAD_REQUEST) / 新旧相同(BAD_REQUEST)
     */
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        // 验旧密码（不通过不透露「用户是否存在」级别的信息——调用方已登录，明示旧密码错是安全的）
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前密码不正确");
        }
        // PasswordPolicy（强度/弱密码字典/用户名相同）
        PasswordPolicy.validate(user.getUsername(), newPassword);
        // 新旧不可相同
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "新密码不能与旧密码相同");
        }
        // 更新 users.password（登录校验源）
        String newHash = passwordEncoder.encode(newPassword);
        user.setPassword(newHash);
        userMapper.updateById(user);
        // 同步 PASSWORD 凭证 secret（镜像一致性；手机/微信注册用户无此凭证则跳过）
        credentialMapper.findByUserId(userId).stream()
                .filter(c -> UserCredential.TYPE_PASSWORD.equals(c.getCredentialType()))
                .findFirst()
                .ifPresent(pwdCred -> {
                    pwdCred.setSecret(newHash);
                    credentialMapper.updateById(pwdCred);
                });
        // 踢所有会话：改密是主动放弃所有会话的强语义，无条件全删（沉淀约束 4）
        sessionService.kickAllSessions(userId);
        log.info("修改密码成功 userId={}", userId);
    }

    /** 设置页展示：identifier 按类型脱敏（手机 138****8000 / 邮箱 a***@x.com / 微信*** / 用户名明文）。 */
    private CredentialVO toVO(UserCredential c) {
        return CredentialVO.builder()
                .credentialType(c.getCredentialType())
                .identifier(mask(c.getCredentialType(), c.getIdentifier()))
                .verified(c.getVerified())
                .verifiedAt(c.getVerifiedAt())
                .build();
    }

    /**
     * 凭证标识脱敏。手机号保留前3后4；邮箱保留首字符和域名；微信/钉钉用占位；用户名（PASSWORD）明文（本就不敏感）。
     */
    private String mask(String credentialType, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return "";
        }
        return switch (credentialType) {
            case UserCredential.TYPE_PHONE ->
                    identifier.length() >= 11
                            ? identifier.substring(0, 3) + "****" + identifier.substring(7)
                            : identifier;
            case UserCredential.TYPE_EMAIL -> {
                int at = identifier.indexOf('@');
                if (at <= 1) {
                    yield identifier.charAt(0) + "***" + (at > 0 ? identifier.substring(at) : "");
                }
                yield identifier.charAt(0) + "***" + identifier.substring(at);
            }
            case UserCredential.TYPE_WECHAT, UserCredential.TYPE_DINGTALK ->
                    identifier.length() > 6
                            ? identifier.substring(0, 3) + "***" + identifier.substring(identifier.length() - 3)
                            : identifier;
            default -> identifier; // PASSWORD（username）等明文展示
        };
    }
}
