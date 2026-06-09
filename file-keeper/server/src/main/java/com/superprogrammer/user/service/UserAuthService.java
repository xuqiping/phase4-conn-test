package com.superprogrammer.user.service;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.security.JwtService;
import com.superprogrammer.security.RefreshTokenService;
import com.superprogrammer.user.dto.*;
import com.superprogrammer.user.entity.User;
import com.superprogrammer.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserAuthService {

    private final UserRepository userRepository;
    private final VerificationService verificationService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public UserSummary register(RegisterRequest request) {
        String email = StringUtils.hasText(request.email())
                ? verificationService.normalizeContact("email", request.email())
                : null;
        String phone = StringUtils.hasText(request.phone())
                ? verificationService.normalizeContact("phone", request.phone())
                : null;
        if (email == null && phone == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱或手机号至少填写一个");
        }
        if (email != null && phone != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱和手机号只能选择一个注册");
        }
        String contactType = email != null ? "email" : "phone";
        String contact = email != null ? email : phone;
        if (userRepository.existsByContact(contactType, contact)) {
            throw new BusinessException(ErrorCode.CONFLICT, "联系方式已注册");
        }
        verificationService.consumeVerified(contactType, contact);
        return userRepository.insertPendingReviewUser(email, phone, passwordEncoder.encode(request.password()));
    }

    public AuthResponse clientLogin(LoginRequest request) {
        User user = userRepository.findByIdentifier(request.identifier().trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (AuthConstants.STATUS_DISABLED.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已禁用");
        }
        return createAuthResponse(user, refreshTokenService.create(user.getId()));
    }

    public AuthResponse refresh(String refreshToken) {
        Long userId = refreshTokenService.requireUserId(refreshToken);
        User user = userRepository.requireById(userId);
        if (AuthConstants.STATUS_DISABLED.equals(user.getStatus())) {
            refreshTokenService.delete(refreshToken);
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已禁用");
        }
        return createAuthResponse(user, refreshToken);
    }

    public void logout(String refreshToken) {
        refreshTokenService.delete(refreshToken);
    }

    public AuthResponse adminLogin(LoginRequest request) {
        User user = userRepository.findByIdentifier(request.identifier().trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (!AuthConstants.ROLE_SUPER_ADMIN.equals(user.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无管理员权限");
        }
        if (!AuthConstants.STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "管理员账号不可用");
        }
        return createAuthResponse(user, refreshTokenService.create(user.getId()));
    }

    public AuthResponse adminRefresh(String refreshToken) {
        AuthResponse response = refresh(refreshToken);
        if (!AuthConstants.ROLE_SUPER_ADMIN.equals(response.user().role())) {
            refreshTokenService.delete(refreshToken);
            throw new BusinessException(ErrorCode.FORBIDDEN, "无管理员权限");
        }
        if (!AuthConstants.STATUS_ACTIVE.equals(response.user().status())) {
            refreshTokenService.delete(refreshToken);
            throw new BusinessException(ErrorCode.FORBIDDEN, "管理员账号不可用");
        }
        return response;
    }

    private AuthResponse createAuthResponse(User user, String refreshToken) {
        String accessToken = jwtService.createAccessToken(user.getId(), user.getRole(), user.getStatus());
        long expiresInSeconds = 15 * 60;
        return new AuthResponse(accessToken, refreshToken, expiresInSeconds, userRepository.toSummary(user));
    }
}
