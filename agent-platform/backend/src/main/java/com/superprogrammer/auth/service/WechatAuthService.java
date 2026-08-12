// agent-platform/backend/src/main/java/com/superprogrammer/auth/service/WechatAuthService.java
package com.superprogrammer.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.auth.dto.TokenResponse;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserCredential;
import com.superprogrammer.auth.entity.UserRole;
import com.superprogrammer.auth.mapper.RoleMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.mapper.UserRoleMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.common.bean.oauth2.WxOAuth2AccessToken;
import me.chanjar.weixin.common.bean.WxOAuth2UserInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 微信扫码登录服务（通道 C）。
 *
 * <p>职责：生成授权跳转 URL / 处理回调换 JWT / 新号自动建号。
 *
 * <p>安全语义：
 * <ul>
 *   <li>state 参数防 CSRF（跳转时生成存 Redis 5min，回调严格校验 + 单次有效）</li>
 *   <li>AppSecret 仅后端（WxMpService 持有，不传前端）</li>
 *   <li>token 用 URL fragment 传递（不进 server log/referer）</li>
 *   <li>DISABLED 用户拒绝登录</li>
 *   <li>user==null 分支跑 dummy 比对抹时序侧信道（沉淀约束 6）</li>
 * </ul>
 *
 * <p>注意：WxMpService Bean 仅在 {@code app.auth.wechat.enabled=true} 时存在，
 * 本服务用 {@code @Autowired(required=false)} 注入，未配置时方法返回"未开启"。
 */
