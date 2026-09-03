package com.superprogrammer.knowledge.connector;

import com.superprogrammer.knowledge.connector.KnowledgeConnectorSpi.ExternalDoc;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentVersionVO;
import com.superprogrammer.knowledge.dto.KnowledgeDocumentVO;
import com.superprogrammer.knowledge.entity.KnowledgeConnector;
import com.superprogrammer.knowledge.entity.KnowledgeConnectorDoc;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.service.KnowledgeDocumentService;
import com.superprogrammer.knowledge.service.KnowledgeDocumentVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * C6 连接器定时同步 worker（WP6 Step3，规格 §8.2）。
 *
 * <p>轮询（默认 60s，{@code knowledge.connector.poll-ms}）→ cron 到期判定（每连接器自带
 * Spring 六段 cron）→ {@link ConnectorSyncTxService#tryClaim} 认领（FOR UPDATE SKIP LOCKED，
 * 双节点安全）→ list() 全量枚举对账本 etag 差分 → 新增/变更走<strong>与手工上传完全相同的
 * 管线</strong>（store 白名单/magic 嗅探/配额/版本链/解析/索引 job），源端消失按
 * sync_on_source_delete 开关 ISOLATED（默认）或治理删除。
 *
 * <p>预算与限速：单轮 ≤50 个文档动作（新增/变更/复活/重试，spec 性能表）；网络侧由
 * {@link FetchLimiter}（1 req/s+200MB）限。cron 抖动错峰：到期时刻叠加确定性偏移
 * [0,600)s（id 散列），多连接器同 cron 不齐发。
 *
 * <p>计费归户：新文档 created_by=连接器创建者（spec §8.3），解析线程 BillingContext
 * 由 DocumentParserService 自种。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorSyncWorker {

    static final int MAX_ACTIONS_PER_ROUND = 50;
    private static final String DEFAULT_CRON = "0 0 4 * * *";

    private final ConnectorSyncTxService txService;
    private final ConnectorFactory factory;
    private final KnowledgeDocumentService documentService;
    private final KnowledgeDocumentVersionService versionService;

    @Scheduled(fixedDelayString = "${knowledge.connector.poll-ms:60000}")
    public void pollOnce() {
        for (KnowledgeConnector connector : txService.listEnabled()) {
            if (!isDue(connector)) {
                continue;
            }
            if (txService.tryClaim(connector.getId(), connector.getLastSyncAt())) {
                syncConnector(connector);
            }
        }
    }

    /** 单连接器一轮同步。轮级异常（连不上/枚举失败）→ recordError 计连续错误；文档级失败计入摘要不中断。 */
    void syncConnector(KnowledgeConnector connector) {
        KnowledgeConnectorSpi spi = null;
        try {
            spi = factory.build(connector);
            Map<String, ExternalDoc> external = new HashMap<>();
            for (ExternalDoc doc : spi.list()) {
                external.putIfAbsent(doc.externalId(), doc);
            }
            syncRound(connector, spi, external);
        } catch (Exception e) {
            log.warn("连接器同步轮失败 connectorId={}: {}", connector.getId(), e.getMessage());
            txService.recordError(connector.getId(), sanitize(e.getMessage()));
        } finally {
            if (spi != null) {
                try {
                    spi.close();
                } catch (Exception ignore) {
                    // 关闭失败不影响记账（HTTP 连接随 GC 回收）
                }
            }
        }
    }

    private void syncRound(KnowledgeConnector connector, KnowledgeConnectorSpi spi,
                           Map<String, ExternalDoc> external) {
        Long creator = connector.getCreatedBy();
        boolean syncDelete = Boolean.TRUE.equals(connector.getSyncOnSourceDelete());
        RoundCounters c = new RoundCounters();
        List<KnowledgeConnectorDoc> mappings = txService.listMappings(connector.getId());
        Map<String, KnowledgeConnectorDoc> byExternal = new HashMap<>();
        for (KnowledgeConnectorDoc m : mappings) {
            byExternal.putIfAbsent(m.getExternalId(), m);
        }

        // ---- 源端消失（无网络成本，先结算）----
        for (KnowledgeConnectorDoc m : mappings) {
            if (Boolean.TRUE.equals(m.getManualDeleted()) || external.containsKey(m.getExternalId())) {
                continue;
            }
            if (m.getDocId() == null) {
                txService.removeMapping(m.getId());   // 从未成功拉取的占位行
                continue;
            }
            KnowledgeDocument doc = txService.getDocument(m.getDocId());
            if (doc == null) {
                // 本地文档已被用户删除 → 账本标记，永不复活（联动点表）
                txService.markManualDeleted(m.getId());
                c.skipped++;
                continue;
            }
            if (syncDelete) {
                documentService.delete(m.getDocId(), creator, false);   // 既有治理删除链（级联节点/向量/文件）
                txService.removeMapping(m.getId());
                c.deleted++;
            } else {
                txService.isolateDoc(m.getDocId(), ConnectorSyncTxService.ISOLATE_REASON_PREFIX
                        + ": " + m.getExternalId());
                c.isolated++;
            }
        }

        // ---- 源端在册：分类出待动作清单 ----
        List<Action> actions = new ArrayList<>();
        for (ExternalDoc ext : external.values()) {
            KnowledgeConnectorDoc m = byExternal.get(ext.externalId());
            if (m != null && Boolean.TRUE.equals(m.getManualDeleted())) {
                c.skipped++;   // 手工删除过：源端在也不复活
                continue;
            }
            if (m == null) {
                actions.add(Action.newDoc(ext));
                continue;
            }
            KnowledgeDocument doc = txService.getDocument(m.getDocId());
            if (doc == null) {
                txService.markManualDeleted(m.getId());
                c.skipped++;
                continue;
            }
            boolean quarantined = "QUARANTINED".equals(doc.getStatus());
            boolean isolatedByConnector = quarantined && doc.getQuarantineReason() != null
                    && doc.getQuarantineReason().startsWith(ConnectorSyncTxService.ISOLATE_REASON_PREFIX);
            if (quarantined && !isolatedByConnector) {
                // 安全隔离（注入扫描命中）只走管理员治理，连接器绝不动（含自动复活）
                c.errors++;
                c.notes.add("文档被安全隔离跳过 docId=" + m.getDocId());
                continue;
            }
            boolean etagChanged = !java.util.Objects.equals(m.getEtag(), ext.etag());
            if (!etagChanged) {
                if (isolatedByConnector) {
                    actions.add(Action.revive(ext, m, doc));   // 源恢复：复活重解析（无需抓取）
                } else if ("FAILED".equals(doc.getStatus())) {
                    actions.add(Action.retry(ext, m, doc));    // 解析失败自愈：同文件重解析
                } else {
                    c.skipped++;
                }
            } else {
                actions.add(Action.changed(ext, m, doc));      // 含 ISOLATED 且内容也变：走变更，reset 顺带清隔离
            }
        }

        // ---- 单轮预算闸 + 确定性顺序（externalId 排序，多轮消化超量）----
        actions.sort(Comparator.comparing(a -> a.ext().externalId()));
        int budget = MAX_ACTIONS_PER_ROUND;
        for (Action a : actions) {
            if (budget <= 0) {
                log.info("连接器单轮动作预算用尽，余量下轮继续 connectorId={}", connector.getId());
                break;
            }
            budget--;
            try {
                switch (a.kind()) {
                    case NEW -> {
                        KnowledgeDocumentVO vo = documentService.upload(connector.getKbId(),
                                new InMemoryMultipartFile(a.ext().displayName(),
                                        contentTypeFor(a.ext().displayName()), spi.fetch(a.ext())),
                                null, null, null, null, null, null, null, creator, false);
                        txService.insertMapping(connector.getId(), a.ext().externalId(),
                                a.ext().etag(), vo.getId());
                        c.added++;
                    }
                    case CHANGED -> {
                        byte[] bytes = spi.fetch(a.ext());
                        KnowledgeDocumentVersionVO version = documentService.createVersion(
                                a.doc().getId(),
                                new InMemoryMultipartFile(a.ext().displayName(),
                                        contentTypeFor(a.ext().displayName()), bytes),
                                a.doc().getCurrentVersionId(), "连接器同步：源端内容变更", creator, false);
                        versionService.activate(a.doc().getId(), version.getId(),
                                a.doc().getCurrentVersionId(), creator, false);
                        txService.resetDocForResync(a.doc().getId(), version.getFileRef(),
                                version.getSourceHash(), creator);
                        txService.touchMapping(a.mapping().getId(), a.ext().etag());
                        c.updated++;
                    }
                    case REVIVE -> {
                        txService.reviveDoc(a.doc().getId(), creator);
                        txService.touchMapping(a.mapping().getId(), a.ext().etag());
                        c.revived++;
                    }
                    case RETRY -> {
                        txService.resetDocForResync(a.doc().getId(), a.doc().getFileRef(),
                                a.doc().getFileHash(), creator);
                        txService.touchMapping(a.mapping().getId(), a.ext().etag());
                        c.retried++;
                    }
                }
            } catch (Exception e) {
                c.errors++;
                c.notes.add(sanitize(a.ext().externalId() + ": " + e.getMessage()));
            }
        }

        String summary = c.summary();
        boolean anyProgress = c.added + c.updated + c.revived + c.retried + c.isolated + c.deleted > 0;
        if (c.errors > 0 && !anyProgress) {
            // 全军覆没视同轮失败：计连续错误（防坏凭证空转刷摘要）
            txService.recordError(connector.getId(), summary);
        } else {
            txService.finishSuccess(connector.getId(), summary);
        }
        log.info("连接器同步完成 connectorId={} type={} {}", connector.getId(), connector.getType(), summary);
    }

    /** cron 到期判定 + 确定性抖动错峰（[0,600)s，id 散列，坑点表「同步风暴」）。 */
    boolean isDue(KnowledgeConnector connector) {
        try {
            String cronText = connector.getScheduleCron() == null || connector.getScheduleCron().isBlank()
                    ? DEFAULT_CRON : connector.getScheduleCron();
            CronExpression cron = CronExpression.parse(cronText);
            OffsetDateTime base = connector.getLastSyncAt() != null ? connector.getLastSyncAt()
                    : (connector.getCreatedAt() != null ? connector.getCreatedAt() : OffsetDateTime.now());
            OffsetDateTime next = cron.next(base);
            if (next == null) {
                return false;
            }
            return !next.plusSeconds(jitterSeconds(connector.getId())).isAfter(OffsetDateTime.now());
        } catch (Exception e) {
            log.warn("连接器 cron 非法，跳过本轮 connectorId={}: {}", connector.getId(), e.getMessage());
            return false;
        }
    }

    static long jitterSeconds(Long id) {
        return id == null ? 0 : Math.floorMod(id.hashCode(), 600);
    }

    /** 错误信息脱敏：剥 URL userinfo（user:pass@）、压缩空白、截断。凭证永不入日志/摘要。 */
    static String sanitize(String message) {
        if (message == null) {
            return "未知错误";
        }
        String cleaned = message.replaceAll("(?://)[^/@\\s]+@", "//***@");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 400 ? cleaned.substring(0, 400) : cleaned;
    }

    private static String contentTypeFor(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html") || n.endsWith(".htm")) {
            return "text/html";
        }
        if (n.endsWith(".md") || n.endsWith(".txt")) {
            return "text/plain";
        }
        if (n.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (n.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (n.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        return "application/octet-stream";
    }

    private enum Kind { NEW, CHANGED, REVIVE, RETRY }

    private record Action(Kind kind, ExternalDoc ext, KnowledgeConnectorDoc mapping, KnowledgeDocument doc) {
        static Action newDoc(ExternalDoc ext) {
            return new Action(Kind.NEW, ext, null, null);
        }

        static Action changed(ExternalDoc ext, KnowledgeConnectorDoc m, KnowledgeDocument doc) {
            return new Action(Kind.CHANGED, ext, m, doc);
        }

        static Action revive(ExternalDoc ext, KnowledgeConnectorDoc m, KnowledgeDocument doc) {
            return new Action(Kind.REVIVE, ext, m, doc);
        }

        static Action retry(ExternalDoc ext, KnowledgeConnectorDoc m, KnowledgeDocument doc) {
            return new Action(Kind.RETRY, ext, m, doc);
        }
    }

    private static final class RoundCounters {
        int added;
        int updated;
        int revived;
        int retried;
        int isolated;
        int deleted;
        int skipped;
        int errors;
        final List<String> notes = new ArrayList<>();

        String summary() {
            StringBuilder sb = new StringBuilder("新增").append(added)
                    .append("/更新").append(updated)
                    .append("/复活").append(revived)
                    .append("/重试").append(retried)
                    .append("/隔离").append(isolated)
                    .append("/删除").append(deleted)
                    .append("/跳过").append(skipped)
                    .append("/错误").append(errors);
            if (!notes.isEmpty()) {
                sb.append("；").append(String.join("；", notes));
            }
            String s = sb.toString();
            return s.length() > 1000 ? s.substring(0, 1000) : s;
        }
    }
}
