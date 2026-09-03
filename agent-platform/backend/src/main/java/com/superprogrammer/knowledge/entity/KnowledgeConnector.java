package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * C6 外部源连接器定义（WP6 Step1，V175）。config_cipher=AES-GCM 密文
 * （复用 {@code AesEncryptService}，明文凭证永不落库/入日志）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_connectors")
public class KnowledgeConnector extends BaseEntity {

    public static final String TYPE_URL_SITE = "URL_SITE";
    public static final String TYPE_S3 = "S3";
    public static final String TYPE_WEBDAV = "WEBDAV";

    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_ERROR = "ERROR";

    private Long tenantId;

    private Long kbId;

    /** URL_SITE / S3 / WEBDAV（CHECK 约束兜底）。 */
    private String type;

    private String name;

    /** 配置密文（Base64[iv|ciphertext]，明文=各类型 config JSON，服务层加解密）。 */
    private String configCipher;

    /** Spring cron（秒 分 时 日 月 周），默认每天 04:00。 */
    private String scheduleCron;

    /** 源删处理：false=ISOLATED（默认，隔离不召回）；true=走既有文档治理删除链（坑点表：防源删误删本地）。 */
    private Boolean syncOnSourceDelete;

    /** ENABLED / DISABLED / ERROR（连续 3 轮错误→ERROR，worker Step3 落地）。 */
    private String status;

    private OffsetDateTime lastSyncAt;

    /** 最近一轮摘要（新增/更新/删除/错误计数+脱敏错误信息）。 */
    private String lastSyncSummary;
}
