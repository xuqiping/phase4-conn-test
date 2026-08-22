package com.superprogrammer.auth.service.mail;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** MailSendQuotaService 单测（12x B3）：IP 小时窗 + 全局日封顶 + Redis 故障降级。 */
@ExtendWith(MockitoExtension.class)
class MailSendQuotaServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SystemSettingService systemSettingService;

    private MailSendQuotaService service;

    @BeforeEach
    void setUp() {
        service = new MailSendQuotaService(redisTemplate, systemSettingService);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void checkIpHourly_underLimit_passes() {
        when(valueOps.increment("mailsend:ip:1.2.3.4")).thenReturn(3L);
        assertDoesNotThrow(() -> service.checkIpHourly("1.2.3.4"));
    }

    @Test
    void checkIpHourly_overLimit_throwsRateLimit() {
        when(valueOps.increment("mailsend:ip:1.2.3.4")).thenReturn(11L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.checkIpHourly("1.2.3.4"));
        assertEquals(ErrorCode.RATE_LIMIT.getCode(), ex.getCode());
    }

    @Test
    void checkIpHourly_blankIp_skips() {
        assertDoesNotThrow(() -> service.checkIpHourly("  "));
    }

    @Test
    void tryConsumeDaily_underCap_true() {
        when(systemSettingService.getLong(MailSendQuotaService.KEY_DAILY_CAP, 500)).thenReturn(500L);
        when(valueOps.increment(startsWith("mailsend:daily:"))).thenReturn(100L);
        assertTrue(service.tryConsumeDaily());
    }

    @Test
    void tryConsumeDaily_overCap_false() {
        when(systemSettingService.getLong(MailSendQuotaService.KEY_DAILY_CAP, 500)).thenReturn(500L);
        when(valueOps.increment(startsWith("mailsend:daily:"))).thenReturn(501L);
        assertFalse(service.tryConsumeDaily());
    }

    @Test
    void tryConsumeDaily_customCap_respected() {
        when(systemSettingService.getLong(MailSendQuotaService.KEY_DAILY_CAP, 500)).thenReturn(5L);
        when(valueOps.increment(startsWith("mailsend:daily:"))).thenReturn(6L);
        assertFalse(service.tryConsumeDaily());
    }

    @Test
    void redisDown_degradesToAllow() {
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("redis down"));
        assertDoesNotThrow(() -> service.checkIpHourly("1.2.3.4"));
        assertTrue(service.tryConsumeDaily());
    }
}
