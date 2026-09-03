-- =====================================================================
-- V175: C6 连接器生态（规格 §8.1，WP6 Step1）
-- 两表：knowledge_connectors（外部源连接器定义）
--       knowledge_connector_docs（源端条目 ↔ 本地文档 映射，增量同步账本）
--
-- 语义：连接器 = 「定时从外部数据源（URL 站点/S3/WebDAV）拉文档进 KB」的适配器定义；
--       config_cipher 存 AES-GCM 密文（复用 AesEncryptService，主密钥 llm.encryption.secret），
--       明文凭证永不落库/入日志。connector_docs 按 (connector_id, external_id) 唯一记账，
--       etag 指纹驱动增量：新条目→新增，指纹变→重灌，源端消失→按开关 ISOLATED/治理删除。
-- 生活比喻：连接器是「订阅报纸的合同」（地址+周期+投递规则），connector_docs 是
--   「每期报纸的签收登记本」（哪期到了、内容变没变、对应放在哪个书架）。
-- 删除语义：连接器删除走逻辑删（deleted，历史可查）；映射行随连接器 FK CASCADE 硬删
--   （登记本随合同作废），本地文档**保留**孤儿化（文档归手工管理，联动点表）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- knowledge_connectors：连接器定义（owner/canManage 维护）
-- ---------------------------------------------------------------------
CREATE TABLE knowledge_connectors (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL DEFAULT 1,
    kb_id               BIGINT       NOT NULL REFERENCES knowledge_bases(id),
    type                VARCHAR(32)  NOT NULL,                  -- URL_SITE / S3 / WEBDAV
    name                VARCHAR(128) NOT NULL,
    config_cipher       TEXT         NOT NULL,                  -- AES-GCM 密文（endpoint/凭证/路径规则 JSON）
    schedule_cron       VARCHAR(64)  NOT NULL DEFAULT '0 0 4 * * *',  -- Spring cron（秒 分 时 日 月 周）
    sync_on_source_delete BOOLEAN    NOT NULL DEFAULT FALSE,    -- 源删处理：false=ISOLATED（默认，隔离不召回）/ true=治理删除链
    status              VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',-- ENABLED / DISABLED / ERROR
    last_sync_at        TIMESTAMPTZ,                            -- 最近一轮同步完成时间
    last_sync_summary   VARCHAR(1000),                          -- 最近一轮摘要（新增/更新/删除/错误计数，脱敏）
    created_by          BIGINT       NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by          BIGINT,
    updated_at          TIMESTAMPTZ,
    deleted             INT          NOT NULL DEFAULT 0,
    CONSTRAINT ck_kc_type CHECK (type IN ('URL_SITE', 'S3', 'WEBDAV')),
    CONSTRAINT ck_kc_status CHECK (status IN ('ENABLED', 'DISABLED', 'ERROR'))
);
-- 列表页/worker 认领扫表用：按库取活连接器（worker Step3 落地）
CREATE INDEX idx_kc_kb ON knowledge_connectors(kb_id) WHERE deleted = 0;

-- ---------------------------------------------------------------------
-- knowledge_connector_docs：增量同步账本（worker 读写，前端只读徽标）
-- ---------------------------------------------------------------------
CREATE TABLE knowledge_connector_docs (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL DEFAULT 1,
    connector_id    BIGINT       NOT NULL REFERENCES knowledge_connectors(id) ON DELETE CASCADE,
    external_id     VARCHAR(512) NOT NULL,                      -- URL / S3 key / WebDAV path（统一存原始未编码路径，坑点表）
    etag            VARCHAR(256),                               -- 内容指纹（ETag/Last-Modified/hash），null=尚未拉取
    doc_id          BIGINT       NOT NULL,                      -- 映射到 knowledge_documents
    manual_deleted  BOOLEAN      NOT NULL DEFAULT FALSE,        -- 手工删除同步文档→下轮不复活（映射行标记）
    synced_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by      BIGINT,
    updated_at      TIMESTAMPTZ,
    deleted         INT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_kcd UNIQUE (connector_id, external_id)
);
-- worker 每轮按连接器拉账本对 etag 用
CREATE INDEX idx_kcd_connector ON knowledge_connector_docs(connector_id);
