// agent-platform/backend/src/test/java/com/superprogrammer/common/security/rule/ColdRulesATest.java
package com.superprogrammer.common.security.rule;

import com.superprogrammer.common.security.SecurityEventTypes;
import com.superprogrammer.common.security.event.ApplicationSecurityEvent;
import com.superprogrammer.system.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.SetOperations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 冷规则单测 A（11x 加固 P3-C9）：IDOR / IMPOSSIBLE_TRAVEL / OFF_HOURS / TOKEN_REUSE / PRIVILEGE_CHANGE。
 */
@ExtendWith(MockitoExtension.class)
class ColdRulesATest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private SystemSettingService settings;

    private final Object src = new Object();

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
        lenient().when(settings.getLong(anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
    }

    private ApplicationSecurityEvent evt(String kind, Long uid, Map<String, Object> payload) {
        return ApplicationSecurityEvent.of(src, kind, uid, "1.2.3.4", payload);
    }

    // ---- IdorProbeRule ----

    @Test
    void idor_underThreshold_noHit() {
        when(valueOps.increment("sec:rule:403:u:42", 1)).thenReturn(5L);
        IdorProbeRule rule = new IdorProbeRule(redisTemplate, settings);

        assertNull(rule.evaluate(evt(ApplicationSecurityEvent.KIND_AUTHZ_DENIED, 42L, Map.of("uri", "/api/x"))));
    }

    @Test
    void idor_overThreshold_medium() {
        when(valueOps.increment("sec:rule:403:u:42", 1)).thenReturn(10L);
        IdorProbeRule rule = new IdorProbeRule(redisTemplate, settings);

        SecurityRule.Verdict v = rule.evaluate(evt(ApplicationSecurityEvent.KIND_AUTHZ_DENIED, 42L, Map.of("uri", "/api/x")));

        assertNotNull(v);
        assertEquals(SecurityEventTypes.IDOR_PROBE, v.eventType());
        assertEquals(SecurityEventTypes.SEV_MEDIUM, v.severity());
        assertEquals(SecurityEventTypes.ACT_NONE, v.autoAction());
    }

    @Test
    void idor_anonymous_usesIpDimension() {
        when(valueOps.increment("sec:rule:403:ip:1.2.3.4", 1)).thenReturn(11L);
        IdorProbeRule rule = new IdorProbeRule(redisTemplate, settings);

        assertNotNull(rule.evaluate(evt(ApplicationSecurityEvent.KIND_AUTHZ_DENIED, null, Map.of())));
    }

    @Test
    void idor_redisDown_noHit() {
        when(valueOps.increment(anyString(), anyLong())).thenThrow(new RuntimeException("redis down"));
        IdorProbeRule rule = new IdorProbeRule(redisTemplate, settings);

        assertNull(rule.evaluate(evt(ApplicationSecurityEvent.KIND_AUTHZ_DENIED, 42L, Map.of())));
    }

    // ---- ImpossibleTravelRule ----

    @Test
    void travel_sameGeo_noHit() {
        when(valueOps.get("sec:rule:lastlogin:42")).thenReturn("中国|上海|上海|浦东|电信|" + (System.currentTimeMillis() / 1000));
        ImpossibleTravelRule rule = new ImpossibleTravelRule(redisTemplate, settings);
        String geo = "中国|上海|上海|浦东|电信";

        assertNull(rule.evaluate(evt(ApplicationSecurityEvent.KIND_LOGIN_SUCCESS, 42L, Map.of("geo", geo))));
    }

    @Test
    void travel_differentGeoWithin2h_hits() {
        long nowSec = System.currentTimeMillis() / 1000;
        when(valueOps.get("sec:rule:lastlogin:42")).thenReturn("中国|北京|" + (nowSec - 600));
        ImpossibleTravelRule rule = new ImpossibleTravelRule(redisTemplate, settings);

        SecurityRule.Verdict v = rule.evaluate(evt(ApplicationSecurityEvent.KIND_LOGIN_SUCCESS, 42L,
                Map.of("geo", "美国|纽约")));

        assertNotNull(v);
        assertEquals(SecurityEventTypes.IMPOSSIBLE_TRAVEL, v.eventType());
        assertEquals(SecurityEventTypes.SEV_MEDIUM, v.severity());
    }

    @Test
    void travel_differentGeoLongAgo_noHit() {
        long nowSec = System.currentTimeMillis() / 1000;
        when(valueOps.get("sec:rule:lastlogin:42")).thenReturn("中国|北京|" + (nowSec - 5 * 3600));
        ImpossibleTravelRule rule = new ImpossibleTravelRule(redisTemplate, settings);

        assertNull(rule.evaluate(evt(ApplicationSecurityEvent.KIND_LOGIN_SUCCESS, 42L, Map.of("geo", "美国|纽约"))));
    }

    @Test
    void travel_firstLogin_noHitButCaches() {
        when(valueOps.get("sec:rule:lastlogin:42")).thenReturn(null);
        ImpossibleTravelRule rule = new ImpossibleTravelRule(redisTemplate, settings);

        assertNull(rule.evaluate(evt(ApplicationSecurityEvent.KIND_LOGIN_SUCCESS, 42L, Map.of("geo", "中国|北京"))));
        verify(valueOps).set(eq("sec:rule:lastlogin:42"), contains("中国|北京|"), anyLong(), any());
    }

    @Test
    void travel_blankGeo_skipped() {
        ImpossibleTravelRule rule = new ImpossibleTravelRule(redisTemplate, settings);

        assertNull(rule.evaluate(evt(ApplicationSecurityEvent.KIND_LOGIN_SUCCESS, 42L, Map.of("geo", ""))));
    }

    // ---- TokenReuseRule ----

    @Test
    void tokenReuse_underThreshold_noHit() {
        when(setOps.add(eq("sec:rule:ips:42"), eq("1.2.3.4"))).thenReturn(1L);
        when(setOps.size("sec:rule:ips:42")).thenReturn(2L);
        TokenReuseRule rule = new TokenReuseRule(redisTemplate, settings);

        assertNull(rule.evaluate(evt(ApplicationSecurityEvent.KIND_LOGIN_SUCCESS, 42L, Map.of())));
    }

    @Test
    void tokenReuse_threeIps_hits() {
        when(setOps.add(eq("sec:rule:ips:42"), eq("1.2.3.4"))).thenReturn(1L);
        when(setOps.size("sec:rule:ips:42")).thenReturn(3L);
        TokenReuseRule rule = new TokenReuseRule(redisTemplate, settings);

        SecurityRule.Verdict v = rule.evaluate(evt(ApplicationSecurityEvent.KIND_LOGIN_SUCCESS, 42L, Map.of()));

        assertNotNull(v);
        assertEquals(SecurityEventTypes.TOKEN_REUSE, v.eventType());
        assertEquals(SecurityEventTypes.SEV_MEDIUM, v.severity());
    }

    // ---- PrivilegeChangeRule ----

    @Test
    void privilegeChange_alwaysHits() {
        PrivilegeChangeRule rule = new PrivilegeChangeRule(redisTemplate, settings);

        SecurityRule.Verdict v = rule.evaluate(evt(ApplicationSecurityEvent.KIND_PRIVILEGE_CHANGE, 1L,
                Map.of("action", "role:update", "targetType", "role", "targetId", "7")));

        assertNotNull(v);
        assertEquals(SecurityEventTypes.PRIVILEGE_CHANGE, v.eventType());
        assertEquals(SecurityEventTypes.SEV_HIGH, v.severity());
        assertTrue(v.detailJson().contains("role:update"));
    }

    // ---- OffHoursSensitiveRule（时间相关：只测 supports + 命中分支结构） ----

    @Test
    void offHours_supportsPrivilegeKindOnly() {
        OffHoursSensitiveRule rule = new OffHoursSensitiveRule(redisTemplate, settings);

        assertTrue(rule.supports(ApplicationSecurityEvent.KIND_PRIVILEGE_CHANGE));
        assertFalse(rule.supports(ApplicationSecurityEvent.KIND_CHAT_MESSAGE));
    }

    @Test
    void offHours_resultDependsOnClock() {
        OffHoursSensitiveRule rule = new OffHoursSensitiveRule(redisTemplate, settings);
        SecurityRule.Verdict v = rule.evaluate(evt(ApplicationSecurityEvent.KIND_PRIVILEGE_CHANGE, 1L,
                Map.of("action", "billing:pricing")));
        int hour = java.time.LocalTime.now().getHour();
        if (hour >= 0 && hour < 6) {
            assertNotNull(v);
            assertEquals(SecurityEventTypes.OFF_HOURS_SENSITIVE, v.eventType());
            assertEquals(SecurityEventTypes.SEV_LOW, v.severity());
        } else {
            assertNull(v);
        }
    }

    @Test
    void offHours_detailRetainsTargetFields() {
        // 13x-1：凌晨敏感操作 detail 必须带 targetType/targetId——详情页要能看到「对谁做了什么」。
        String detail = OffHoursSensitiveRule.buildDetail(
                Map.of("action", "user:update_status", "targetType", "user", "targetId", "42"),
                java.time.LocalTime.of(2, 30));
        assertTrue(detail.contains("user:update_status"));
        assertTrue(detail.contains("\"targetType\":\"user\""));
        assertTrue(detail.contains("\"targetId\":\"42\""));
        assertTrue(detail.contains("02:30"));
    }
}
