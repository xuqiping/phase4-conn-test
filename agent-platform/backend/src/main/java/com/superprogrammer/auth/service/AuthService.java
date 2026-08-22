// agent-platform/backend/src/main/java/com/superprogrammer/auth/service/AuthService.java
package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.auth.dingtalk.service.DingTalkService;
import com.superprogrammer.auth.dto.*;
import com.superprogrammer.auth.entity.Role;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserRole;
import com.superprogrammer.auth.mapper.RoleMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.mapper.UserRoleMapper;
import com.superprogrammer.auth.security.JwtUtil;
import com.superprogrammer.auth.security.PasswordPolicy;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.system.service.SystemSettingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final SystemSettingService systemSettingService;
    private final DepartmentService departmentService;
    /** 日志系统 LOG-FR-11：登录/登出/刷新/注册审计（异步落库，绝不阻断认证主流程）。 */
    private final AuditLogService auditLogService;
    /** 运维系统 OPS-FR-07：登录结果 + 注册限流触发指标（result 仅 success/fail）。 */
    private final com.superprogrammer.common.metrics.BizMetrics bizMetrics;
    /** 安全体系 S2 · A8（SEC-FR-008）：单点登录会话（sid 签发/比对/登出清除）。 */
    private final SessionService sessionService;
    /** 认证系统增强 Chunk A/B：多凭证账号模型（注册时建 PASSWORD/EMAIL 凭证）。 */
    private final CredentialService credentialService;
    /** 11x 加固 P2-C7：登录尝试取证（异步，不阻主链）。 */
    private final com.superprogrammer.common.security.LoginAttemptsService loginAttemptsService;
    /** 11x 加固 P2-C7：撞库自动封 IP。 */
    private final com.superprogrammer.common.security.IpBlacklistService ipBlacklistService;
    /** 11x 加固 P2-C7：安全事件落库（暴破/撞库）。 */
    private final com.superprogrammer.common.security.SecurityEventService securityEventService;
    /** 11x 加固 P1-C3：暴破锁号即时踢下线。 */
    private final com.superprogrammer.common.security.BanService banService;
    /** 认证系统增强 Chunk E：异地登录提醒（登录成功后异步比对省份）。 */
    private final LoginAlertService loginAlertService;
    /** 安全体系 S5 · SEC-FR-006（A6 TOTP）：已绑定用户两步登录 + 验证码校验。 */
    private final MfaService mfaService;
    /** 12x B1：注册前置邮箱验证码校验（码过才建号，EMAIL 凭证直接 verified=TRUE）。 */
    private final EmailService emailService;

    private static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";
    /**
     * 安全体系 S5 · SEC-FR-004+（A4 旋转）：旋转拉黑标记值。logout 写 "1"（正常过期），
     * 旋转写本标记——刷新路径读到它 = 已被旋转换走的 refresh 再次出现 = 重放信号（token 可能被偷）。
     */
    private static final String REFRESH_ROTATION_MARKER = "rotated";
    /** 安全体系 S5 · SEC-FR-004+：重放检出告警（MEDIUM 事件）。横切可选依赖，单测无 Bean 直通。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.superprogrammer.common.security.SecurityEventPublisher securityEventPublisher;

    // 安全审计 #9：开放注册限流（防单 IP 1 分钟批量注册万号烧 LLM 配额 + 放大 SSRF 攻击面）
    private static final String RATE_LIMIT_IP_PREFIX = "ratelimit:register:ip:";
    private static final String RATE_LIMIT_USER_PREFIX = "ratelimit:register:user:";
    private static final long REGISTER_WINDOW_SECONDS = 60;
    private static final long REGISTER_MAX_PER_IP = 5;
    private static final long REGISTER_MAX_PER_USERNAME = 5;

    // 安全体系 S1 · SEC-FR-001 登录防爆破：同账号 5 次失败锁 15min；同 IP 1h 失败 >20 次封禁。
    // 计数键 TTL=窗口（自然过期自动解锁，TTL 误杀合法用户风险归零）；Redis 故障降级放行 + WARN。
    private static final String LOGIN_FAIL_USER_PREFIX = "login:fail:u:";
    private static final String LOGIN_FAIL_IP_PREFIX = "login:fail:ip:";
    /** user==null 分支的 dummy 比对目标（有效 bcrypt，强度与真实口令一致），抹平账号存在性时间侧信道。 */
    private static final String DUMMY_BCRYPT_HASH = "$2b$10$dinNKZ7q5nyOQXsC.P6uo.eqMpM6WlTeRO.2yV26dGK4V1tV0p2Kq";
    private static final long LOGIN_LOCK_MAX_FAILS = 5;
    private static final long LOGIN_LOCK_WINDOW_SECONDS = 15 * 60;
    private static final long LOGIN_IP_BAN_MAX_FAILS = 20;
    private static final long LOGIN_IP_WINDOW_SECONDS = 3600;

    @Transactional
    public void register(RegisterRequest request) {
        // 限流（安全审计 #9）：IP + 用户名双维度，超阈值 → 429
        try {
            checkRegisterRateLimit(currentClientIp(), request.getUsername());
        } catch (BusinessException e) {
            // OPS-FR-07：限流触发计数（每次被拒注册正好一次；IP/用户名双窗口可能双中，这里只记一次）
            if (e.getCode() == ErrorCode.RATE_LIMIT.getCode()) {
                bizMetrics.registerRateLimited();
            }
            throw e;
        }

        // 检查用户名唯一
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, request.getUsername());
        User existing = userMapper.selectOne(wrapper);
        if (existing != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }

        // 密码策略（复杂度/弱密码字典/与用户名相同/bcrypt 72 字节上限）
        PasswordPolicy.validate(request.getUsername(), request.getPassword());

        // 12x B1：注册前置校验邮箱 6 位码（验过即删，一次性）。
        // 放在查重/密码策略之后——这两类失败不烧码，用户改完可直接重提。
        emailService.verifyRegisterCode(request.getEmail(), request.getEmailCode());

        // 创建用户
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setStatus("ACTIVE");
        userMapper.insert(user);

        // 分配默认角色(user)
        LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
        roleWrapper.eq(Role::getCode, "user");
        Role defaultRole = roleMapper.selectOne(roleWrapper);
        if (defaultRole != null) {
            UserRole userRole = new UserRole(user.getId(), defaultRole.getId());
            userRoleMapper.insert(userRole);
        }

        // 认证系统增强 Chunk A/B：建多凭证（PASSWORD verified=TRUE）。
        // 12x B1：EMAIL 验证码已过 → 直接 verified=TRUE（无需注册后再点激活链接）。
        // 注意：发验证邮件不放本事务（外部 SMTP 调用可能慢，拉长事务），B1 后注册流程已不再需要。
        try {
            credentialService.createCredential(user.getId(), com.superprogrammer.auth.entity.UserCredential.TYPE_PASSWORD,
                    user.getUsername(), user.getPassword(), true);
        } catch (BusinessException e) {
            // 并发注册同一用户名：DB 唯一约束兜底（users.uk_users_username），这里转 CONFLICT
            log.warn("建 PASSWORD 凭证冲突 userId={} username={} : {}", user.getId(), user.getUsername(), e.toString());
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }

        try {
            credentialService.createCredential(user.getId(), com.superprogrammer.auth.entity.UserCredential.TYPE_EMAIL,
                    request.getEmail(), null, true);
        } catch (BusinessException e) {
            // 邮箱已被他人使用（并发注册同邮箱）：users.uk_users_email 已兜底，这里不应触发；防御性转 CONFLICT
            log.warn("建 EMAIL 凭证冲突 userId={} email={} : {}", user.getId(), request.getEmail(), e.toString());
            throw new BusinessException(ErrorCode.CONFLICT, "该邮箱已被使用");
        }

        log.info("用户注册成功: {}", user.getUsername());
        auditAuth("register", user.getId(), user.getUsername(), AuditLogEntity.RESULT_SUCCESS, null);
    }

    /**
     * 注册限流（安全审计 #9）：IP + 用户名双维度，固定窗口 60s。
     * <p>Redis 故障 → 降级放行（不阻断注册主链），仅记日志。
     */
    private void checkRegisterRateLimit(String ip, String username) {
        if (ip != null && !ip.isBlank()) {
            checkRateWindow(RATE_LIMIT_IP_PREFIX + ip, REGISTER_MAX_PER_IP);
        }
        if (username != null && !username.isBlank()) {
            checkRateWindow(RATE_LIMIT_USER_PREFIX + username.toLowerCase(), REGISTER_MAX_PER_USERNAME);
        }
    }

    private void checkRateWindow(String key, long max) {
        try {
            Long n = redisTemplate.opsForValue().increment(key);
            if (n != null && n == 1L) {
                redisTemplate.expire(key, REGISTER_WINDOW_SECONDS, TimeUnit.SECONDS);
            }
            if (n != null && n > max) {
                throw new BusinessException(ErrorCode.RATE_LIMIT,
                        "注册过于频繁，请稍后再试（限流窗口 " + REGISTER_WINDOW_SECONDS + "s）");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("注册限流 Redis 检查失败，降级放行: {}", e.getMessage());
        }
    }

    /**
     * 取真实客户端 IP。Phase4 审查修正：X-Forwarded-For 是客户端可伪造头，无条件信任 =
     * 攻击者轮换 XFF 即架空 IP 封禁（且 login:fail:ip:* 键无限膨胀）。
     * 仅当 remoteAddr 命中可信代理网段（{@code app.security.trusted-proxies}，逗号分隔精确 IP，
     * 默认空=不信任何 XFF）才采纳 XFF 首段；生产 Nginx 反代须把 Nginx 内网地址配进来。
     */
    @org.springframework.beans.factory.annotation.Value("${app.security.trusted-proxies:}")
    private String trustedProxies;

    private String currentClientIp() {
        try {
            Object attrsObj = RequestContextHolder.currentRequestAttributes();
            if (!(attrsObj instanceof ServletRequestAttributes attrs)) return null;
            HttpServletRequest req = attrs.getRequest();
            String remote = req.getRemoteAddr();
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank() && isTrustedProxy(remote)) {
                int comma = xff.indexOf(',');
                return (comma > 0 ? xff.substring(0, comma) : xff).trim();
            }
            return remote;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || trustedProxies == null || trustedProxies.isBlank()) return false;
        for (String p : trustedProxies.split(",")) {
            if (remoteAddr.equals(p.trim())) return true;
        }
        return false;
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        // SEC-FR-001 防爆破前置闸：命中账号锁/IP 封禁 → 固定话术拒绝（不区分「密码错」与「已锁定」）
        String usernameKey = request.getUsername() == null ? "" : request.getUsername().toLowerCase(java.util.Locale.ROOT);
        String clientIp = currentClientIp();
        assertLoginAllowed(usernameKey, clientIp);

        // 查询用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, request.getUsername());
        User user = userMapper.selectOne(wrapper);

        if (user == null) {
            // Phase4 审查修正：不存在用户也做一次 dummy bcrypt 比对——否则响应时间差（跳过 ~100ms 哈希）
            // 即账号存在性 oracle（40103 统一话术堵了内容侧，时间侧也得堵）。
            passwordEncoder.matches(request.getPassword(), DUMMY_BCRYPT_HASH);
            recordLoginFailure(usernameKey, clientIp, null, request.getUsername());
            loginAttemptsService.recordAsync(usernameKey, null, clientIp, false, "no_such_user");
            auditAuth("login", null, request.getUsername(), AuditLogEntity.RESULT_FAIL, "user_not_found");
            bizMetrics.authLogin(com.superprogrammer.common.metrics.BizMetrics.RESULT_FAIL);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        // 验证密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            recordLoginFailure(usernameKey, clientIp, user.getId(), user.getUsername());
            loginAttemptsService.recordAsync(usernameKey, user.getId(), clientIp, false, "bad_password");
            auditAuth("login", user.getId(), user.getUsername(), AuditLogEntity.RESULT_FAIL, "bad_password");
            bizMetrics.authLogin(com.superprogrammer.common.metrics.BizMetrics.RESULT_FAIL);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        // 检查用户状态
        if (!"ACTIVE".equals(user.getStatus())) {
            auditAuth("login", user.getId(), user.getUsername(), AuditLogEntity.RESULT_FAIL, "user_disabled");
            bizMetrics.authLogin(com.superprogrammer.common.metrics.BizMetrics.RESULT_FAIL);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户已被禁用或锁定");
        }

        // 查询角色和权限
        List<String> roleCodes = userMapper.selectRoleCodesByUsername(user.getUsername());
        List<String> permissionCodes = userMapper.selectPermissionCodesByUserId(user.getId());

        // 登录成功清账号失败计数（IP 计数保留：防多账号轮试）
        clearLoginFailure(usernameKey);
        loginAttemptsService.recordAsync(usernameKey, user.getId(), clientIp, true, null);

        // 生成JWT Token（走公共方法）
        log.info("用户登录成功: {}", user.getUsername());
        auditAuth("login", user.getId(), user.getUsername(), AuditLogEntity.RESULT_SUCCESS, null);
        bizMetrics.authLogin(com.superprogrammer.common.metrics.BizMetrics.RESULT_SUCCESS);

        // 安全体系 S5 · SEC-FR-006（A6 TOTP）：已绑定用户走两步登录——密码因子已过，
        // 此处不发 access/refresh，只发 5 分钟 mfaToken 引导第二屏（校验通过才建会话发双 token）。
        if (mfaService.isBound(user.getId())) {
            log.info("TOTP已绑定，进入两步登录: {}", user.getUsername());
            auditAuth("login_mfa", user.getId(), user.getUsername(), AuditLogEntity.RESULT_SUCCESS, "mfa_pending");
            return TokenResponse.builder()
                    .mfaRequired(true)
                    .mfaToken(jwtUtil.generateMfaToken(user.getId()))
                    .tokenType("Bearer")
                    .build();
        }

        // 认证系统增强 Chunk E：异地登录提醒（异步，不阻塞发 token）
        try {
            loginAlertService.maybeAlert(user, user.getUsername(), clientIp);
        } catch (Exception e) {
            log.warn("异地登录提醒调度失败(已吞) : {}", e.toString());
        }

        TokenResponse response = issueTokens(user, roleCodes, permissionCodes);
        // S5 A6：totp.required 灰度开关开 + admin 未绑定 → 建议标记（前端引导绑定，不阻断登录）
        if (systemSettingService.getAuthTotpRequired()
                && roleCodes != null && roleCodes.contains("admin") && !mfaService.isBound(user.getId())) {
            response.setMfaBindAdvice(true);
        }
        return response;
    }

    /**
     * 安全体系 S5 · SEC-FR-006（A6 TOTP）：两步登录第二屏校验。
     *
     * <p>链路：mfaToken 签名/类型/有效期 → 一次性（jti 黑名单）→ 防爆破（同一 mfaToken
     * 最多 5 次试错，6 位码空间 10^6，5 分钟窗内 5 次封顶）→ TOTP/恢复码校验 →
     * 消费 mfaToken + 用户状态复查（防密码步与验证码步之间被禁用）→ issueTokens 建会话。</p>
     */
    @Transactional
    public TokenResponse verifyMfa(MfaVerifyRequest request) {
        String mfaToken = request.getMfaToken();
        if (!jwtUtil.isTokenValid(mfaToken) || !"mfa".equals(jwtUtil.getTypeFromToken(mfaToken))) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "MFA会话已失效，请重新登录");
        }
        String jti = jwtUtil.getTokenId(mfaToken);
        Long userId = jwtUtil.getUserIdFromToken(mfaToken);
        if (isTokenBlacklisted(jti)) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "MFA会话已失效，请重新登录");
        }
        if (exceedsMfaAttempts(jti)) {
            blacklistMfaToken(jti);
            auditAuth("login_mfa", userId, null, AuditLogEntity.RESULT_FAIL, "mfa_attempts_exceeded");
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "验证尝试次数过多，请重新登录");
        }

        if (!mfaService.verifyAndConsume(userId, request.getCode(), true)) {
            auditAuth("login_mfa", userId, null, AuditLogEntity.RESULT_FAIL, "mfa_code_wrong");
            bizMetrics.authMfaVerify(com.superprogrammer.common.metrics.BizMetrics.RESULT_FAIL);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证码错误");
        }

        // Phase4 交叉审查修正：原子消费——setIfAbsent 抢占 jti 拉黑位，并发同票同码只有
        // 一个赢家（旧实现验码成功后才 set，双发 token 竞态）；抢输=同票被复用（重放）→ 拒。
        if (!consumeMfaTokenOnce(jti)) {
            auditAuth("login_mfa", userId, null, AuditLogEntity.RESULT_FAIL, "mfa_token_replayed");
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "MFA会话已失效，请重新登录");
        }
        User user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            auditAuth("login_mfa", userId, null, AuditLogEntity.RESULT_FAIL, "user_disabled");
            bizMetrics.authMfaVerify(com.superprogrammer.common.metrics.BizMetrics.RESULT_FAIL);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户已被禁用或锁定");
        }

        log.info("TOTP两步登录完成: {}", user.getUsername());
        auditAuth("login_mfa", user.getId(), user.getUsername(), AuditLogEntity.RESULT_SUCCESS, "mfa_verified");
        bizMetrics.authMfaVerify(com.superprogrammer.common.metrics.BizMetrics.RESULT_SUCCESS);
        List<String> roleCodes = userMapper.selectRoleCodesByUsername(user.getUsername());
        List<String> permissionCodes = userMapper.selectPermissionCodesByUserId(user.getId());
        return issueTokens(user, roleCodes, permissionCodes);
    }

    /** 同一 mfaToken 试错计数（Redis 5 分钟窗，>5 次 → 作废该票）。Redis 故障降级：跳过限次（WARN）。 */
    private boolean exceedsMfaAttempts(String jti) {
        try {
            Long n = redisTemplate.opsForValue().increment("mfa:tries:" + jti);
            redisTemplate.expire("mfa:tries:" + jti, 5 * 60, TimeUnit.SECONDS);
            return n != null && n > 5;
        } catch (Exception e) {
            log.warn("MFA试错计数Redis失败(降级不限次) : {}", e.getMessage());
            return false;
        }
    }

    /** 消费 mfaToken：jti 拉黑 5 分钟（= 票自然寿命，一次性）。失败仅 WARN 不阻断（票已短命）。 */
    private void blacklistMfaToken(String jti) {
        try {
            redisTemplate.opsForValue().set(TOKEN_BLACKLIST_PREFIX + jti, "1",
                    5 * 60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("MFA token拉黑失败(降级,票5min自然过期) : {}", e.getMessage());
        }
    }

    /**
     * 原子消费 mfaToken（Phase4 修正）：setIfAbsent 抢占 jti 拉黑位——返回 true=赢家（首次使用），
     * false=同票已被并发使用（重放）。Redis 故障降级放行 + WARN（沿黑名单家族范式：可用性 > 强制力，
     * 且票本身仅 5min 短命）。
     */
    private boolean consumeMfaTokenOnce(String jti) {
        try {
            Boolean won = redisTemplate.opsForValue().setIfAbsent(
                    TOKEN_BLACKLIST_PREFIX + jti, "1", 5 * 60, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(won);
        } catch (Exception e) {
            log.warn("MFA token原子消费Redis失败(降级放行,票5min自然过期) : {}", e.getMessage());
            return true;
        }
    }

    // ---------- S5 A6 TOTP：绑定流委托 MfaService（AuthController 转发用） ----------

    public boolean isMfaBound(Long userId) {
        return mfaService.isBound(userId);
    }

    public boolean isTotpRequired() {
        return systemSettingService.getAuthTotpRequired();
    }

    public com.superprogrammer.auth.dto.MfaBindResponse startMfaBind(Long userId) {
        User user = userMapper.selectById(userId);
        return mfaService.startBind(userId, user == null ? String.valueOf(userId) : user.getUsername());
    }

    public com.superprogrammer.auth.dto.MfaBindResponse confirmMfaBind(Long userId, String code) {
        return mfaService.confirmBind(userId, code);
    }

    public void unbindMfa(Long userId, String code) {
        mfaService.unbind(userId, code);
    }

    /**
     * SEC-FR-001 前置闸：账号失败计数 ≥5（15min 窗口）或 IP 失败计数 >20（1h 窗口）→ 拒绝。
     * 固定话术（LOGIN_LOCKED 单一口径），不泄露是账号锁还是 IP 封。Redis 异常 → 降级放行 + WARN。
     */
    private void assertLoginAllowed(String usernameKey, String ip) {
        try {
            if (!usernameKey.isBlank()) {
                String fails = redisTemplate.opsForValue().get(LOGIN_FAIL_USER_PREFIX + usernameKey);
                if (fails != null && Long.parseLong(fails) >= LOGIN_LOCK_MAX_FAILS) {
                    bizMetrics.authLoginLocked("account");
                    throw new BusinessException(ErrorCode.LOGIN_LOCKED);
                }
            }
            if (ip != null && !ip.isBlank()) {
                String fails = redisTemplate.opsForValue().get(LOGIN_FAIL_IP_PREFIX + ip);
                if (fails != null && Long.parseLong(fails) > LOGIN_IP_BAN_MAX_FAILS) {
                    bizMetrics.authLoginLocked("ip");
                    throw new BusinessException(ErrorCode.LOGIN_LOCKED);
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("登录防爆破 Redis 检查失败，降级放行: {}", e.getMessage());
        }
    }

    /**
     * 登录失败计数（user-not-found / bad_password 两分支调用）。越过阈值瞬间写安全审计
     * （login_locked / ip_banned，仅跃迁写一次防刷屏）+ ERROR 级日志供锁定风暴排查。
     *
     * <p>11x 加固 P2-C7 扩展（阈值跃迁瞬间追加）：
     * ① 账号 5 次/15min → DB 锁号（status=LOCKED + locked_until=+15min）+ ban 标记踢下线
     *    + LOGIN_BRUTE_FORCE HIGH 事件（auto_action=ACCOUNT_LOCKED）——原来只 Redis 计数，
     *    Redis 故障即归零，升级后 DB 持久且即时踢；P3 AccountUnlockScheduler 到期自动解锁；
     * ② 同 IP 5min 内试 ≥20 个不同账号 → 撞库判定：CREDENTIAL_STUFFING HIGH 事件
     *    + ipBlacklistService.autoBlock(60min)（撞库封 IP 而非锁每个号——防账号枚举 DoS）。</p>
     */
    private void recordLoginFailure(String usernameKey, String ip, Long userId, String username) {
        try {
            if (!usernameKey.isBlank()) {
                Long n = incrWithWindow(LOGIN_FAIL_USER_PREFIX + usernameKey, LOGIN_LOCK_WINDOW_SECONDS);
                if (n != null && n == LOGIN_LOCK_MAX_FAILS) {
                    bizMetrics.authLoginLocked("account");
                    auditAuth("login_locked", userId, username, AuditLogEntity.RESULT_FAIL, "fail_count_" + n);
                    log.error("账号登录失败达阈值锁定 15min: username={} ip={}", username, ip);
                    onBruteForceLocked(userId, username, ip, n);
                }
            }
            if (ip != null && !ip.isBlank()) {
                Long n = incrWithWindow(LOGIN_FAIL_IP_PREFIX + ip, LOGIN_IP_WINDOW_SECONDS);
                if (n != null && n == LOGIN_IP_BAN_MAX_FAILS + 1) {
                    bizMetrics.authLoginLocked("ip");
                    auditAuth("ip_banned", userId, username, AuditLogEntity.RESULT_FAIL, "ip_fail_count_" + n);
                    log.error("IP 登录失败达阈值封禁 1h: ip={} lastUsername={}", ip, username);
                }
                checkCredentialStuffing(ip, usernameKey);
            }
        } catch (Exception e) {
            log.warn("登录失败计数 Redis 失败(已吞，不阻断登录): {}", e.getMessage());
        }
    }

    /** 暴破锁号（11x C7）：DB LOCKED + locked_until + ban 标记 + HIGH 事件。全程 try 吞——不阻登录主链。 */
    private void onBruteForceLocked(Long userId, String username, String ip, Long failCount) {
        try {
            securityEventService.record(
                    com.superprogrammer.common.security.SecurityEventTypes.LOGIN_BRUTE_FORCE,
                    com.superprogrammer.common.security.SecurityEventTypes.SEV_HIGH,
                    userId, ip, null,
                    "{\"username\":\"" + username + "\",\"fails\":" + failCount + "}",
                    userId != null
                            ? com.superprogrammer.common.security.SecurityEventTypes.ACT_ACCOUNT_LOCKED
                            : com.superprogrammer.common.security.SecurityEventTypes.ACT_NONE);
            if (userId != null) {
                User target = userMapper.selectById(userId);
                if (target != null && "ACTIVE".equals(target.getStatus())) {
                    target.setStatus("LOCKED");
                    target.setLockedUntil(OffsetDateTime.now().plusMinutes(15));
                    target.setBanReason("LOGIN_BRUTE_FORCE");
                    userMapper.updateById(target);
                    banService.revoke(userId, "LOCKED");
                    bizMetrics.accountLocked("brute_force");
                }
            }
        } catch (Exception e) {
            log.warn("暴破锁号执行失败(已吞,Redis 计数闸仍在) userId={} : {}", userId, e.getMessage());
        }
    }

    /** 撞库检测（11x C7）：同 IP 5min 窗内 ≥20 个不同账号 → 封 IP 1h + HIGH 事件。 */
    private void checkCredentialStuffing(String ip, String usernameKey) {
        try {
            String key = "login:ids:" + ip;
            redisTemplate.opsForSet().add(key, usernameKey);
            redisTemplate.expire(key, 300, TimeUnit.SECONDS);
            Long distinct = redisTemplate.opsForSet().size(key);
            if (distinct != null && distinct == 20L) {
                ipBlacklistService.autoBlock(ip,
                        com.superprogrammer.common.security.SecurityEventTypes.CREDENTIAL_STUFFING, 60);
                securityEventService.record(
                        com.superprogrammer.common.security.SecurityEventTypes.CREDENTIAL_STUFFING,
                        com.superprogrammer.common.security.SecurityEventTypes.SEV_HIGH,
                        null, ip, null,
                        "{\"distinctAccounts\":" + distinct + "}",
                        com.superprogrammer.common.security.SecurityEventTypes.ACT_IP_BLOCKED);
                log.error("撞库判定封 IP 1h: ip={} distinctAccounts={}", ip, distinct);
            }
        } catch (Exception e) {
            log.warn("撞库检测失败(已吞，不阻断登录) ip={} : {}", ip, e.getMessage());
        }
    }

    private Long incrWithWindow(String key, long windowSeconds) {
        Long n = redisTemplate.opsForValue().increment(key);
        if (n != null && n == 1L) {
            redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
        }
        return n;
    }

    /** 登录成功清账号计数（IP 计数保留）。Redis 异常吞掉——清零失败只是锁多留到 TTL。 */
    private void clearLoginFailure(String usernameKey) {
        try {
            if (!usernameKey.isBlank()) {
                redisTemplate.delete(LOGIN_FAIL_USER_PREFIX + usernameKey);
            }
        } catch (Exception e) {
            log.warn("登录成功清零计数 Redis 失败(已吞): {}", e.getMessage());
        }
    }

    /**
     * 登录/认证审计（LOG-FR-11）：module=auth，detail 只带 reason 码——<b>严禁密码/token 原文</b>。
     * 异步落库，任何异常吞掉（认证主流程绝不被审计拖垮）。
     */
    private void auditAuth(String action, Long userId, String username, String result, String reason) {
        try {
            String detail = reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}";
            AuditLogEntity row = auditLogService.fromMdc("auth", action, "user",
                    userId == null ? null : String.valueOf(userId), detail, result);
            // 登录前无 JWT，MDC userId 是 "-"——显式覆盖为真实身份
            row.setUserId(userId);
            row.setUsername(username);
            row.setUserAgent(currentUserAgent());
            auditLogService.record(row);
        } catch (Exception e) {
            log.warn("认证审计落库失败(已吞): action={} : {}", action, e.toString());
        }
    }

    /** UA 截断 256；非 web 上下文 → null。 */
    private String currentUserAgent() {
        try {
            Object attrsObj = RequestContextHolder.currentRequestAttributes();
            if (!(attrsObj instanceof ServletRequestAttributes attrs)) return null;
            String ua = attrs.getRequest().getHeader("User-Agent");
            return ua != null && ua.length() > 256 ? ua.substring(0, 256) : ua;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 公共发 token：根据已认证的 User 签发 access+refresh，返回 TokenResponse。
     */
    private TokenResponse issueTokens(User user, List<String> roleCodes, List<String> permissionCodes) {
        long accessExpirationMs = systemSettingService.getAccessTokenExpirationMs();
        // A8 单点登录：开新会话（覆盖写=踢旧），sid 签进 access+refresh
        String sid = sessionService.newSession(user.getId(), user.getUsername());
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername(), roleCodes, accessExpirationMs, sid);
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), sid);

        // 更新最后登录时间
        user.setLastLoginAt(OffsetDateTime.now());
        userMapper.updateById(user);

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(accessExpirationMs)
                .userInfo(TokenResponse.UserInfo.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .name(user.getName())
                        .primaryDepartmentName(departmentService.getPrimaryDepartmentName(user.getId()))
                        .email(user.getEmail())
                        .avatar(user.getAvatar())
                        .roles(roleCodes)
                        .permissions(permissionCodes)
                        .build())
                .build();
    }

    /**
     * 手机验证码登录发 token（SmsService 用）。
     * 复用 issueTokens 核心逻辑，但角色/权限由手机号登录路径现查。
     */
    public TokenResponse issueTokensForSms(User user) {
        List<String> roleCodes = userMapper.selectRoleCodesByUsername(user.getUsername());
        List<String> permissionCodes = userMapper.selectPermissionCodesByUserId(user.getId());
        return issueTokens(user, roleCodes, permissionCodes);
    }

    /**
     * 钉钉免登登录：按 unionId 查找或自动建号，签发本平台 JWT。
     */
    @Transactional
    public TokenResponse loginByDingTalk(DingTalkService.DingTalkUserInfo info) {
        if (info == null || info.unionId() == null || info.unionId().isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉 unionId 为空");
        }

        // 1) 按 unionId 查既有用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getDingtalkUnionId, info.unionId());
        User user = userMapper.selectOne(wrapper);

        if (user == null) {
            // 2) 首次免登 → 自动建号
            user = new User();
            user.setUsername("dt_" + info.unionId());
            user.setName(info.nick());
            user.setBindType("dingtalk");
            user.setDingtalkUnionId(info.unionId());
            user.setDingtalkOpenId(info.openId());
            user.setAvatar(info.avatar());
            user.setStatus("ACTIVE");
            // 钉钉用户不走密码登录；password 列 NOT NULL，填随机占位 hash（不可用于密码登录）
            user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
            userMapper.insert(user);

            // 分配默认角色 user
            LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
            roleWrapper.eq(Role::getCode, "user");
            Role defaultRole = roleMapper.selectOne(roleWrapper);
            if (defaultRole != null) {
                UserRole userRole = new UserRole(user.getId(), defaultRole.getId());
                userRoleMapper.insert(userRole);
            }
            log.info("钉钉用户首次登录自动建号: unionId={}, userId={}, name={}", info.unionId(), user.getId(), info.nick());
            auditAuth("dingtalk_register", user.getId(), user.getUsername(), AuditLogEntity.RESULT_SUCCESS, null);
        } else {
            // 已绑定 → 刷新 openId/avatar/name（防钉钉变更）
            if (info.openId() != null && !info.openId().equals(user.getDingtalkOpenId())) {
                user.setDingtalkOpenId(info.openId());
            }
            if (info.avatar() != null && !info.avatar().equals(user.getAvatar())) {
                user.setAvatar(info.avatar());
            }
            if (info.nick() != null && !info.nick().equals(user.getName())) {
                user.setName(info.nick());
                userMapper.updateById(user);
            }
            if (!"ACTIVE".equals(user.getStatus())) {
                auditAuth("dingtalk_login", user.getId(), user.getUsername(), AuditLogEntity.RESULT_FAIL, "user_disabled");
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户已被禁用或锁定");
            }
        }

        List<String> roleCodes = userMapper.selectRoleCodesByUsername(user.getUsername());
        List<String> permissionCodes = userMapper.selectPermissionCodesByUserId(user.getId());
        // 同步钉钉部门到本地（按 dingtalkDeptId 建/匹配 + 关联用户，幂等）
        departmentService.syncUserDepartmentsFromDingtalk(user.getId(), info.depts(), user.getId());
        auditAuth("dingtalk_login", user.getId(), user.getUsername(), AuditLogEntity.RESULT_SUCCESS, null);
        return issueTokens(user, roleCodes, permissionCodes);
    }

    public TokenResponse refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();

        // 验证refresh token
        if (!jwtUtil.isTokenValid(refreshToken)) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "无效的Refresh Token");
        }

        // 检查token类型
        String type = jwtUtil.getTypeFromToken(refreshToken);
        if (!"refresh".equals(type)) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "请使用Refresh Token刷新");
        }

        // 检查Redis黑名单（降级放行同 isTokenBlacklisted：Redis 故障不阻断刷新主链）
        String jti = jwtUtil.getTokenId(refreshToken);
        if (isTokenBlacklisted(jti)) {
            // 安全体系 S5 · SEC-FR-004+（A4 旋转）：黑名单值="rotated" 的 refresh 再次出现 = 重放。
            // （logout 拉黑的值="1"，属正常登出过期路径，不告警。）话术固定 TOKEN_INVALID 防探测。
            if (REFRESH_ROTATION_MARKER.equals(blacklistMarker(jti))) {
                log.warn("refresh token 重放检出(已被旋转的旧票再次使用) jti={}", jti);
                bizMetrics.authRefreshReplayed();
                if (securityEventPublisher != null) {
                    try {
                        securityEventPublisher.publish(
                                com.superprogrammer.common.security.event.ApplicationSecurityEvent.KIND_TOKEN_REUSE,
                                jwtUtil.getUserIdFromToken(refreshToken),
                                java.util.Map.of("jti", jti));
                    } catch (Exception e) {
                        log.warn("TOKEN_REUSE 事件发布失败(不阻断拒绝): {}", e.getMessage());
                    }
                }
                auditAuth("refresh", null, null, AuditLogEntity.RESULT_FAIL, "refresh_replayed");
            }
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "Token已失效");
        }

        // 获取用户信息并生成新access token
        Long userId = jwtUtil.getUserIdFromToken(refreshToken);
        // A8：refresh 须带 sid 且匹配当前会话（被踢会话的 refresh 同样拒绝，固定话术）
        String sid = jwtUtil.getSidFromToken(refreshToken);
        if (!sessionService.isCurrent(userId, sid)) {
            // 8x-1：username 传 null（查询侧按 userId 回填），不再写入"-"占位
            auditAuth("refresh", userId, null, AuditLogEntity.RESULT_FAIL, "session_kicked");
            throw new BusinessException(ErrorCode.SESSION_KICKED);
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            auditAuth("refresh", null, null, AuditLogEntity.RESULT_FAIL, "user_not_found");
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        // 11x 加固 P1-C3：refresh 兜底查 DB status——单点登录关/Redis 故障时封号用户不得换发新 access。
        // 固定 SESSION_KICKED 话术（不透传「被封」防探测）。
        if (!"ACTIVE".equals(user.getStatus())) {
            auditAuth("refresh", userId, user.getUsername(), AuditLogEntity.RESULT_FAIL, "user_not_active");
            throw new BusinessException(ErrorCode.SESSION_KICKED);
        }

        List<String> roleCodes = userMapper.selectRoleCodesByUsername(user.getUsername());
        long accessExpirationMs = systemSettingService.getAccessTokenExpirationMs();
        // A8：旋转沿用同 sid（会话延续）
        String newAccessToken = jwtUtil.generateAccessToken(userId, user.getUsername(), roleCodes, accessExpirationMs, sid);

        // 安全体系 S5 · SEC-FR-004+（A4 refresh 旋转）：旧票拉黑（值=rotated，TTL=剩余有效期）+
        // 签发新 refresh（同 sid）回传——7 天重放窗口封死。
        // Phase4 交叉审查修正（原子闸）：拉黑改 setIfAbsent 抢占——并发双发同票只有一个赢家，
        // 抢输 = 该票已被并发旋转换走（重放）→ 拒绝（旧实现 set 非原子，两请求都成功旋转各自换新票）。
        // Redis 故障仍 WARN 降级（旧票至多活到自然过期，access 15min 短命兜底）。
        String newRefreshToken = refreshToken;
        if (systemSettingService.getAuthRefreshRotationEnabled()) {
            try {
                long remainingTtl = jwtUtil.getRemainingTtl(refreshToken);
                if (remainingTtl > 0) {
                    Boolean won = redisTemplate.opsForValue().setIfAbsent(
                            TOKEN_BLACKLIST_PREFIX + jti, REFRESH_ROTATION_MARKER,
                            remainingTtl, TimeUnit.MILLISECONDS);
                    if (!Boolean.TRUE.equals(won)) {
                        log.warn("refresh并发旋转抢占失败(按重放拒绝) jti={}", jti);
                        bizMetrics.authRefreshReplayed();
                        auditAuth("refresh", userId, user.getUsername(),
                                AuditLogEntity.RESULT_FAIL, "refresh_replayed");
                        throw new BusinessException(ErrorCode.TOKEN_INVALID, "Token已失效");
                    }
                }
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                log.warn("refresh旋转拉黑旧票失败(降级:旧票残留至自然过期,access仍15min短命): {}", e.getMessage());
            }
            newRefreshToken = jwtUtil.generateRefreshToken(userId, sid);
            bizMetrics.authRefreshRotated();
        }

        auditAuth("refresh", user.getId(), user.getUsername(), AuditLogEntity.RESULT_SUCCESS, null);
        return TokenResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .tokenType("Bearer")
                .expiresIn(accessExpirationMs)
                .build();
    }

    public void logout(String accessToken, String refreshToken) {
        // 黑名单写入（Redis 故障 → WARN 不阻断登出；黑名单缺失的代价=token 残留至自然过期，access 仅 15min）
        try {
            // 将access token加入黑名单
            blacklistToken(accessToken);

            // 将refresh token加入黑名单
            blacklistToken(refreshToken);
        } catch (Exception e) {
            log.warn("登出黑名单写入失败(降级:登出继续,token残留至自然过期): {}", e.getMessage());
        }

        // 审计：登出（MDC 已有 userId/username——logout 必带 JWT 经过 MdcUserFilter；无上下文走"-"兜底）
        Long logoutUserId = null;
        String logoutUsername = null;
        String logoutSid = null;
        if (accessToken != null && jwtUtil.isTokenValid(accessToken)) {
            logoutUserId = jwtUtil.getUserIdFromToken(accessToken);
            logoutUsername = jwtUtil.getUsernameFromToken(accessToken);
            logoutSid = jwtUtil.getSidFromToken(accessToken);
        }
        // A8：登出删会话键——比对 sid 只删自己的会话（旧会话登出不踢飞当前会话，防 logout-bomb）；
        // 黑名单保留防登出后 token 残留复用；sid 比对在黑名单之后
        sessionService.clearSession(logoutUserId, logoutSid);
        auditAuth("logout", logoutUserId, logoutUsername, AuditLogEntity.RESULT_SUCCESS, null);
        log.info("用户登出成功");
    }

    /**
     * 单 token 拉黑（jti + 剩余 TTL；已过期/非法 token 静默跳过）。logout/注销共用。
     */
    private void blacklistToken(String token) {
        if (token == null || !jwtUtil.isTokenValid(token)) {
            return;
        }
        String jti = jwtUtil.getTokenId(token);
        long ttl = jwtUtil.getRemainingTtl(token);
        if (ttl > 0) {
            redisTemplate.opsForValue().set(
                    TOKEN_BLACKLIST_PREFIX + jti, "1", ttl, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 安全体系 S5 · SEC-FR-100（J2 注销）：登录态+密码确认 → 软删匿名化。
     *
     * <p>只匿名化身份字段（username/email/name/phone/avatar/微信钉钉绑定 → deleted_{uuid} 体系值）、
     * 状态置 DELETED（登录口 ACTIVE 检查=再不可登录）、随机口令覆盖；计费流水/审计日志按法定口径
     * 保留（脱敏口径见隐私政策页）。踢全部会话（kickAllSessions=注销语义，与改密一致）+ 当前双
     * token 拉黑——注销即刻全端 401。
     */
    @Transactional
    public void deleteAccount(Long userId, String password, String accessToken, String refreshToken) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "账号当前状态不允许注销");
        }
        if (password == null || password.isBlank()
                || !passwordEncoder.matches(password, user.getPassword())) {
            auditAuth("account_deleted", userId, user.getUsername(),
                    AuditLogEntity.RESULT_FAIL, "password_confirm_failed");
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码错误，注销未执行");
        }

        String oldUsername = user.getUsername();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        // 匿名化：uuid 后缀保证唯一索引（uk_users_username/uk_users_email）零碰撞；
        // .invalid 保留域（RFC2606）——邮件系统永不投递，防误发到真实域名
        user.setUsername("deleted_" + suffix);
        user.setName("已注销用户");
        user.setEmail(suffix + "@deleted.invalid");
        user.setAvatar(null);
        user.setPhone(null);
        user.setWechatUnionid(null);
        user.setWechatOpenid(null);
        user.setDingtalkUnionId(null);
        user.setDingtalkOpenId(null);
        // 随机口令覆盖：即便他日放开 DELETED 状态检查，任何已知口令也命中不了
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatus("DELETED");
        user.setBanReason(null);
        user.setLockedUntil(null);
        userMapper.updateById(user);

        // TOTP 材料清痕（secret/恢复码哈希/pending）——失败仅降级：账号已不可登录
        try {
            mfaService.purgeForDeletedUser(userId);
        } catch (Exception e) {
            log.warn("注销TOTP清痕失败(降级:账号已不可登录): {}", e.getMessage());
        }

        // 全会话踢除（主动放弃所有会话——密码变更同语义）
        sessionService.kickAllSessions(userId);
        // 当前双 token 立即拉黑（不等 15min/7d 自然过期）
        try {
            blacklistToken(accessToken);
            blacklistToken(refreshToken);
        } catch (Exception e) {
            log.warn("注销token拉黑失败(降级:token残留至自然过期): {}", e.getMessage());
        }

        auditAuth("account_deleted", userId, oldUsername, AuditLogEntity.RESULT_SUCCESS, null);
        log.info("用户注销完成(软删匿名化) userId={}", userId);
    }

    public UserVO getCurrentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        List<String> roleCodes = userMapper.selectRoleCodesByUsername(user.getUsername());
        List<String> permissionCodes = userMapper.selectPermissionCodesByUserId(userId);

        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .name(user.getName())
                .primaryDepartmentName(departmentService.getPrimaryDepartmentName(userId))
                .email(user.getEmail())
                .avatar(user.getAvatar())
                .status(user.getStatus())
                .banReason(user.getBanReason())
                .lockedUntil(user.getLockedUntil())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .roles(roleCodes)
                .permissions(permissionCodes)
                .build();
    }

    /**
     * 黑名单查询。Redis 故障 → 降级放行 + WARN（可用性 > 强制力，与 S1 防爆破/A8 会话比对同范式；
     * 黑名单条目本就短 TTL=token 剩余有效期，降级窗口内被登出 token 至多残留几分钟）。
     */
    public boolean isTokenBlacklisted(String jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + jti));
        } catch (Exception e) {
            log.warn("Token黑名单查询失败(降级放行): {}", e.getMessage());
            return false;
        }
    }

    /**
     * 安全体系 S5 · SEC-FR-004+：读黑名单标记值（区分 logout="1" / 旋转="rotated"）。
     * Redis 故障返回 null（调用侧只在 isTokenBlacklisted 已确认存在时读，降级不改变拒绝结果）。
     */
    private String blacklistMarker(String jti) {
        try {
            return redisTemplate.opsForValue().get(TOKEN_BLACKLIST_PREFIX + jti);
        } catch (Exception e) {
            log.warn("Token黑名单标记读取失败(按普通失效处理): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取用户权限编码列表（供JwtAuthenticationFilter使用）
     */
    public List<String> getUserPermissionCodes(Long userId) {
        return userMapper.selectPermissionCodesByUserId(userId);
    }
}
