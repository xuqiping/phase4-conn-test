package com.superprogrammer.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.dto.KnowledgeConnectorRequest;
import com.superprogrammer.knowledge.dto.KnowledgeConnectorVO;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.entity.KnowledgeConnector;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseMapper;
import com.superprogrammer.knowledge.mapper.KnowledgeConnectorMapper;
import com.superprogrammer.knowledge.connector.ConnectorSyncWorker;
import com.superprogrammer.llm.service.AesEncryptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C6 连接器 CRUD（WP6 Step1）：config 明文 AES-GCM 加密落库（复用 {@link AesEncryptService}
 * ——billing/LLM 密钥同款主密钥体系，生产态弱密钥 fail-fast 由其自带 SEC-FR-074 保证）。
 * 凭证只写不读：任何 VO 不回明文；日志/异常永不携带密文与明文。
 * 权限：KB 治理级（isOwnerOrAdmin——同 KB 改名/删除口径，MANAGE 授予位不含连接器管理）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeConnectorService {

    private static final Set<String> TYPES = Set.of(
            KnowledgeConnector.TYPE_URL_SITE, KnowledgeConnector.TYPE_S3, KnowledgeConnector.TYPE_WEBDAV);
    private static final String DEFAULT_CRON = "0 0 4 * * *";

    private final KnowledgeConnectorMapper connectorMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AesEncryptService aesEncryptService;
    private final ObjectMapper objectMapper;
    /** 惰性注入：worker→factory→本服务 会构造期成环，ObjectProvider 运行期解环。 */
    private final ObjectProvider<ConnectorSyncWorker> syncWorker;

    public KnowledgeConnectorVO create(Long kbId, KnowledgeConnectorRequest request, Long userId, boolean admin) {
        KnowledgeBase kb = ensureKb(kbId);
        assertManageable(kb, userId, admin, "只有管理员或知识库创建者可管理连接器");
        String type = requireType(request.getType());
        Map<String, Object> config = requireConfig(request.getConfig());
        validateConfig(type, config);

        KnowledgeConnector connector = new KnowledgeConnector();
        connector.setTenantId(1L);
        connector.setKbId(kbId);
        connector.setType(type);
        connector.setName(request.getName().trim());
        connector.setConfigCipher(encryptConfig(config));
        connector.setScheduleCron(normalizeCron(request.getScheduleCron()));
        connector.setSyncOnSourceDelete(Boolean.TRUE.equals(request.getSyncOnSourceDelete()));
        connector.setStatus(KnowledgeConnector.STATUS_ENABLED);
        connector.setCreatedBy(userId);
        connectorMapper.insert(connector);
        return toVO(connector);
    }

    public List<KnowledgeConnectorVO> list(Long kbId, Long userId, boolean admin) {
        KnowledgeBase kb = ensureKb(kbId);
        assertManageable(kb, userId, admin, "只有管理员或知识库创建者可查看连接器");
        return connectorMapper.selectList(new LambdaQueryWrapper<KnowledgeConnector>()
                        .eq(KnowledgeConnector::getKbId, kbId)
                        .orderByDesc(KnowledgeConnector::getId))
                .stream().map(this::toVO).toList();
    }

    public KnowledgeConnectorVO update(Long id, KnowledgeConnectorRequest request, Long userId, boolean admin) {
        KnowledgeConnector connector = ensureConnector(id);
        assertManageable(ensureKb(connector.getKbId()), userId, admin, "只有管理员或知识库创建者可管理连接器");
        if (request.getName() != null && !request.getName().isBlank()) {
            connector.setName(request.getName().trim());
        }
        // 类型创建后不可改（映射账本 external_id 语义随类型；换源=新建连接器）
        if (request.getType() != null && !request.getType().isBlank()
                && !request.getType().equals(connector.getType())) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "连接器类型创建后不可变更，请删除后新建");
        }
        if (request.getConfig() != null) {
            validateConfig(connector.getType(), request.getConfig());
            connector.setConfigCipher(encryptConfig(request.getConfig()));
        }
        if (request.getScheduleCron() != null || request.getSyncOnSourceDelete() != null) {
            connector.setScheduleCron(request.getScheduleCron() == null
                    ? connector.getScheduleCron() : normalizeCron(request.getScheduleCron()));
            if (request.getSyncOnSourceDelete() != null) {
                connector.setSyncOnSourceDelete(request.getSyncOnSourceDelete());
            }
        }
        connectorMapper.updateById(connector);
        return toVO(connector);
    }

    /** 逻辑删（V175 deleted 列）：映射账本行保留但随连接器失活（worker 只扫活连接器）；
     *  本地文档保留孤儿化（联动点表——归手工管理）。 */
    public void delete(Long id, Long userId, boolean admin) {
        KnowledgeConnector connector = ensureConnector(id);
        assertManageable(ensureKb(connector.getKbId()), userId, admin, "只有管理员或知识库创建者可删除连接器");
        connectorMapper.deleteById(id);
    }

    /** 启用（WP6 Step4）：ERROR → ENABLED 复位连续错误计数（重新给同步机会）；DISABLED → ENABLED。 */
    public KnowledgeConnectorVO enable(Long id, Long userId, boolean admin) {
        KnowledgeConnector connector = ensureConnector(id);
        assertManageable(ensureKb(connector.getKbId()), userId, admin, "只有管理员或知识库创建者可管理连接器");
        connector.setStatus(KnowledgeConnector.STATUS_ENABLED);
        connector.setSyncErrorStreak(0);
        connectorMapper.updateById(connector);
        return toVO(connector);
    }

    /** 停用：worker 只扫 ENABLED——DISABLED 即停摆（配置与账本全保留）。 */
    public KnowledgeConnectorVO disable(Long id, Long userId, boolean admin) {
        KnowledgeConnector connector = ensureConnector(id);
        assertManageable(ensureKb(connector.getKbId()), userId, admin, "只有管理员或知识库创建者可管理连接器");
        connector.setStatus(KnowledgeConnector.STATUS_DISABLED);
        connectorMapper.updateById(connector);
        return toVO(connector);
    }

    /**
     * 立即同步（WP6 Step4，运维手动重试入口）：ERROR 先复位 ENABLED（手动重试=重新给机会），
     * 异步交给 worker（认领语义同轮询——撞上并发轮次自动让位）。
     */
    public void syncNow(Long id, Long userId, boolean admin) {
        KnowledgeConnector connector = ensureConnector(id);
        assertManageable(ensureKb(connector.getKbId()), userId, admin, "只有管理员或知识库创建者可触发同步");
        if (KnowledgeConnector.STATUS_ERROR.equals(connector.getStatus())) {
            connector.setStatus(KnowledgeConnector.STATUS_ENABLED);
            connector.setSyncErrorStreak(0);
            connectorMapper.updateById(connector);
        }
        syncWorker.getObject().triggerManualSync(id);
    }

    /** worker（Step3）用：解密 config 供连接器实例化。密文损坏抛业务异常（不落原文进日志）。 */
    public Map<String, Object> decryptConfig(KnowledgeConnector connector) {
        try {
            return objectMapper.readValue(aesEncryptService.decrypt(connector.getConfigCipher()),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("连接器 config 解密失败 connectorId={} kbId={} errorType={}",
                    connector.getId(), connector.getKbId(), e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "连接器配置无法解密，请重新保存配置");
        }
    }

    // ============================ 校验与工具 ============================

    private KnowledgeBase ensureKb(Long kbId) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        return kb;
    }

    private KnowledgeConnector ensureConnector(Long id) {
        KnowledgeConnector connector = connectorMapper.selectById(id);
        if (connector == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "连接器不存在");
        }
        return connector;
    }

    private void assertManageable(KnowledgeBase kb, Long userId, boolean admin, String message) {
        if (!knowledgeBaseService.isOwnerOrAdmin(kb, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, message);
        }
    }

    private String requireType(String type) {
        if (type == null || !TYPES.contains(type)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "连接器类型必须为 URL_SITE / S3 / WEBDAV");
        }
        return type;
    }

    private Map<String, Object> requireConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "连接器配置不能为空");
        }
        return config;
    }

    /** 类型专属必填结构校验（在明文态做——加密后无法校验）。SSRF 校验在拉取时逐跳做（Step2），此处只验形状。 */
    private void validateConfig(String type, Map<String, Object> config) {
        switch (type) {
            case KnowledgeConnector.TYPE_URL_SITE -> requireHttpUrl(config, "seedUrl", "URL_SITE 种子地址");
            case KnowledgeConnector.TYPE_S3 -> {
                requireHttpUrl(config, "endpoint", "S3 endpoint");
                requireNonBlank(config, "bucket", "S3 bucket");
                requireNonBlank(config, "accessKey", "S3 accessKey");
                requireNonBlank(config, "secretKey", "S3 secretKey");
            }
            case KnowledgeConnector.TYPE_WEBDAV -> {
                requireHttpUrl(config, "baseUrl", "WebDAV 地址");
                requireNonBlank(config, "username", "WebDAV 账号");
            }
            default -> throw new BusinessException(ErrorCode.UNPROCESSABLE, "未知连接器类型: " + type);
        }
    }

    private void requireHttpUrl(Map<String, Object> config, String key, String label) {
        Object value = config.get(key);
        if (!(value instanceof String s) || !(s.startsWith("http://") || s.startsWith("https://"))) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, label + "必须是 http(s):// 起始的合法地址");
        }
    }

    private void requireNonBlank(Map<String, Object> config, String key, String label) {
        Object value = config.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, label + "不能为空");
        }
    }

    private String normalizeCron(String cron) {
        String value = (cron == null || cron.isBlank()) ? DEFAULT_CRON : cron.trim();
        if (!CronExpression.isValidExpression(value)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "cron 表达式非法（Spring 六段：秒 分 时 日 月 周）");
        }
        return value;
    }

    private String encryptConfig(Map<String, Object> config) {
        try {
            return aesEncryptService.encrypt(objectMapper.writeValueAsString(config));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "连接器配置序列化失败");
        }
    }

    private KnowledgeConnectorVO toVO(KnowledgeConnector c) {
        return KnowledgeConnectorVO.builder()
                .id(c.getId())
                .kbId(c.getKbId())
                .type(c.getType())
                .name(c.getName())
                .status(c.getStatus())
                .scheduleCron(c.getScheduleCron())
                .syncOnSourceDelete(Boolean.TRUE.equals(c.getSyncOnSourceDelete()))
                .lastSyncAt(c.getLastSyncAt())
                .lastSyncSummary(c.getLastSyncSummary())
                .syncErrorStreak(c.getSyncErrorStreak() == null ? 0 : c.getSyncErrorStreak())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
