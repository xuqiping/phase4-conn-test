package com.superprogrammer.auth.security;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 密码策略（安全体系补强）：注册唯一密码入口的统一校验。
 * 规则：6-100 字符（DTO @Size 已挡，本类兜底）；必须同时含小写/大写/数字/特殊字符；
 * 拒绝弱密码字典（忽略大小写）；拒绝与用户名相同（忽略大小写）；
 * 拒绝超 bcrypt 72 字节上限（超长部分会被 bcrypt 静默截断，等效弱密码）。
 * 注：登录路径不过本策略（存量弱密码账号仍可登录，改密端点落地后再收口）。
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    /** bcrypt 只取前 72 字节，超出部分被静默丢弃 */
    private static final int BCRYPT_MAX_BYTES = 72;

    private static final Pattern LOWER = Pattern.compile("[a-z]");
    private static final Pattern UPPER = Pattern.compile("[A-Z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final Pattern SPECIAL = Pattern.compile("[^a-zA-Z0-9\\s]");

    /** 常见弱密码字典（小写存储，比对时忽略大小写）。刻意小而准，覆盖 top 撞库样本 */
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "123456", "123456789", "12345678", "1234567890", "1234567",
            "password", "password1", "password123", "passw0rd", "passw0rd!", "p@ssw0rd", "p@ssw0rd!",
            "qwerty", "qwerty123", "qwertyuiop", "abc123", "abcdef",
            "admin", "admin123", "admin123456", "root", "root123", "test123", "test1234",
            "111111", "123123", "666666", "88888888", "5201314", "iloveyou",
            "letmein", "welcome", "monkey", "dragon", "sunshine", "princess",
            "1q2w3e4r", "1qaz2wsx", "qazwsx", "zxcvbnm", "000000"
    );

    /**
     * 校验密码是否符合策略。不通过抛 BusinessException(BAD_REQUEST)，话术说明具体原因
     * （注册场景可用性优先，明示原因不属于敏感信息泄露）。
     */
    public static void validate(String username, String password) {
        if (password == null || password.length() < 6 || password.length() > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码长度必须在6-100之间");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码过长：加密算法上限72字节（约24个汉字或72个英文字符）");
        }
        if (username != null && password.equalsIgnoreCase(username)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码不能与用户名相同");
        }
        if (WEAK_PASSWORDS.contains(password.toLowerCase())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码过于常见，容易被猜到，请更换");
        }
        if (!LOWER.matcher(password).find() || !UPPER.matcher(password).find()
                || !DIGIT.matcher(password).find() || !SPECIAL.matcher(password).find()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码须同时包含大写字母、小写字母、数字和特殊字符");
        }
    }
}
