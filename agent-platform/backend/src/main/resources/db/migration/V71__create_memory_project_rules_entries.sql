-- V65: 记忆系统二期 P1 · 收录规则 + 项目条目（数据层）。
-- 总体设计：项目工程文档/设计/记忆系统二期-项目自动收录与多模态-总体设计.md §3.1/§3.2。
-- 子 plan：workflow_output/docs/plans/记忆系统二期_P1收录路由.plan.md（Step 1）。
--
-- 新建 2 张表 + 路由阈值 KV seed：
--   memory_project_rules   = 项目收录规则（创建者声明"什么内容算本项目的"，UNIQUE(project_id)，v1 每项目一条）
--   memory_project_entries = 项目记忆条目（路由 LLM 蒸馏产物，项目资产，原文不出个人域）
--
-- 关键坑规避（承 V33/V47 教训）：
--   ① halfvec 算子 <=> 在 MyBatis XML 须转义 &lt;=&gt;（本迁移只建列，XML 在 Mapper 写）；
--   ② BIGINT[] 写入须显式 typeHandler=LongArrayTypeHandler；
--   ③ 软删表的唯一约束用部分索引（WHERE deleted = 0），PG 表级 CONSTRAINT 不支持 WHERE（V50 范式）；
--   ④ 本迁移不动 memory_turns 四列（双读过渡），删列放 V67（P1 最后，新召回链路接管后）。

-- ============================================================================
-- 1. memory_project_rules：项目收录规则（路由粗筛锚点 + 精判依据）
-- ============================================================================
CREATE TABLE memory_project_rules (
    id               BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT                   NOT NULL REFERENCES projects(id) ON DELETE CASCADE,  -- 规则所属项目，随项目死
    rule_text        TEXT                     NOT NULL,                -- 自然语言规则（成员可见，透明化）
    positive_examples TEXT[]                  NOT NULL DEFAULT '{}',   -- 正例（≤5，成员可见）
    negative_examples TEXT[]                  NOT NULL DEFAULT '{}',   -- 负例（≤5 滚动，仅 owner/admin 可见，防规避）
    anchor_embedding halfvec(2048),                                    -- 语义粗筛向量 = embed(rule_text+正例)
    anchor_tokens    TEXT,                                             -- BM25 词法串（jieba 分词后空格拼接）
    anchor_tokens_tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(anchor_tokens, ''))) STORED,
    enabled          BOOLEAN                  NOT NULL DEFAULT true,   -- 收录开关；anchor 计算失败时强制 false
    created_by       BIGINT,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by       BIGINT,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted          INT                      NOT NULL DEFAULT 0,
    version          INT                      NOT NULL DEFAULT 0
);

-- v1 每项目一条活规则（软删行不挡重建，走部分索引）
CREATE UNIQUE INDEX uk_memory_project_rules_project ON memory_project_rules(project_id) WHERE deleted = 0;
CREATE INDEX idx_memory_project_rules_anchor_hnsw ON memory_project_rules USING hnsw (anchor_embedding halfvec_cosine_ops);
CREATE INDEX idx_memory_project_rules_anchor_tsv  ON memory_project_rules USING gin (anchor_tokens_tsv);

COMMENT ON TABLE  memory_project_rules IS '项目收录规则（路由层据此自动收记忆进项目）。UNIQUE(project_id) v1 每项目一条；rule_text/正例成员可见，负例仅 owner/admin';
COMMENT ON COLUMN memory_project_rules.rule_text         IS '自然语言规则，如「涉及 SeedDance 视频生成的参数、工作流、排期的讨论」';
COMMENT ON COLUMN memory_project_rules.positive_examples IS '正例（≤5），显著提升路由精度；成员可见';
COMMENT ON COLUMN memory_project_rules.negative_examples IS '负例（≤5 先进先出滚动），审核「弃」时反哺；仅 owner/admin 可见防规避';
COMMENT ON COLUMN memory_project_rules.anchor_embedding  IS '规则锚点向量（rule_text+正例 embed），路由粗筛用；计算失败→enabled 强制 false';
COMMENT ON COLUMN memory_project_rules.enabled           IS '收录开关（owner 控）；关=该项目不再收新条目，已收保留，重开不回溯';

