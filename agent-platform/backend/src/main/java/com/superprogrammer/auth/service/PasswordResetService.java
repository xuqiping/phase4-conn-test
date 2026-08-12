// agent-platform/backend/src/main/java/com/superprogrammer/auth/service/PasswordResetService.java
package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserCredential;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.security.PasswordPolicy;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * 找回密码服务（通道 D：邮件 + 短信双通道）。
 *
 * <p>流程：
 * <ol>
 *   <li>forgot(identifier, channel)：查用户 → 生成 reset token 存 Redis → 统一话术（防枚举）→ 发邮件/短信</li>
 *   <li>reset(token/code, newPassword)：校验 token → PasswordPolicy → 更新密码 → 踢所有会话</li>
 * </ol>
 *
 * <p>安全语义：
 * <ul>
 *   <li>统一话术："若账号存在，重置链接/码已发送"（防账号枚举）</li>
 *   <li>reset token 用 SecureRandom，用完即删（防重放）</li>
 *   <li>未验证的 EMAIL 凭证不可用于找回（verified=FALSE 不发）</li>
 *   <li>新旧密码不可相同</li>
 *   <li>重置后踢该用户所有会话（直接 delete session:userId，重置是主动放弃所有会话的强语义）</li>
 *   <li>限流：同账号 3 次/h、同 IP 10 次/h</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserMapper userMapper;
    private final CredentialService credentialService;
    private final EmailService emailService;
    private final SmsService smsService;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final SessionService sessionService;

    private static final String RESET_LIMIT_ACCOUNT_PREFIX = "reset:limit:account:";
    private static final String RESET_LIMIT_IP_PREFIX = "reset:limit:ip:";
    private static final long RESET_LIMIT_WINDOW_SECONDS = 3600;
    private static final int RESET_LIMIT_ACCOUNT_MAX = 3;
    private static final int RESET_LIMIT_IP_MAX = 10;

    /**
     * 发起找回密码（统一话术防枚举）。
     *
     * @param identifier 账号标识（用户名/邮箱/手机号）
     * @param channel    渠道：EMAIL 或 SMS
     * @param clientIp   客户端 IP（限流用）
     * @return 统一话术"若账号存在，重置链接/码已发送"
     */
    public String forgot(String identifier, String channel, String clientIp) {
        // 限流
        checkRateLimit(identifier, clientIp);

        User user = findUserByIdentifier(identifier);
        if (user == null) {
            // 统一话术：不泄露账号是否存在（防枚举）
            return "若账号存在，重置链接/码已发送";
        }

        if ("SMS".equals(channel)) {
            // 短信找回：需用户绑了已验证手机
            UserCredential phoneCredential = credentialService.findForLogin(UserCredential.TYPE_PHONE, user.getPhone());
            if (phoneCredential == null || !Boolean.TRUE.equals(phoneCredential.getVerified()) || user.getPhone() == null) {
                return "若账号存在，重置链接/码已发送"; // 未绑手机/未验证 → 不发但仍返统一话术
            }
            // 发短信重置码（复用 SmsService 的发码能力，但用 reset 模板）
            // TODO: SmsService 加 sendResetCode 方法用 templateCodeReset 模板
            log.info("发起短信找回密码 userId={} phone={}", user.getId(), maskPhone(user.getPhone()));
        } else {
            // 邮件找回：需用户绑了已验证邮箱
            UserCredential emailCredential = findVerifiedEmailCredential(user.getId());
            if (emailCredential == null) {
                return "若账号存在，重置链接/码已发送"; // 未验证邮箱 → 不发但仍返统一话术
            }
            emailService.sendResetEmail(user.getId(), emailCredential.getIdentifier());
        }

        return "若账号存在，重置链接/码已发送";
    }

    /**
     * 重置密码（token 校验 + PasswordPolicy + 踢会话）。
     *
     * @param token       邮件重置 token（或短信码，channel=SMS 时）
     * @param newPassword 新密码
     * @param channel     渠道：EMAIL 或 SMS
     */
    @Transactional
    public void reset(String token, String newPassword, String channel, String phone) {
        Long userId;
        if ("SMS".equals(channel)) {
            // 短信重置：校验 6 位码
            userId = validateSmsResetCode(phone, token);
        } else {
            // 邮件重置：校验 token
            userId = validateEmailResetToken(token);
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        // PasswordPolicy 校验
        PasswordPolicy.validate(user.getUsername(), newPassword);

        // 新旧密码不可相同
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "新密码不能与旧密码相同");
        }

        // 更新密码（users 表 + user_credential PASSWORD 凭证）
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);

        // 更新 PASSWORD 凭证 secret（镜像 users.password；通过 CredentialService 内 mapper 同步）
        UserCredential pwdCredential = credentialService.findForLogin(UserCredential.TYPE_PASSWORD, user.getUsername());
        if (pwdCredential != null) {
            pwdCredential.setSecret(user.getPassword());
            // 通过 CredentialService 更新（用 mapper 更新）
            updateCredentialSecret(pwdCredential);
        }

        // 重置后踢该用户所有会话（沉淀约束 4：重置是主动放弃所有会话的强语义，无条件全删）
        // Chunk G 修复：原直接 redisTemplate.delete("session:"+userId) 用错了键前缀
        // （SessionService 实际键为 session:user:{userId}），导致重置后旧会话未失效。
        // 改走 SessionService.kickAllSessions 统一正确的键前缀 + 降级范式。
        sessionService.kickAllSessions(userId);
        log.info("重置密码后踢所有会话 userId={}", userId);
    }

    // ==================== 内部方法 ====================

    /** 按标识查用户（用户名/邮箱/手机号任一）。 */
    private User findUserByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return null;
        }
        // 先按用户名查
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, identifier);
        User user = userMapper.selectOne(wrapper);
        if (user != null) return user;

        // 按 EMAIL 凭证查
        UserCredential emailCred = credentialService.findForLogin(UserCredential.TYPE_EMAIL, identifier);
        if (emailCred != null) {
            return userMapper.selectById(emailCred.getUserId());
        }

        // 按 PHONE 凭证查
        UserCredential phoneCred = credentialService.findForLogin(UserCredential.TYPE_PHONE, identifier);
        if (phoneCred != null) {
            return userMapper.selectById(phoneCred.getUserId());
        }

        return null;
    }

    /** 找该用户已验证的 EMAIL 凭证。 */
    private UserCredential findVerifiedEmailCredential(Long userId) {
        return credentialService.findByUserIdRaw(userId).stream()
                .filter(c -> UserCredential.TYPE_EMAIL.equals(c.getCredentialType()) && Boolean.TRUE.equals(c.getVerified()))
                .findFirst()
                .orElse(null);
    }

    /** 校验邮件重置 token。 */
    private Long validateEmailResetToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.RESET_TOKEN_INVALID);
        }
        String userIdStr;
        try {
            userIdStr = redisTemplate.opsForValue().get(EmailService.RESET_TOKEN_PREFIX + token);
        } catch (Exception e) {
            log.error("重置 token 查 Redis 失败 : {}", e.toString());
            throw new BusinessException(ErrorCode.RESET_TOKEN_INVALID);
        }
        if (userIdStr == null) {
            throw new BusinessException(ErrorCode.RESET_TOKEN_INVALID);
        }
        // token 用完即删
        try {
            redisTemplate.delete(EmailService.RESET_TOKEN_PREFIX + token);
        } catch (Exception e) {
            log.warn("重置 token 删除失败(已吞) : {}", e.toString());
        }
        return Long.parseLong(userIdStr);
    }

    /** 校验短信重置码（简化版，复用 sms:code 前缀但用 reset 码）。 */
    private Long validateSmsResetCode(String phone, String code) {
        // TODO: 完整实现需用独立的 reset 码前缀（reset:sms:code:<phone>）
        // 当前简化：复用 EmailService.RESET_TOKEN_PREFIX 不适用，抛未实现
        throw new BusinessException(ErrorCode.RESET_TOKEN_INVALID, "短信重置码校验暂未实现");
    }

    /** 更新凭证 secret（直接用 mapper，避免 CredentialService 暴露写方法）。 */
    private void updateCredentialSecret(UserCredential credential) {
        // CredentialService 没有暴露 update 方法，这里直接通过 entity 的 update
        // 实际应给 CredentialService 加 updateSecret 方法
        log.warn("updateCredentialSecret 待实现：当前通过 users.password 已更新，凭证表 secret 同步待补");
    }

    /** 找回限流：同账号 3 次/h、同 IP 10 次/h。 */
    private void checkRateLimit(String identifier, String clientIp) {
        if (identifier != null && !identifier.isBlank()) {
            checkWindow(RESET_LIMIT_ACCOUNT_PREFIX + identifier, RESET_LIMIT_ACCOUNT_MAX);
        }
        if (clientIp != null && !clientIp.isBlank()) {
            checkWindow(RESET_LIMIT_IP_PREFIX + clientIp, RESET_LIMIT_IP_MAX);
        }
    }

    private void checkWindow(String key, int max) {
        try {
            Long n = redisTemplate.opsForValue().increment(key);
            if (n != null && n == 1L) {
                redisTemplate.expire(key, RESET_LIMIT_WINDOW_SECONDS, TimeUnit.SECONDS);
            }
            if (n != null && n > max) {
                throw new BusinessException(ErrorCode.RATE_LIMIT, "操作过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("找回限流 Redis 失败(降级放行) : {}", e.toString());
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 11) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
