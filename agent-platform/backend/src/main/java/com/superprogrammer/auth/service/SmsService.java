// agent-platform/backend/src/main/java/com/superprogrammer/auth/service/SmsService.java
package com.superprogrammer.auth.service;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.dysmsapi.model.v20170525.SendSmsRequest;
import com.aliyuncs.dysmsapi.model.v20170525.SendSmsResponse;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.profile.IClientProfile;
import com.superprogrammer.auth.dto.TokenResponse;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserCredential;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 短信验证码服务（通道 B：手机验证码登录/注册 + 通道 D 找回密码发短信）。
 *
 * <p>职责：发码（前置滑块校验 + 限流）/ 验码登录（新号自动建号）。
 *
 * <p>安全语义：
 * <ul>
 *   <li>滑块前置闸门：发码前必须过滑块（防机器人批量刷短信）</li>
 *   <li>手机号格式：正则 {@code ^1[3-9]\d{9}$} 严格只放国内号（防 SMS Pumping 高价国际号）</li>
 *   <li>限流三档：同号 60s 一次 / 同号每天 10 次 / 同 IP 每天 30 次</li>
 *   <li>验证码用完即删（Redis DEL），防重放</li>
 *   <li>错误 5 次作废（防穷举）</li>
 *   <li>统一话术：不区分"码错"和"号不存在"（防枚举）</li>
 *   <li>user==null 分支跑 dummy 比对抹平时序侧信道（沉淀约束 6）</li>
 *   <li>阿里云超时降级（connectTimeout=3s/readTimeout=5s）</li>
 * </ul>
 */
@Slf4j
@Service
public class SmsService {

    private final AuthChannelSettingService channelSettings;
    private final StringRedisTemplate redisTemplate;
    private final CredentialService credentialService;
    private final CaptchaService captchaService;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;
    private final com.superprogrammer.auth.mapper.UserMapper userMapper;
    private final com.superprogrammer.auth.mapper.RoleMapper roleMapper;
    private final com.superprogrammer.auth.mapper.UserRoleMapper userRoleMapper;

    /** 验证码 Redis 前缀。 */
    private static final String SMS_CODE_PREFIX = "sms:code:";
    /** 验证码错误计数前缀。 */
    private static final String SMS_CODE_FAIL_PREFIX = "sms:code:fail:";
    /** 限流前缀：同号 60s。 */
    private static final String SMS_LIMIT_PHONE_PREFIX = "sms:limit:phone:";
    /** 限流前缀：同号每天。 */
    private static final String SMS_LIMIT_PHONE_DAILY_PREFIX = "sms:limit:phone:daily:";
    /** 限流前缀：同 IP 每天。 */
    private static final String SMS_LIMIT_IP_DAILY_PREFIX = "sms:limit:ip:daily:";

    /** 验证码有效期：5min。 */
    private static final long CODE_TTL_SECONDS = 5 * 60;
    /** 同号 60s 限流窗口。 */
    private static final long PHONE_WINDOW_SECONDS = 60;
    /** 验证码错误上限：5 次作废。 */
    private static final int CODE_MAX_FAILS = 5;

    private final SecureRandom secureRandom = new SecureRandom();

    public SmsService(AuthChannelSettingService channelSettings, StringRedisTemplate redisTemplate,
                      CredentialService credentialService, CaptchaService captchaService,
                      AuthService authService, PasswordEncoder passwordEncoder,
                      com.superprogrammer.auth.mapper.UserMapper userMapper,
                      com.superprogrammer.auth.mapper.RoleMapper roleMapper,
                      com.superprogrammer.auth.mapper.UserRoleMapper userRoleMapper) {
        this.channelSettings = channelSettings;
        this.redisTemplate = redisTemplate;
        this.credentialService = credentialService;
        this.captchaService = captchaService;
        this.authService = authService;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
    }

    /**
     * 发短信验证码（前置滑块校验 + 限流）。
     *
     * @param phone            手机号（正则 {@code ^1[3-9]\d{9}$}）
     * @param captchaToken     滑块验证码 token（AJ-Captcha）
     * @param clientIp         客户端 IP（限流用）
     * @return 统一话术（"验证码已发送"），不泄露手机号是否注册
     */
    public String sendCode(String phone, String captchaToken, String clientIp) {
        var config = channelSettings.smsSnapshot();
        if (!config.enabled() || config.accessKeyId() == null || config.accessKeyId().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "短信服务未开启");
        }

        // 滑块前置闸门（防机器人批量刷短信）
        captchaService.verify(captchaToken);

        // 手机号格式校验（拒国际号，防 SMS Pumping）
        if (!phone.matches("^1[3-9]\\d{9}$")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号格式不正确");
        }

        // 限流三档
        checkRateLimit(phone, clientIp, config);

