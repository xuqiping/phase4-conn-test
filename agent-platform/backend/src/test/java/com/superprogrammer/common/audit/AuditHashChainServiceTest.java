package com.superprogrammer.common.audit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditHashChainService 单测（安全体系 S2 · D1，SEC-FR-040）。
 * 覆盖：密钥 fail-fast、GENESIS 起点、链式 prev 传递、单行重算比对、篡改证伪。
 */
class AuditHashChainServiceTest {

    private static final String KEY = "testAuditHmacKeyForTestingPurposesOnly0123456789";

    private final AuditLogMapper mapper = mock(AuditLogMapper.class);
    private final AuditHashChainService service = new AuditHashChainService(mapper, KEY);

    private static AuditLogEntity row(String action) {
        AuditLogEntity r = new AuditLogEntity();
        r.setTraceId("t-1");
        r.setUserId(42L);
        r.setUsername("alice");
        r.setModule("auth");
        r.setAction(action);
        r.setDetailJson("{\"a\":1}");
        r.setClientIp("10.0.0.1");
        r.setResult(AuditLogEntity.RESULT_SUCCESS);
        return r;
    }

    @Test
    void blankKeyRefusesStartup() {
        AuditHashChainService s = new AuditHashChainService(mapper, "  ");
        assertThatThrownBy(s::validateKey).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shortKeyRefusesStartup() {
        AuditHashChainService s = new AuditHashChainService(mapper, "too-short");
        assertThatThrownBy(s::validateKey).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validKeyPasses() {
        assertThatCode(service::validateKey).doesNotThrowAnyException();
    }

    @Test
    void firstRowUsesGenesisPrevHash() {
        when(mapper.selectLastRecordHash()).thenReturn(null);
        AuditLogEntity r = row("login");

        service.insertChained(r);

        verify(mapper).advisoryLock(AuditHashChainService.ADVISORY_LOCK_KEY);
        verify(mapper).insert(r);
        assertThat(r.getPrevHash()).isEqualTo(AuditHashChainService.GENESIS);
        assertThat(r.getRecordHash()).hasSize(64);
        // created_at 显式赋值（DB DEFAULT 在插入后才确定，哈希须覆盖确定值）
        assertThat(r.getCreatedAt()).isNotNull();
    }

    @Test
    void secondRowChainsToFirstRecordHash() {
        AuditLogEntity first = row("login");
        when(mapper.selectLastRecordHash()).thenReturn(null);
        service.insertChained(first);

        AuditLogEntity second = row("logout");
        when(mapper.selectLastRecordHash()).thenReturn(first.getRecordHash());
        service.insertChained(second);

        assertThat(second.getPrevHash()).isEqualTo(first.getRecordHash());
        assertThat(second.getRecordHash()).isNotEqualTo(first.getRecordHash());
    }

    @Test
    void matchesVerifiesProducedRow() {
        when(mapper.selectLastRecordHash()).thenReturn(null);
        AuditLogEntity r = row("login");
        service.insertChained(r);
        assertThat(service.matches(r)).isTrue();
    }

    @Test
    void tamperedRowFailsVerification() {
        when(mapper.selectLastRecordHash()).thenReturn(null);
        AuditLogEntity r = row("login");
        service.insertChained(r);

        // 篡改任一字段 → 重算不再匹配
        r.setUsername("mallory");
        assertThat(service.matches(r)).isFalse();
    }

    @Test
    void canonicalIsFieldOrderSensitive() {
        AuditLogEntity a = row("login");
        a.setCreatedAt(OffsetDateTime.parse("2026-08-10T00:00:00Z"));
        AuditLogEntity b = row("login");
        b.setCreatedAt(a.getCreatedAt());
        b.setUserId(43L);
        assertThat(AuditHashChainService.canonical(a)).isNotEqualTo(AuditHashChainService.canonical(b));
    }

    @Test
    void insertCapturesRowContentInHash() {
        when(mapper.selectLastRecordHash()).thenReturn(null);
        service.insertChained(row("login"));
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getRecordHash()).isNotBlank();
    }

    @Test
    void canonicalJsonToleratesPgJsonbNormalization() {
        // PG jsonb 落库会键重排+冒号加空格：两侧解析成同一棵树 → 规范化串一致（否则校验恒断链）
        assertThat(AuditHashChainService.canonicalJson("{\"b\":1,\"a\":\"x\"}"))
                .isEqualTo(AuditHashChainService.canonicalJson("{\"a\": \"x\", \"b\": 1}"));
    }

    @Test
    void canonicalJsonKeepsNumberPrecision() {
        assertThat(AuditHashChainService.canonicalJson("{\"v\":0.57}"))
                .isEqualTo(AuditHashChainService.canonicalJson("{\"v\": 0.57}"));
        // 0.57 vs 0.58 必须可区分（防 stripTrailingZeros 之类误并）
        assertThat(AuditHashChainService.canonicalJson("{\"v\":0.57}"))
                .isNotEqualTo(AuditHashChainService.canonicalJson("{\"v\": 0.58}"));
    }

    @Test
    void canonicalJsonNonJsonPassthrough() {
        assertThat(AuditHashChainService.canonicalJson("not-json")).isEqualTo("not-json");
        assertThat(AuditHashChainService.canonicalJson(null)).isEmpty();
        assertThat(AuditHashChainService.canonicalJson("  ")).isEmpty();
    }

    @Test
    void createdAtWrittenAsUtcMicros() {
        // pgjdbc 读回 timestamptz 恒 UTC+微秒精度，写入侧必须同型归一
        when(mapper.selectLastRecordHash()).thenReturn(null);
        AuditLogEntity r = row("login");
        service.insertChained(r);
        assertThat(r.getCreatedAt().getOffset()).isEqualTo(java.time.ZoneOffset.UTC);
        assertThat(r.getCreatedAt().getNano() % 1000).isZero();
    }
}
