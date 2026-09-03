package com.superprogrammer.knowledge.connector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.knowledge.entity.KnowledgeConnector;
import com.superprogrammer.knowledge.entity.KnowledgeConnectorDoc;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import com.superprogrammer.knowledge.event.DocumentUploadedEvent;
import com.superprogrammer.knowledge.mapper.KnowledgeConnectorDocMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeConnectorMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeDocumentMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeImageEmbeddingMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * C6 同步 worker 的全部 DB 写操作（WP6 Step3）。独立 bean（对齐 IndexJobTxService 范式）：
 * worker（非事务）跨 bean 调本类 @Transactional 方法经 Spring 代理生效；外网抓取在事务外。
 *
 * <p>认领=乐观口径的 FOR UPDATE SKIP LOCKED：锁窗口只覆盖「校验 last_sync_at 未被并发改写→
 * 推进 last_sync_at」的瞬时事务（同步本身分钟级，不占行锁）；SKIP LOCKED 让并发节点锁冲突时
 * 直接放弃而非排队。
 *
 * <p>ISOLATED 复用 QUARANTINED 状态与既有隔离治理语义（不召回=节点+向量已清；管理端可解除），
 * 以 quarantineReason 前缀区分「源端删除」与「注入扫描命中」——安全隔离绝不被连接器自动复活。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorSyncTxService {

    private static final Long TENANT_ID = 1L;
    public static final String ISOLATE_REASON_PREFIX = "连接器源端已删除（ISOLATED）";

    private final KnowledgeConnectorMapper connectorMapper;
    private final KnowledgeConnectorDocMapper mappingMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeNodeMapper nodeMapper;
    private final KnowledgeEmbeddingMapper embeddingMapper;
    private final KnowledgeDocEmbeddingMapper docEmbeddingMapper;
    private final KnowledgeImageEmbeddingMapper imageEmbeddingMapper;
    private final ApplicationEventPublisher eventPublisher;

    /** 启用中的连接器（worker 轮询入口读，无事务）。 */
    public List<KnowledgeConnector> listEnabled() {
        return connectorMapper.selectList(new LambdaQueryWrapper<KnowledgeConnector>()
                .eq(KnowledgeConnector::getStatus, KnowledgeConnector.STATUS_ENABLED));
    }

    public KnowledgeConnector getConnector(Long id) {
        return id == null ? null : connectorMapper.selectById(id);
    }

    /**
     * 同步新文档打来源标（WP6 Step4 🔌 徽标数据面）：sourceType=CONNECTOR + sourceUri=external_id。
     * upload() 管线不感知连接器（零改动承诺），落库后补一列轻量 update。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markConnectorOrigin(Long docId, String externalId) {
        documentMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, docId)
                .set(KnowledgeDocument::getSourceType, "CONNECTOR")
                .set(KnowledgeDocument::getSourceUri, externalId));
    }

    /**
     * 认领到期连接器：行锁（SKIP LOCKED）内复核 status=ENABLED 且 last_sync_at 仍是 worker
     * 读到的旧值（防双节点同时判定到期），然后推 last_sync_at=now 作为本轮占位——
     * 后续轮次以新 last_sync_at 计 cron，天然防重入。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean tryClaim(Long connectorId, OffsetDateTime expectedLastSyncAt) {
        KnowledgeConnector row = connectorMapper.selectOne(new LambdaQueryWrapper<KnowledgeConnector>()
                .eq(KnowledgeConnector::getId, connectorId)
                .last("FOR UPDATE SKIP LOCKED"));
        if (row == null || !KnowledgeConnector.STATUS_ENABLED.equals(row.getStatus())
                || !Objects.equals(row.getLastSyncAt(), expectedLastSyncAt)) {
            return false;
        }
        connectorMapper.update(null, new LambdaUpdateWrapper<KnowledgeConnector>()
                .eq(KnowledgeConnector::getId, connectorId)
                .set(KnowledgeConnector::getLastSyncAt, OffsetDateTime.now()));
        return true;
    }

    public List<KnowledgeConnectorDoc> listMappings(Long connectorId) {
        return mappingMapper.selectList(new LambdaQueryWrapper<KnowledgeConnectorDoc>()
                .eq(KnowledgeConnectorDoc::getConnectorId, connectorId));
    }

    public KnowledgeDocument getDocument(Long docId) {
        return docId == null ? null : documentMapper.selectById(docId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void insertMapping(Long connectorId, String externalId, String etag, Long docId) {
        KnowledgeConnectorDoc m = new KnowledgeConnectorDoc();
        m.setTenantId(TENANT_ID);
        m.setConnectorId(connectorId);
        m.setExternalId(externalId);
        m.setEtag(etag);
        m.setDocId(docId);
        m.setManualDeleted(false);
        m.setSyncedAt(OffsetDateTime.now());
        mappingMapper.insert(m);
    }

    @Transactional(rollbackFor = Exception.class)
    public void touchMapping(Long mappingId, String etag) {
        mappingMapper.update(null, new LambdaUpdateWrapper<KnowledgeConnectorDoc>()
                .eq(KnowledgeConnectorDoc::getId, mappingId)
                .set(KnowledgeConnectorDoc::getEtag, etag)
                .set(KnowledgeConnectorDoc::getSyncedAt, OffsetDateTime.now()));
    }

    /** 手工删除同步文档 → 账本标记：下轮起永久跳过（不复活，联动点表）。 */
    @Transactional(rollbackFor = Exception.class)
    public void markManualDeleted(Long mappingId) {
        mappingMapper.update(null, new LambdaUpdateWrapper<KnowledgeConnectorDoc>()
                .eq(KnowledgeConnectorDoc::getId, mappingId)
                .set(KnowledgeConnectorDoc::getManualDeleted, true));
    }

    /** 账本行逻辑删（源删同步删模式）：源端条目若再现，按全新文档走新增。 */
    @Transactional(rollbackFor = Exception.class)
    public void removeMapping(Long mappingId) {
        mappingMapper.deleteById(mappingId);
    }

    /**
     * ISOLATED：置 QUARANTINED+原因，清节点与三池向量（与注入隔离同构——召回以节点为准，
     * 节点清空即不召回）；账本行保留 etag，源恢复时对比后复活。
     */
    @Transactional(rollbackFor = Exception.class)
    public void isolateDoc(Long docId, String reason) {
        documentMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, docId)
                .set(KnowledgeDocument::getStatus, "QUARANTINED")
                .set(KnowledgeDocument::getQuarantineReason, reason));
        clearNodesAndEmbeddings(docId);
        log.info("连接器源端删除 → 文档 ISOLATED docId={}", docId);
    }

    /**
     * 变更重同步落库：换 fileRef/fileHash、置 PENDING、清旧节点+向量（writeNodes 不清旧，
     * 复用解析管线前必须先清——与 quarantine→unquarantine 重解析路径同构），
     * 事务内发 DocumentUploadedEvent（AFTER_COMMIT 触发完整解析管线）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetDocForResync(Long docId, String fileRef, String fileHash, Long operatorId) {
        documentMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, docId)
                .set(KnowledgeDocument::getFileRef, fileRef)
                .set(KnowledgeDocument::getFileHash, fileHash)
                .set(KnowledgeDocument::getStatus, "PENDING")
                .set(KnowledgeDocument::getParseError, null)
                .set(KnowledgeDocument::getQuarantineReason, ""));
        clearNodesAndEmbeddings(docId);
        eventPublisher.publishEvent(new DocumentUploadedEvent(docId, operatorId));
    }

    /**
     * ISOLATED 复活（源端条目再现且 etag 未变）：节点在隔离时已清，直接置回 PENDING+
     * 重发解析事件重建（与 unquarantine 同构）。安全隔离（注入命中）不走此方法——
     * worker 按 quarantineReason 前缀分流，只有连接器自己的 ISOLATED 标记可自动复活。
     */
    @Transactional(rollbackFor = Exception.class)
    public void reviveDoc(Long docId, Long operatorId) {
        documentMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, docId)
                .set(KnowledgeDocument::getStatus, "PENDING")
                .set(KnowledgeDocument::getQuarantineReason, ""));
        eventPublisher.publishEvent(new DocumentUploadedEvent(docId, operatorId));
        log.info("连接器源端恢复 → 文档复活重解析 docId={}", docId);
    }

    /** 成功收口：摘要落库 + 连续错误清零。 */
    @Transactional(rollbackFor = Exception.class)
    public void finishSuccess(Long connectorId, String summary) {
        connectorMapper.update(null, new LambdaUpdateWrapper<KnowledgeConnector>()
                .eq(KnowledgeConnector::getId, connectorId)
                .set(KnowledgeConnector::getLastSyncSummary, truncate(summary, 1000))
                .set(KnowledgeConnector::getSyncErrorStreak, 0));
    }

    /** 失败记账：连续错误 +1，≥3 轮 → ERROR 停调度（管理端重新启用后恢复）。 */
    @Transactional(rollbackFor = Exception.class)
    public void recordError(Long connectorId, String message) {
        KnowledgeConnector row = connectorMapper.selectById(connectorId);
        int streak = (row == null || row.getSyncErrorStreak() == null ? 0 : row.getSyncErrorStreak()) + 1;
        LambdaUpdateWrapper<KnowledgeConnector> u = new LambdaUpdateWrapper<>();
        u.eq(KnowledgeConnector::getId, connectorId)
                .set(KnowledgeConnector::getSyncErrorStreak, streak)
                .set(KnowledgeConnector::getLastSyncSummary, truncate("同步失败: " + message, 1000));
        if (streak >= 3) {
            u.set(KnowledgeConnector::getStatus, KnowledgeConnector.STATUS_ERROR);
        }
        connectorMapper.update(null, u);
        log.warn("连接器同步失败 connectorId={} streak={} : {}", connectorId, streak, message);
    }

    private void clearNodesAndEmbeddings(Long docId) {
        nodeMapper.delete(new LambdaQueryWrapper<KnowledgeNode>()
                .eq(KnowledgeNode::getDocumentId, docId));
        embeddingMapper.deleteByDocument(docId);
        docEmbeddingMapper.deleteByDocument(docId);
        imageEmbeddingMapper.deleteByDocument(docId);
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
