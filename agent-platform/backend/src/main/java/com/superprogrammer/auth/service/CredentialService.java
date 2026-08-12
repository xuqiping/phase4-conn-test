// agent-platform/backend/src/main/java/com/superprogrammer/auth/service/CredentialService.java
package com.superprogrammer.auth.service;

import com.superprogrammer.auth.dto.CredentialVO;
import com.superprogrammer.auth.entity.UserCredential;
import com.superprogrammer.auth.mapper.UserCredentialMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 凭证基础 CRUD 服务（Chunk A）。
 *
 * <p>职责：登录路径查询、建凭证、标记验证、设置页列表。
 * 绑定/解绑/改密的业务规则（至少留一种、验旧密码、踢会话）在 Chunk E/G 的对应方法中实现。
 *
 * <p>安全语义：
 * <ul>
 *   <li>并发注册/绑定同一凭证 → DB 唯一约束兜底 → 转 CONFLICT 友好话术</li>
 *   <li>设置页 identifier 一律脱敏（手机/邮箱），防前端/日志明文回显</li>
 *   <li>软删（deleted=1）而非物理删——解绑留痕供审计</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialService {

    private final UserCredentialMapper credentialMapper;

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