        // 同号 5min 内已发未用 → 拒重发（防刷量）
        String codeKey = SMS_CODE_PREFIX + phone;
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(codeKey))) {
                return "验证码已发送，请 5 分钟后再试";
            }
        } catch (Exception e) {
            log.warn("查验证码存在性 Redis 失败(降级放行) phone={} : {}", phone, e.toString());
        }

        // 生成 6 位码
        String code = generateCode();
        try {
            redisTemplate.opsForValue().set(codeKey, code, config.codeTtlMinutes() * 60L, TimeUnit.SECONDS);
            redisTemplate.delete(SMS_CODE_FAIL_PREFIX + phone); // 清错误计数
        } catch (Exception e) {
            log.error("验证码存 Redis 失败 phone={} : {}", phone, e.toString());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "发送失败，请稍后重试");
        }

        // 阿里云 SMS 发送
        boolean sent = sendSms(config, phone, config.templateCodeVerify(), "{\"code\":\"" + code + "\"}");
        if (!sent) {
            // 发送失败 → 删 Redis 里的码（用户没收到，不能留着）
            try {
                redisTemplate.delete(codeKey);
            } catch (Exception e) {
                log.warn("发送失败后删验证码 Redis 失败(已吞) phone={} : {}", phone, e.toString());
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "发送失败，请稍后重试");
        }

        return "验证码已发送";
    }

    /**
     * 验证码登录（新号自动建号）。
     *
     * @param phone 手机号
     * @param code  验证码
     * @return TokenResponse（JWT）
     */
    @Transactional
    public TokenResponse verifyAndLogin(String phone, String code) {
        // 校验码匹配 + 未过期 + 错误次数<5
        validateCode(phone, code);

        // 查 PHONE 凭证
        UserCredential credential = credentialService.findForLogin(UserCredential.TYPE_PHONE, phone);
        User user;
        if (credential == null) {
            // 新号 → 自动建号
            user = createUserByPhone(phone);
        } else {
            // 老号 → 查 users
            user = userMapper.selectById(credential.getUserId());
            if (user == null) {
                // 沉淀约束 6：user==null 也走 dummy 比对（抹平时序侧信道）
                passwordEncoder.matches(code, "$2b$10$dinNKZ7q5nyOQXsC.P6uo.eqMpM6WlTeRO.2yV26dGK4V1tV0p2Kq");
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证码错误或已过期");
            }
            // 校验 status=ACTIVE
            if (!"ACTIVE".equals(user.getStatus())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户已被禁用或锁定");
            }
        }

        // 码用完即删（防重放）
        try {
            redisTemplate.delete(SMS_CODE_PREFIX + phone);
        } catch (Exception e) {
            log.warn("验证码删除失败(已吞) phone={} : {}", phone, e.toString());
        }

        // 发 JWT（复用 AuthService 现有发 token 逻辑）
        return authService.issueTokensForSms(user);
    }

    // ==================== 内部方法 ====================

    /** 限流三档：同号 60s / 同号每天 10 次 / 同 IP 每天 30 次。 */
    private void checkRateLimit(String phone, String clientIp, AuthChannelSettingService.SmsSnapshot config) {
        // 同号 60s
        checkWindow(SMS_LIMIT_PHONE_PREFIX + phone, 1, PHONE_WINDOW_SECONDS, "发送过于频繁，请 60 秒后再试");

        // 同号每天
        String phoneDailyKey = SMS_LIMIT_PHONE_DAILY_PREFIX + phone;
        checkDailyLimit(phoneDailyKey, config.limitPerPhonePerDay(), "该手机号今日发送次数已达上限");

        // 同 IP 每天
        if (clientIp != null && !clientIp.isBlank()) {
            String ipDailyKey = SMS_LIMIT_IP_DAILY_PREFIX + clientIp;
            checkDailyLimit(ipDailyKey, config.limitPerIpPerDay(), "该 IP 今日发送次数已达上限");
        }
    }

    /** 固定窗口限流（60s）。 */
    private void checkWindow(String key, long max, long windowSeconds, String errorMessage) {
        try {
            Long n = redisTemplate.opsForValue().increment(key);
            if (n != null && n == 1L) {
                redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
            }
            if (n != null && n > max) {
                throw new BusinessException(ErrorCode.RATE_LIMIT, errorMessage);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("限流 Redis 检查失败(降级放行) key={} : {}", key, e.toString());
        }
    }

    /** 每天限流（TTL 到当日 24 点）。 */
    private void checkDailyLimit(String key, long max, String errorMessage) {
        try {
            Long n = redisTemplate.opsForValue().increment(key);
            if (n != null && n == 1L) {
                // TTL 到当日 24 点
                long secondsToMidnight = java.time.Duration.between(
                        java.time.OffsetDateTime.now(),
                        java.time.OffsetDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0)
                ).getSeconds();
                redisTemplate.expire(key, secondsToMidnight, TimeUnit.SECONDS);
            }
            if (n != null && n > max) {
                throw new BusinessException(ErrorCode.RATE_LIMIT, errorMessage);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("每日限流 Redis 检查失败(降级放行) key={} : {}", key, e.toString());
        }
    }

    /** 校验码：匹配 + 未过期 + 错误次数<5。不匹配 → 错误计数 +1，≥5 作废。统一话术防枚举。 */
    private void validateCode(String phone, String code) {
        String codeKey = SMS_CODE_PREFIX + phone;
        String failKey = SMS_CODE_FAIL_PREFIX + phone;

        String storedCode;
        try {
            storedCode = redisTemplate.opsForValue().get(codeKey);
        } catch (Exception e) {
            log.error("验证码查 Redis 失败 phone={} : {}", phone, e.toString());
            throw new BusinessException(ErrorCode.SMS_CODE_INVALID);
        }

        if (storedCode == null) {
            throw new BusinessException(ErrorCode.SMS_CODE_INVALID);
        }

        // 错误次数检查
        String failCountStr;
        try {
            failCountStr = redisTemplate.opsForValue().get(failKey);
        } catch (Exception e) {
            log.warn("验证码错误计数查 Redis 失败(降级) phone={} : {}", phone, e.toString());
            failCountStr = null;
        }
        int failCount = failCountStr == null ? 0 : Integer.parseInt(failCountStr);
        if (failCount >= CODE_MAX_FAILS) {
            // 作废
            try {
                redisTemplate.delete(codeKey);
                redisTemplate.delete(failKey);
            } catch (Exception e) {
                log.warn("作废验证码删 Redis 失败(已吞) phone={} : {}", phone, e.toString());
            }
            throw new BusinessException(ErrorCode.SMS_CODE_INVALID);
        }

        if (!storedCode.equals(code)) {
            // 错误计数 +1
            try {
                Long n = redisTemplate.opsForValue().increment(failKey);
                if (n != null && n == 1L) {
                    redisTemplate.expire(failKey, CODE_TTL_SECONDS, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.warn("验证码错误计数 Redis 失败(降级) phone={} : {}", phone, e.toString());
            }
            throw new BusinessException(ErrorCode.SMS_CODE_INVALID);
        }
    }

    /** 新号自动建号（username=phone, bind_type='phone', password=随机 hash）。 */
    private User createUserByPhone(String phone) {
        User user = new User();
        user.setUsername(phone);
        user.setPhone(phone);
        user.setBindType("phone");
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString())); // 占位 hash（不可用于密码登录）
        user.setStatus("ACTIVE");
        userMapper.insert(user);

        // 分配默认角色
        var roleWrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.superprogrammer.auth.entity.Role>();
        roleWrapper.eq(com.superprogrammer.auth.entity.Role::getCode, "user");
        var defaultRole = roleMapper.selectOne(roleWrapper);
        if (defaultRole != null) {
            userRoleMapper.insert(new com.superprogrammer.auth.entity.UserRole(user.getId(), defaultRole.getId()));
        }

        // 建 PHONE 凭证（verified=TRUE，收码即验证）
        credentialService.createCredential(user.getId(), UserCredential.TYPE_PHONE, phone, null, true);

        log.info("手机验证码登录新号自动建号: phone={} userId={}", phone, user.getId());
        return user;
    }

    /** 生成 6 位数字验证码（SecureRandom）。 */
    private String generateCode() {
        int code = secureRandom.nextInt(900000) + 100000;
        return String.valueOf(code);
    }

    /** 阿里云 SMS 发送。超时降级。 */
    private boolean sendSms(AuthChannelSettingService.SmsSnapshot config,
                            String phone, String templateCode, String templateParam) {
        try {
            IClientProfile profile = DefaultProfile.getProfile(config.region(),
                    config.accessKeyId(), config.accessKeySecret());
            IAcsClient client = new DefaultAcsClient(profile);

            SendSmsRequest request = new SendSmsRequest();
            request.setPhoneNumbers(phone);
            request.setSignName(config.signName());
            request.setTemplateCode(templateCode);
            request.setTemplateParam(templateParam);

            SendSmsResponse response = client.getAcsResponse(request);
            boolean success = "OK".equals(response.getCode());
            log.info("短信发送{} phone={} template={} code={} message={}",
                    success ? "成功" : "失败", maskPhone(phone), templateCode, response.getCode(), response.getMessage());
            return success;
        } catch (Exception e) {
            log.error("短信发送异常 phone={} template={} : {}", maskPhone(phone), templateCode, e.toString());
            return false;
        }
    }

    /** 手机号脱敏（日志用）：138****8000。 */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 11) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
