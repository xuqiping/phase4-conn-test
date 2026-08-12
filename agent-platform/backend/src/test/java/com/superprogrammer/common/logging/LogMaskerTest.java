package com.superprogrammer.common.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脱敏规则单测（LOG-FR-07 / 安全检查：五类敏感信息正则生效）。
 * 每类一条正向用例 + 误伤边界（普通数字/普通文本不动）。
 */
class LogMaskerTest {

    @Test
    void masksMobilePhone() {
        assertThat(LogMasker.mask("用户手机 13812348000 已登记"))
                .isEqualTo("用户手机 138****8000 已登记");
    }

    @Test
    void masksBankCard() {
        // P0 验收用例：测试卡号 6228... 输出打码
        assertThat(LogMasker.mask("card=6228480402564890018"))
                .isEqualTo("card=6228****0018");
        // 16 位
        assertThat(LogMasker.mask("卡号 6225880212345678")).isEqualTo("卡号 6225****5678");
    }

    @Test
    void masksIdCard() {
        assertThat(LogMasker.mask("身份证 110101199003074321"))
                .isEqualTo("身份证 110101********4321");
        // 末位 X
        assertThat(LogMasker.mask("id=11010119900307432X"))
                .isEqualTo("id=110101********432X");
    }

    @Test
    void masksEmail() {
        assertThat(LogMasker.mask("邮箱 zhangsan@example.com 注册"))
                .isEqualTo("邮箱 z***@example.com 注册");
    }

    @Test
    void masksApiKeyAndBearer() {
        assertThat(LogMasker.mask("apiKey=sk-abcdef123456")).isEqualTo("apiKey=****");
        assertThat(LogMasker.mask("token: eyJhbGciOiJ9")).isEqualTo("token: ****");
        assertThat(LogMasker.mask("password = hunter2xyz")).isEqualTo("password = ****");
        assertThat(LogMasker.mask("Authorization Bearer eyJhbGciOiJ9.sig"))
                .isEqualTo("Authorization Bearer ****");
    }

    @Test
    void leavesOrdinaryTextUntouched() {
        // 短数字、价格、traceId、普通日志行不脱敏
        assertThat(LogMasker.mask("cost=0.000568 tokens=496 userId=42")).isEqualTo("cost=0.000568 tokens=496 userId=42");
        assertThat(LogMasker.mask("trace-abc123 处理完成")).isEqualTo("trace-abc123 处理完成");
        assertThat(LogMasker.mask(null)).isNull();
        assertThat(LogMasker.mask("")).isEmpty();
    }
}
