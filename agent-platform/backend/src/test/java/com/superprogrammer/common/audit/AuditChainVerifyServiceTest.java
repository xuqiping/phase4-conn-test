package com.superprogrammer.common.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AuditChainVerifyService 单测（安全体系 S2 · D3/D4，SEC-FR-042/043）。
 * 链上行由真实 AuditHashChainService 产出（mock mapper 捕获），保证校验与写入同源。
 */
class AuditChainVerifyServiceTest {

    private static final String KEY = "testAuditHmacKeyForTestingPurposesOnly0123456789";

    private final AuditLogMapper mapper = mock(AuditLogMapper.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AuditHashChainService chainService = new AuditHashChainService(mapper, KEY);

    @TempDir
    Path tempDir;

    private AuditChainVerifyService verifyService;

    @BeforeEach
    void setUp() {
        verifyService = new AuditChainVerifyService(mapper, chainService, auditLogService,
                tempDir.resolve("audit-anchor.log").toString());
    }

    /** 用真实链服务顺序产 n 条链上行（id 手工补 1..n，模拟 DB IDENTITY 回填）。 */
    private List<AuditLogEntity> chainedRows(int n) {
        List<AuditLogEntity> rows = new ArrayList<>();
        String[] last = {null};
        for (int i = 1; i <= n; i++) {
            when(mapper.selectLastRecordHash()).thenReturn(last[0]);
            AuditLogEntity r = new AuditLogEntity();
            r.setTraceId("t-" + i);
            r.setUserId((long) i);
            r.setUsername("u" + i);
            r.setModule("auth");
            r.setAction("login");
            r.setDetailJson("{\"idx\":" + i + "}");
            r.setResult(AuditLogEntity.RESULT_SUCCESS);
            chainService.insertChained(r);
            r.setId((long) i);
            last[0] = r.getRecordHash();
            rows.add(r);
        }
        return rows;
    }

    private static AuditLogEntity legacyRow(long id) {
        AuditLogEntity r = new AuditLogEntity();
        r.setId(id);
        r.setModule("auth");
        r.setAction("login");
        return r; // prev/record_hash 均为 null = 链外存量行
    }

    @SuppressWarnings("unchecked")
    private void givenRows(List<AuditLogEntity> rows) {
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(rows);
    }

    @Test
    void emptyTableVerifiesOk() {
        givenRows(List.of());
        AuditChainVerifyService.ChainVerifyResult r = verifyService.verify();
        assertThat(r.ok()).isTrue();
        assertThat(r.totalRows()).isZero();
    }

    @Test
    void legacyOnlyPrefixVerifiesOk() {
        givenRows(List.of(legacyRow(1), legacyRow(2)));
        AuditChainVerifyService.ChainVerifyResult r = verifyService.verify();
        assertThat(r.ok()).isTrue();
        assertThat(r.legacyRows()).isEqualTo(2);
        assertThat(r.chainedRows()).isZero();
    }

    @Test
    void legacyPlusValidChainVerifiesOk() {
        List<AuditLogEntity> rows = new ArrayList<>();
        rows.add(legacyRow(1));
        List<AuditLogEntity> chained = chainedRows(3);
        chained.forEach(r -> r.setId(r.getId() + 1)); // id 2..4，衔接存量行之后
        rows.addAll(chained);
        givenRows(rows);

        AuditChainVerifyService.ChainVerifyResult r = verifyService.verify();
        assertThat(r.ok()).isTrue();
        assertThat(r.legacyRows()).isEqualTo(1);
        assertThat(r.chainedRows()).isEqualTo(3);
        assertThat(r.lastRowId()).isEqualTo(4L);
    }

    @Test
    void tamperedRowBreaksAtThatId() {
        List<AuditLogEntity> rows = chainedRows(3);
        rows.get(1).setUsername("mallory"); // 篡改第 2 行
        givenRows(rows);

        AuditChainVerifyService.ChainVerifyResult r = verifyService.verify();
        assertThat(r.ok()).isFalse();
        assertThat(r.firstBrokenId()).isEqualTo(2L);
        assertThat(r.breakReason()).contains("篡改");
    }

    @Test
    void deletedMiddleRowBreaksLinkage() {
        List<AuditLogEntity> rows = chainedRows(3);
        rows.remove(1); // 删掉第 2 行 → 第 3 行 prev_hash 指向不存在的哈希
        givenRows(rows);

        AuditChainVerifyService.ChainVerifyResult r = verifyService.verify();
        assertThat(r.ok()).isFalse();
        assertThat(r.firstBrokenId()).isEqualTo(3L);
        assertThat(r.breakReason()).contains("prev_hash");
    }

    @Test
    void hashlessRowAfterChainedBreaks() {
        List<AuditLogEntity> rows = new ArrayList<>(chainedRows(2));
        rows.add(legacyRow(99)); // 链上行之后出现无哈希行
        givenRows(rows);

        AuditChainVerifyService.ChainVerifyResult r = verifyService.verify();
        assertThat(r.ok()).isFalse();
        assertThat(r.firstBrokenId()).isEqualTo(99L);
    }

    @Test
    void anchorAppendedOnSuccess() {
        givenRows(chainedRows(2));
        AuditChainVerifyService.ChainVerifyResult r = verifyService.verifyAndAnchor();

        assertThat(r.ok()).isTrue();
        assertThat(tempDir.resolve("audit-anchor.log")).exists();
        try {
            String content = Files.readString(tempDir.resolve("audit-anchor.log"));
            assertThat(content).contains("lastRowId=2").contains("recordHash=" + r.lastRecordHash());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void anchorSkippedOnBroken() {
        List<AuditLogEntity> rows = chainedRows(2);
        rows.get(0).setAction("tampered");
        givenRows(rows);

        AuditChainVerifyService.ChainVerifyResult r = verifyService.verifyAndAnchor();
        assertThat(r.ok()).isFalse();
        assertThat(tempDir.resolve("audit-anchor.log")).doesNotExist();
    }
}
