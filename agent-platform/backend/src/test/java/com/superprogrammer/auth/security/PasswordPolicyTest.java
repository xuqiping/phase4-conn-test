package com.superprogrammer.auth.security;

import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PasswordPolicy 单测：复杂度四要素 / 弱密码字典 / 与用户名相同 / bcrypt 72 字节上限。
 */
class PasswordPolicyTest {

    @Test
    void valid_strongPassword_passes() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("zhangsan", "Str0ng#Pass"));
        assertDoesNotThrow(() -> PasswordPolicy.validate("zhangsan", "Xk9!mvQ2zL"));
    }

    @Test
    void tooShortOrTooLong_rejected() {
        BusinessException e1 = assertThrows(BusinessException.class,
                () -> PasswordPolicy.validate("u", "Ab1!x"));
        assertTrue(e1.getMessage().contains("6-100"));

        String long101 = "Aa1!" + "x".repeat(97); // 101 字符
        assertThrows(BusinessException.class, () -> PasswordPolicy.validate("u", long101));
    }

    @Test
    void missingComplexityClass_rejected() {
        // 缺大写
        assertThrows(BusinessException.class, () -> PasswordPolicy.validate("u", "weak1!pass"));
        // 缺小写
        assertThrows(BusinessException.class, () -> PasswordPolicy.validate("u", "WEAK1!PASS"));
        // 缺数字
        assertThrows(BusinessException.class, () -> PasswordPolicy.validate("u", "Weak!Pass"));
        // 缺特殊字符
        BusinessException e = assertThrows(BusinessException.class,
                () -> PasswordPolicy.validate("u", "Weak1Pass"));
        assertTrue(e.getMessage().contains("特殊字符"));
    }

    @Test
    void weakPasswordDictionary_rejected_ignoreCase() {
        assertThrows(BusinessException.class, () -> PasswordPolicy.validate("u", "Password123"));
        assertThrows(BusinessException.class, () -> PasswordPolicy.validate("u", "Admin123"));
        assertThrows(BusinessException.class, () -> PasswordPolicy.validate("u", "Passw0rd!"));
        // 「过于常见」话术断言：须用本身满足复杂度四要素的字典词（否则先撞复杂度规则）
        BusinessException e = assertThrows(BusinessException.class,
                () -> PasswordPolicy.validate("u", "P@ssw0rd"));
        assertTrue(e.getMessage().contains("过于常见"));
    }

    @Test
    void sameAsUsername_rejected_ignoreCase() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> PasswordPolicy.validate("ZhangSan", "zhangsAN"));
        assertTrue(e.getMessage().contains("不能与用户名相同"));
        // 用户名是密码子串不拒（策略只管相同）
        assertDoesNotThrow(() -> PasswordPolicy.validate("zhang", "Zhang0!x"));
    }

    @Test
    void overBcrypt72Bytes_rejected() {
        // 24 个汉字 = 72 字节 → 过；25 个汉字 = 75 字节 → 拒（且含特殊/大小写/数字不影响：纯汉字无大小写，先撞字节上限）
        String ok72 = "汉".repeat(20) + "Aa1!"; // 60+4=64 字节
        assertDoesNotThrow(() -> PasswordPolicy.validate("u", ok72));
        String over = "汉".repeat(25) + "Aa1!"; // 75+4=79 字节
        BusinessException e = assertThrows(BusinessException.class,
                () -> PasswordPolicy.validate("u", over));
        assertTrue(e.getMessage().contains("72字节"));
    }
}
