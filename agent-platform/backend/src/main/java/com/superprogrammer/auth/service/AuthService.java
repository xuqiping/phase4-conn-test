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
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
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

    private static final String TOKEN_BLACKLIST_PREFIX = "token:blacklist:";

    @Transactional
    public void register(RegisterRequest request) {
        // 检查用户名唯一
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, request.getUsername());
        User existing = userMapper.selectOne(wrapper);
        if (existing != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }

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

        log.info("用户注册成功: {}", user.getUsername());
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        // 查询用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, request.getUsername());
        User user = userMapper.selectOne(wrapper);

        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        // 验证密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        // 检查用户状态
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户已被禁用或锁定");
        }

        // 查询角色和权限
        List<String> roleCodes = userMapper.selectRoleCodesByUsername(user.getUsername());
        List<String> permissionCodes = userMapper.selectPermissionCodesByUserId(user.getId());

        // 生成JWT Token（走公共方法）
        log.info("用户登录成功: {}", user.getUsername());
        return issueTokens(user, roleCodes, permissionCodes);
    }

    /**
     * 公共发 token：根据已认证的 User 签发 access+refresh，返回 TokenResponse。
     */
    private TokenResponse issueTokens(User user, List<String> roleCodes, List<String> permissionCodes) {
        long accessExpirationMs = systemSettingService.getAccessTokenExpirationMs();
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername(), roleCodes, accessExpirationMs);
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

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
                        .email(user.getEmail())
                        .avatar(user.getAvatar())
                        .roles(roleCodes)
                        .permissions(permissionCodes)
                        .build())
                .build();
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
            user.setBindType("dingtalk");
            user.setDingtalkUnionId(info.unionId());
            user.setDingtalkOpenId(info.openId());
            user.setAvatar(info.avatar());
            user.setStatus("ACTIVE");
            userMapper.insert(user);

            // 分配默认角色 user
            LambdaQueryWrapper<Role> roleWrapper = new LambdaQueryWrapper<>();
            roleWrapper.eq(Role::getCode, "user");
            Role defaultRole = roleMapper.selectOne(roleWrapper);
            if (defaultRole != null) {
                UserRole userRole = new UserRole(user.getId(), defaultRole.getId());
                userRoleMapper.insert(userRole);
            }
            log.info("钉钉用户首次登录自动建号: unionId={}, userId={}", info.unionId(), user.getId());
        } else {
            // 已绑定 → 刷新 openId/avatar（防钉钉头像变更）
            if (info.openId() != null && !info.openId().equals(user.getDingtalkOpenId())) {
                user.setDingtalkOpenId(info.openId());
            }
            if (info.avatar() != null && !info.avatar().equals(user.getAvatar())) {
                user.setAvatar(info.avatar());
            }
            if (!"ACTIVE".equals(user.getStatus())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户已被禁用或锁定");
            }
        }

        List<String> roleCodes = userMapper.selectRoleCodesByUsername(user.getUsername());
        List<String> permissionCodes = userMapper.selectPermissionCodesByUserId(user.getId());
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

        // 检查Redis黑名单
        String jti = jwtUtil.getTokenId(refreshToken);
        String blacklistKey = TOKEN_BLACKLIST_PREFIX + jti;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(blacklistKey))) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "Token已失效");
        }

        // 获取用户信息并生成新access token
        Long userId = jwtUtil.getUserIdFromToken(refreshToken);
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        List<String> roleCodes = userMapper.selectRoleCodesByUsername(user.getUsername());
        long accessExpirationMs = systemSettingService.getAccessTokenExpirationMs();
        String newAccessToken = jwtUtil.generateAccessToken(userId, user.getUsername(), roleCodes, accessExpirationMs);

        return TokenResponse.builder()
                .accessToken(newAccessToken)
                .tokenType("Bearer")
                .expiresIn(accessExpirationMs)
                .build();
    }

    public void logout(String accessToken, String refreshToken) {
        // 将access token加入黑名单
        if (accessToken != null && jwtUtil.isTokenValid(accessToken)) {
            String accessJti = jwtUtil.getTokenId(accessToken);
            long accessTtl = jwtUtil.getRemainingTtl(accessToken);
            if (accessTtl > 0) {
                redisTemplate.opsForValue().set(
                        TOKEN_BLACKLIST_PREFIX + accessJti, "1", accessTtl, TimeUnit.MILLISECONDS);
            }
        }

        // 将refresh token加入黑名单
        if (refreshToken != null && jwtUtil.isTokenValid(refreshToken)) {
            String refreshJti = jwtUtil.getTokenId(refreshToken);
            long refreshTtl = jwtUtil.getRemainingTtl(refreshToken);
            if (refreshTtl > 0) {
                redisTemplate.opsForValue().set(
                        TOKEN_BLACKLIST_PREFIX + refreshJti, "1", refreshTtl, TimeUnit.MILLISECONDS);
            }
        }

        log.info("用户登出成功");
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
                .email(user.getEmail())
                .avatar(user.getAvatar())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .roles(roleCodes)
                .permissions(permissionCodes)
                .build();
    }

    public boolean isTokenBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_BLACKLIST_PREFIX + jti));
    }

    /**
     * 获取用户权限编码列表（供JwtAuthenticationFilter使用）
     */
    public List<String> getUserPermissionCodes(Long userId) {
        return userMapper.selectPermissionCodesByUserId(userId);
    }
}
