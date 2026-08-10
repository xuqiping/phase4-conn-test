package com.superprogrammer.common.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 审计链校验 + 外部锚定服务（安全体系 S2 · D3/D4，SEC-FR-042/043）。
 *
 * <p>D3 校验：全量逐行重算比对（当前量级全量 MVP，量大后改「从上次锚点行起算」增量）。
 * 断链 → ERROR 日志 + 安全审计（module=audit/action=chain_broken/FAIL），<b>只告警不修复</b>
 * （修账必须人工，校验程序自身不能成为篡改面——同 BillingReconcileService 铁律）。
 * 定时每日 03:40（错开 S1 对账 03:20）。
 *
 * <p>D4 外部锚定：校验通过则把当日末行 record_hash 追加写 logs/audit-anchor.log——
 * 即使整库被拖后重算假链，锚定文件（随备份体系 sync-offsite 异地）可证伪。
 */
@Slf4j
@Service
public class AuditChainVerifyService {

    /** 链校验结果。ok=false 时 firstBrokenId/breakReason 指向首个断点。 */
    public record ChainVerifyResult(boolean ok, long totalRows, long legacyRows, long chainedRows,
                                    Long firstBrokenId, String breakReason,
                                    Long lastRowId, String lastRecordHash) {
        static ChainVerifyResult broken(long total, long legacy, long chained, Long brokenId, String reason,
                                        Long lastRowId, String lastHash) {
            return new ChainVerifyResult(false, total, legacy, chained, brokenId, reason, lastRowId, lastHash);
        }

        static ChainVerifyResult ok(long total, long legacy, long chained, Long lastRowId, String lastHash) {
            return new ChainVerifyResult(true, total, legacy, chained, null, null, lastRowId, lastHash);
        }
    }

    private final AuditLogMapper auditLogMapper;
    private final AuditHashChainService chainService;
    private final AuditLogService auditLogService;
    private final Path anchorFile;

    public AuditChainVerifyService(AuditLogMapper auditLogMapper,
                                   AuditHashChainService chainService,
                                   AuditLogService auditLogService,
                                   @Value("${audit.chain.anchor-file:logs/audit-anchor.log}") String anchorFile) {
        this.auditLogMapper = auditLogMapper;
        this.chainService = chainService;
        this.auditLogService = auditLogService;
        this.anchorFile = Path.of(anchorFile);
    }

    /** 每日定时校验（03:40，错开 S1 对账 03:20）。 */
    @Scheduled(cron = "${audit.chain.verify.cron:0 40 3 * * *}")
    public void verifyDaily() {
        verifyAndAnchor();
    }

    /** 校验 + 按结果锚定/告警（admin 手动端点也走此入口）。 */
    public ChainVerifyResult verifyAndAnchor() {
        ChainVerifyResult result = verify();
        if (result.ok()) {
            appendAnchor(result);
            log.info("审计链校验通过 total={} legacy={} chained={} lastId={}",
                    result.totalRows(), result.legacyRows(), result.chainedRows(), result.lastRowId());
        } else {
            // 断链：ERROR + 安全审计留痕（新审计行本身链式写入，不影响判定）
            log.error("审计链断裂! brokenId={} reason={} total={} chained={}",
                    result.firstBrokenId(), result.breakReason(), result.totalRows(), result.chainedRows());
            try {
                auditLogService.record(auditLogService.fromMdc("audit", "chain_broken", "audit_logs",
                        String.valueOf(result.firstBrokenId()),
                        "{\"reason\":\"" + result.breakReason() + "\"}", AuditLogEntity.RESULT_FAIL));
            } catch (Exception e) {
                log.warn("断链审计写入失败(已吞): {}", e.toString());
            }
        }
        return result;
    }

    /**
     * 全量逐行校验：链外行（NULL 哈希）只许构成连续前缀；链上行校验 prev_hash 衔接 + 单行重算。
     */
    public ChainVerifyResult verify() {
        List<AuditLogEntity> rows = auditLogMapper.selectList(
                new LambdaQueryWrapper<AuditLogEntity>().orderByAsc(AuditLogEntity::getId));
        boolean legacyPrefix = true;
        String prevExpected = AuditHashChainService.GENESIS;
        long legacy = 0;
        long chained = 0;
        Long lastRowId = null;
        String lastHash = null;
        for (AuditLogEntity row : rows) {
            if (row.getRecordHash() == null) {
                if (!legacyPrefix) {
                    return ChainVerifyResult.broken(rows.size(), legacy, chained, row.getId(),
                            "链上行之后出现无哈希行（疑似删行/插行）", lastRowId, lastHash);
                }
                legacy++;
                continue;
            }
            legacyPrefix = false;
            if (!prevExpected.equals(row.getPrevHash())) {
                return ChainVerifyResult.broken(rows.size(), legacy, chained, row.getId(),
                        "prev_hash 衔接断裂（疑似删行/重排）", lastRowId, lastHash);
            }
            if (!chainService.matches(row)) {
                return ChainVerifyResult.broken(rows.size(), legacy, chained, row.getId(),
                        "record_hash 重算不匹配（行内容被篡改）", lastRowId, lastHash);
            }
            prevExpected = row.getRecordHash();
            chained++;
            lastRowId = row.getId();
            lastHash = row.getRecordHash();
        }
        return ChainVerifyResult.ok(rows.size(), legacy, chained, lastRowId, lastHash);
    }

    /** D4：校验通过 → 末行哈希追加锚定文件（失败只 ERROR，不反转校验结论）。 */
    private void appendAnchor(ChainVerifyResult result) {
        if (result.lastRowId() == null) {
            return; // 无链上行，不锚空链
        }
        String line = OffsetDateTime.now() + " lastRowId=" + result.lastRowId()
                + " recordHash=" + result.lastRecordHash() + System.lineSeparator();
        try {
            if (anchorFile.getParent() != null) {
                Files.createDirectories(anchorFile.getParent());
            }
            Files.writeString(anchorFile, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("审计链锚定文件写入失败 anchorFile={} : {}", anchorFile, e.toString());
        }
    }
}