-- ============================================================================
-- 2. memory_project_entries：项目记忆条目（路由蒸馏产物，项目资产）
-- ============================================================================
CREATE TABLE memory_project_entries (
    id             BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id     BIGINT                   NOT NULL REFERENCES projects(id) ON DELETE CASCADE,   -- 条目归属项目（项目资产，随项目死）
    author_user_id BIGINT                   NOT NULL REFERENCES users(id) ON DELETE CASCADE,      -- 溯源谁聊出来的（仅元数据展示「张三·3天前」）
    source_turn_id BIGINT                   REFERENCES memory_turns(id) ON DELETE SET NULL,       -- 回指个人流水账（仅作者本人可顺藤，对他人不可解析）
    tag_ids        BIGINT[]                 NOT NULL DEFAULT '{}',   -- 标签 id 集（归一在作者个人标签库，D2 案 A）
    l1_summary     TEXT,                                             -- 蒸馏 L1（生成时即脱敏，只含规则相关内容）
    l2_detail      TEXT,                                             -- 蒸馏 L2（同上）
    confidence     DOUBLE PRECISION         NOT NULL DEFAULT 0,      -- 路由置信度 0~1（≥0.8 ACTIVE / 0.5~0.8 PENDING_REVIEW / <0.5 丢弃）
    status         VARCHAR(20)              NOT NULL DEFAULT 'PENDING_REVIEW' CHECK (status IN ('ACTIVE','PENDING_REVIEW')),  -- 弃=软删（deleted=1）
    content_type   VARCHAR(10)              NOT NULL DEFAULT 'TEXT' CHECK (content_type IN ('TEXT','FILE')),                  -- FILE=P3 文件记忆收录
    file_id        BIGINT,                                           -- content_type=FILE 时指向 stored_files（P3 用）
    reviewed_by    BIGINT,                                           -- 审核人（owner/admin 收/弃留痕）
    reviewed_at    TIMESTAMP WITH TIME ZONE,                         -- 审核时间
    created_by     BIGINT,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by     BIGINT,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted        INT                      NOT NULL DEFAULT 0,
    version        INT                      NOT NULL DEFAULT 0
);

CREATE INDEX idx_memory_project_entries_project_status ON memory_project_entries(project_id, status) WHERE deleted = 0;  -- 召回合流主查询
CREATE INDEX idx_memory_project_entries_source_turn    ON memory_project_entries(source_turn_id)     WHERE deleted = 0;  -- 删 turn 级联软删用
CREATE INDEX idx_memory_project_entries_author         ON memory_project_entries(author_user_id)     WHERE deleted = 0;  -- 作者撤回/成员「我的条目」
CREATE INDEX idx_memory_project_entries_tag_ids        ON memory_project_entries USING gin (tag_ids);                     -- 标签聚合

COMMENT ON TABLE  memory_project_entries IS '项目记忆条目（路由 LLM 蒸馏产物，项目资产非用户资产）。原文不出个人域：永不含 raw_content；成员即可读，DEPARTED 失读权';
COMMENT ON COLUMN memory_project_entries.source_turn_id IS '回指 memory_turns（软链，仅作者可顺藤）；turn 删除时级联软删本条目（P4 D6）';
COMMENT ON COLUMN memory_project_entries.confidence     IS '路由置信度；阈值走 system_settings KV（memory.routing.*）可调';
COMMENT ON COLUMN memory_project_entries.status         IS 'ACTIVE=直接收录/审核通过；PENDING_REVIEW=灰区待裁决；审核「弃」=软删（deleted=1）';

-- ============================================================================
-- 3. 路由阈值/开关 KV seed（D1 定案：system_settings 可调）
-- ============================================================================
INSERT INTO system_settings (setting_key, setting_value, description) VALUES
    ('memory.routing.enabled', 'true', '记忆二期路由总开关：false=全停自动收录，不影响个人流水账写入'),
    ('memory.routing.coarse-threshold', '0.35', '路由粗筛阈值（向量+BM25 RRF 分数，低于此零 LLM 调用；严于标签归一的 0.25）'),
    ('memory.routing.auto-approve-threshold', '0.8', '路由置信度≥此值条目直接 ACTIVE'),
    ('memory.routing.review-threshold', '0.5', '路由置信度≥此值且<auto-approve 进 PENDING_REVIEW；低于此丢弃')
ON CONFLICT (setting_key) DO NOTHING;