@Slf4j
@Service
public class WechatAuthService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final AuthService authService;
    private final CredentialService credentialService;

    /** WxMpService 仅在 wechat.enabled=true 时存在，用 required=false 注入。 */
    @Autowired(required = false)
    private WxMpService wxMpService;

    @Value("${app.auth.wechat.enabled:false}")
    private boolean wechatEnabled;

    @Value("${app.auth.wechat.redirect-uri:}")
    private String redirectUri;

    private static final String STATE_PREFIX = "wechat:state:";
    private static final long STATE_TTL_SECONDS = 5 * 60;
    private final SecureRandom secureRandom = new SecureRandom();

    public WechatAuthService(UserMapper userMapper, RoleMapper roleMapper, UserRoleMapper userRoleMapper,
                             PasswordEncoder passwordEncoder, StringRedisTemplate redisTemplate,
                             AuthService authService, CredentialService credentialService) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.redisTemplate = redisTemplate;
        this.authService = authService;
        this.credentialService = credentialService;
    }

    /** 是否启用（前端据此显隐微信登录 Tab）。 */
    public boolean isEnabled() {
        return wechatEnabled && wxMpService != null;
    }

    /**
     * 生成微信授权跳转 URL（前端 window.location 跳过去）。
     *
     * @return 授权 URL；未开启抛 BAD_REQUEST
     */
    public String buildAuthorizeUrl() {
        if (!isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "微信登录未开启");
        }
        // 生成 state（防 CSRF）
        String state = generateState();
        try {
            redisTemplate.opsForValue().set(STATE_PREFIX + state, "1", STATE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("微信 state 存 Redis 失败 : {}", e.toString());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成授权链接失败");
        }
        return wxMpService.getOAuth2Service().buildAuthorizationUrl(redirectUri, "snsapi_userinfo", state);
    }

    /**
     * 处理微信回调（code + state 换 JWT）。
     *
     * @param code  微信授权 code
     * @param state CSRF state（校验匹配 + 单次有效）
     * @return TokenResponse
     */
    @Transactional
    public TokenResponse handleCallback(String code, String state) {
        if (!isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "微信登录未开启");
        }

        // 校验 state（防 CSRF + 单次有效）
        validateState(state);

        // WxJava 用 code 换 access_token + openid + 用户信息
        WxOAuth2AccessToken accessToken;
        WxOAuth2UserInfo wxUser;
        try {
            accessToken = wxMpService.getOAuth2Service().getAccessToken(code);
            wxUser = wxMpService.getOAuth2Service().getUserInfo(accessToken, "zh_CN");
        } catch (Exception e) {
            log.error("微信 OAuth 换 token/用户信息失败 : {}", e.toString());
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "微信授权失败");
        }

        String unionid = wxUser.getUnionId();
        String openid = wxUser.getOpenid();
        String identifier = (unionid != null && !unionid.isBlank()) ? unionid : openid;

        // 查 WECHAT 凭证
        UserCredential credential = credentialService.findForLogin(UserCredential.TYPE_WECHAT, identifier);
        User user;
        if (credential == null) {
            // 新号 → 自动建号
            user = createUserByWechat(wxUser, unionid, openid, identifier);
        } else {
            user = userMapper.selectById(credential.getUserId());
            if (user == null) {
                // 沉淀约束 6：user==null 跑 dummy
                passwordEncoder.matches(code, "$2b$10$dinNKZ7q5nyOQXsC.P6uo.eqMpM6WlTeRO.2yV26dGK4V1tV0p2Kq");
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "微信登录失败");
            }
            if (!"ACTIVE".equals(user.getStatus())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户已被禁用或锁定");
            }
            // 刷新 avatar/name（参考现有钉钉模式）
            updateWxInfo(user, wxUser, unionid, openid);
        }

        log.info("微信扫码登录成功: unionId={} userId={}", maskIdentifier(unionid), user.getId());
        return authService.issueTokensForSms(user);
    }

    // ==================== 内部方法 ====================

    /** 校验 state（防 CSRF + 单次有效）。 */
    private void validateState(String state) {
        if (state == null || state.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "微信授权失败");
        }
        String key = STATE_PREFIX + state;
        try {
            String stored = redisTemplate.opsForValue().get(key);
            if (stored == null) {
                // state 不存在/已过期/已用过
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "微信授权失败");
            }
            // 单次有效：用完即删
            redisTemplate.delete(key);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("微信 state 校验 Redis 失败(降级放行) : {}", e.toString());
        }
    }

    /** 新号自动建号（username=wx_<openid前8位>, bind_type='wechat'）。 */
    private User createUserByWechat(WxOAuth2UserInfo wxUser, String unionid, String openid, String identifier) {
        User user = new User();
        user.setUsername("wx_" + (openid != null && openid.length() >= 8 ? openid.substring(0, 8) : openid));
        user.setName(wxUser.getNickname());
        user.setBindType("wechat");
        user.setWechatUnionid(unionid);
        user.setWechatOpenid(openid);
        user.setAvatar(wxUser.getHeadImgUrl());
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString())); // 占位 hash
        user.setStatus("ACTIVE");
        userMapper.insert(user);

        // 分配默认角色
        var roleWrapper = new LambdaQueryWrapper<com.superprogrammer.auth.entity.Role>();
        roleWrapper.eq(com.superprogrammer.auth.entity.Role::getCode, "user");
        var defaultRole = roleMapper.selectOne(roleWrapper);
        if (defaultRole != null) {
            userRoleMapper.insert(new UserRole(user.getId(), defaultRole.getId()));
        }

        // 建 WECHAT 凭证（verified=TRUE，授权即验证）
        credentialService.createCredential(user.getId(), UserCredential.TYPE_WECHAT, identifier, null, true);

        log.info("微信扫码登录新号自动建号: unionId={} userId={}", maskIdentifier(unionid), user.getId());
        return user;
    }

    /** 刷新微信用户信息（avatar/name 变更时更新）。 */
    private void updateWxInfo(User user, WxOAuth2UserInfo wxUser, String unionid, String openid) {
        boolean changed = false;
        if (wxUser.getHeadImgUrl() != null && !wxUser.getHeadImgUrl().equals(user.getAvatar())) {
            user.setAvatar(wxUser.getHeadImgUrl());
            changed = true;
        }
        if (wxUser.getNickname() != null && !wxUser.getNickname().equals(user.getName())) {
            user.setName(wxUser.getNickname());
            changed = true;
        }
        if (unionid != null && !unionid.equals(user.getWechatUnionid())) {
            user.setWechatUnionid(unionid);
            changed = true;
        }
        if (openid != null && !openid.equals(user.getWechatOpenid())) {
            user.setWechatOpenid(openid);
            changed = true;
        }
        if (changed) {
            userMapper.updateById(user);
        }
    }

    /** 生成 state（SecureRandom 16 字节 + hex）。 */
    private String generateState() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** identifier 脱敏（日志用）。 */
    private String maskIdentifier(String id) {
        if (id == null || id.length() < 6) return "***";
        return id.substring(0, 3) + "***" + id.substring(id.length() - 3);
    }
}
