-- =====================================================================
-- V170: C1 文档关联关系图（规格 §3.1/§3.3，WP1 Step1）
-- 两表：knowledge_document_relations（人工声明的边）
--       knowledge_document_relation_suggestions（共召回统计的建边建议）
--
-- 语义：一条边 = 「命中主动方 doc_id 时，被动方 related_doc_id 按关系类型跟进」。
--   MUST_CITE     必须引用   —— 命中 A 时 B 强制进上下文（打包召回/硬绑定）
--   MAY_CITE      按需引用   —— B 作为候选进 rerank，过阈才进
--   MUST_BE_CITED 必须被引用 —— B 被召回时 A 必须跟着出现（反向硬绑定）
--   MAY_BE_CITED  按需被引用 —— B 被召回时 A 仅作「相关文档」推荐，不进主上下文
-- 存储只需 CITE 方向语义 + 查询时双向读（MUST_BE_CITED(A→B) 读作 MUST_CITE(B→A)），
-- 避免四种类型两两组合的状态爆炸。链式传导限 1 跳（服务层保证，表结构无关）。
-- 生活比喻：文档是科室，边是「会诊单」——MUST_CITE 是「叫上 B 一起拍板」，
--   MAY_CITE 是「B 有空就请来参考」，MUST_BE_CITED 是「B 出场 A 必须陪同」，
--   MAY_BE_CITED 是「名单末尾提一嘴 A」。
-- 撤销 = 硬删（对齐 knowledge_permissions 惯例，表无 deleted 列）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- knowledge_document_relations：人工边（owner/canManage 维护）
-- ---------------------------------------------------------------------
CREATE TABLE knowledge_document_relations (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL DEFAULT 1,
    kb_id           BIGINT       NOT NULL,                     -- 关联仅限同库（首版边界）
    doc_id          BIGINT       NOT NULL,                     -- 主动方文档
    related_doc_id  BIGINT       NOT NULL,                     -- 被动方文档
    relation_type   VARCHAR(32)  NOT NULL,                     -- MUST_CITE / MAY_CITE / MUST_BE_CITED / MAY_BE_CITED
    note            VARCHAR(500),                              -- 可选备注（为什么关联）
    created_by      BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_kdr UNIQUE (kb_id, doc_id, related_doc_id, relation_type),
    CONSTRAINT ck_kdr_no_self CHECK (doc_id <> related_doc_id),
    CONSTRAINT ck_kdr_type CHECK (relation_type IN
        ('MUST_CITE', 'MAY_CITE', 'MUST_BE_CITED', 'MAY_BE_CITED'))
);
-- 检索 step6.5 用：按主动方批量取边（命中 A 带出 B）
CREATE INDEX idx_kdr_doc ON knowledge_document_relations(kb_id, doc_id);
-- 检索 step6.5 用：按被动方批量取边（命中 B 反向带出 A，MUST_BE_CITED/MAY_BE_CITED 读法）
CREATE INDEX idx_kdr_related ON knowledge_document_relations(kb_id, related_doc_id);

-- ---------------------------------------------------------------------
-- knowledge_document_relation_suggestions：共召回建议（只建议不自动建边）
-- 来源：trace 共召回统计（同 query 下两文档共现 >= N 次）→ 建议行；
--       owner/canManage 采纳（转正式边+状态 ADOPTED）或忽略（IGNORED）。
-- ---------------------------------------------------------------------
CREATE TABLE knowledge_document_relation_suggestions (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL DEFAULT 1,
    kb_id             BIGINT       NOT NULL,
    doc_id_a          BIGINT       NOT NULL,                   -- 对内无方向语义，a<b 规范化存储
    doc_id_b          BIGINT       NOT NULL,
    co_recall_count   INTEGER      NOT NULL DEFAULT 0,          -- 共召回次数
    sample_query_hash VARCHAR(128),                             -- 最近一次共召回 query 的 hash（脱敏：trace 惯例只存 hash）
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING / ADOPTED / IGNORED
    last_seen_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_kdrs UNIQUE (kb_id, doc_id_a, doc_id_b),
    CONSTRAINT ck_kdrs_pair CHECK (doc_id_a < doc_id_b),        -- a<b 去重同一对的两个方向
    CONSTRAINT ck_kdrs_status CHECK (status IN ('PENDING', 'ADOPTED', 'IGNORED'))
);
-- 建议列表页用：按库取待处理建议
CREATE INDEX idx_kdrs_pending ON knowledge_document_relation_suggestions(kb_id) WHERE status = 'PENDING';
