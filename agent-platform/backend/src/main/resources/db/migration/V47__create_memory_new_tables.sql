-- V47: 计划12 · 个人记忆重设计 · 数据层（迭代 A）。
-- 总体设计：项目工程文档/设计/个人记忆重设计-总体设计.md §2。
-- 子 plan：项目工程文档/计划/计划12-A-数据层.md。
--
-- 完全替换旧 user_memories 事实抽取模型，新建 9 张表 + 扩 memory_conflicts：
--   标签独立表(memory_tags) + 流水账主体(memory_turns) + 总结提炼层(memory_summaries)
--   + 覆盖表(memory_summary_coverage) + 项目成员/设置(memory_project_*)
--   + 自动总结勾选(memory_consolidation_scopes) + 跨用户波及通知(memory_notifications)。
-- 旧 user_memories/user_memory_projects 不在此 DROP，留到 V49（H 收尾，老数据不迁移）。
-- memory_recall_acl 表属 I1 迭代，走 V48，不在本迁移。
--
-- 软删约定（对照总体设计 §2 + §7 旧栈教训）：
--   tags/turns/summaries 走 BaseEntity 软删（deleted+version）；
--   coverage/members/settings/consolidation_scopes/notifications 业务上硬约束或级联清，**无 deleted 列**，不强行加。
--
-- 关键坑规避：
--   ① halfvec 算子 <=> 在 MyBatis XML 须转义 &lt;=&gt;（V33 教训，本迁移只建列，XML 在 Mapper 写）；
--   ② BIGINT[] 写入须显式 typeHandler=LongArrayTypeHandler（V33 教训）；
--   ③ memory_summary_coverage UNIQUE 含 project_id=NULL 须 NULLS NOT DISTINCT（PG15+/本项目16）；
--   ④ 项目删除 §3.7 清理 = memory_summaries/coverage/members 的 project_id 单值列 ON DELETE CASCADE 自动实现，
--      turns.project_ids 是数组无 FK，项目 id 保留（追加 deleted_project_ids 由 app 管）。

-- ============================================================================
-- 1. memory_tags：标签库（写时归一主体，禁手动归并/拆分）
-- ============================================================================
CREATE TABLE memory_tags (
    id               BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT                   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    subject          VARCHAR(50)              NOT NULL DEFAULT '我',   -- L0 主体（我/表哥/配偶...）
    topic            VARCHAR(50)              NOT NULL,                -- L0 主题（居住/爱好/工作...）
    label            VARCHAR(100)             NOT NULL,                -- 对外展示名（同义归一后的规范名）
    aliases          TEXT[]                   NOT NULL DEFAULT '{}',   -- 同义别名集（归一时滚进），GIN 支撑包含查询
    anchor_embedding halfvec(2048),                                    -- 语义粗筛向量 = embed(label+subject+topic+aliases)
    anchor_tokens    TEXT,                                             -- BM25 词法串（jieba 分词后空格拼接）
    anchor_tokens_tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(anchor_tokens, ''))) STORED,
    usage_count      INT                      NOT NULL DEFAULT 0,      -- 复用次数（归一命中 ++，L12）
    created_by       BIGINT,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by       BIGINT,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted          INT                      NOT NULL DEFAULT 0,
    version          INT                      NOT NULL DEFAULT 0,
    -- 写时归一兜底：同 user 同 (subject,topic) 只一条活标签（误并不可逆，禁手动拆）
    CONSTRAINT uk_memory_tags_user_subject_topic UNIQUE NULLS NOT DISTINCT (user_id, subject, topic)
);

CREATE INDEX idx_memory_tags_user              ON memory_tags(user_id)               WHERE deleted = 0;
CREATE INDEX idx_memory_tags_anchor_hnsw       ON memory_tags USING hnsw (anchor_embedding halfvec_cosine_ops);
CREATE INDEX idx_memory_tags_anchor_tsv        ON memory_tags USING gin (anchor_tokens_tsv);
CREATE INDEX idx_memory_tags_aliases           ON memory_tags USING gin (aliases);

COMMENT ON TABLE  memory_tags IS '标签库（写时归一，subject:topic + label + aliases + anchor 三列）。禁手动归并/拆分，仅 owner 改 label/补 aliases';
COMMENT ON COLUMN memory_tags.subject     IS 'L0 主体，默认「我」';
COMMENT ON COLUMN memory_tags.topic       IS 'L0 主题（居住/爱好/工作）';
COMMENT ON COLUMN memory_tags.label       IS '对外展示名（同义归一后规范名）；对外只露 label+subject+topic';
COMMENT ON COLUMN memory_tags.aliases     IS '同义别名集，归一命中滚进；对外不露';
COMMENT ON COLUMN memory_tags.usage_count IS '复用次数，归一命中自增';

-- ============================================================================
-- 2. memory_turns：流水账（一轮对话 INPUT/OUTPUT 各一条，主体表）
-- ============================================================================
CREATE TABLE memory_turns (
    id                   BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id              BIGINT                   NOT NULL REFERENCES users(id) ON DELETE CASCADE,  -- 作者
    session_id           BIGINT,                  -- 来源会话（可空，手动新建无会话）
    direction            VARCHAR(10)              NOT NULL CHECK (direction IN ('INPUT','OUTPUT')),
    tag_ids              BIGINT[]                 NOT NULL DEFAULT '{}',   -- L0：贴的标签 id 集
    l1_summary           TEXT,                                              -- L1：一句概要
    l2_detail            TEXT,                                              -- L2：结构化详述
    raw_content          TEXT,                                              -- 原文（gen 关态写入，90 天 TTL 软删）
    gen_done             BOOLEAN                  NOT NULL DEFAULT false,  -- 是否跑过生成 LLM（false=仅 raw）
    project_ids          BIGINT[]                 NOT NULL DEFAULT '{}',   -- 项目挂载槽（多挂共享，空=个人私有）
    born_personal        BOOLEAN                  NOT NULL DEFAULT true,   -- 出身标记（写入定死，挂/卸不改，卸空转 true）
    departed_project_ids BIGINT[]                 NOT NULL DEFAULT '{}',   -- 作者已离职的项目 id（不删数据，保交接）
    deleted_project_ids  BIGINT[]                 NOT NULL DEFAULT '{}',   -- 项目被删的 id（保留挂载，scope 召回自然排除）
    created_by           BIGINT,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by           BIGINT,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted              INT                      NOT NULL DEFAULT 0,
    version              INT                      NOT NULL DEFAULT 0
);

CREATE INDEX idx_memory_turns_user_created     ON memory_turns(user_id, created_at DESC)       WHERE deleted = 0;
CREATE INDEX idx_memory_turns_user_gen_created ON memory_turns(user_id, gen_done, created_at)  WHERE deleted = 0;  -- raw TTL worker 用
CREATE INDEX idx_memory_turns_project_ids      ON memory_turns USING gin (project_ids);
CREATE INDEX idx_memory_turns_tag_ids          ON memory_turns USING gin (tag_ids);
CREATE INDEX idx_memory_turns_departed         ON memory_turns USING gin (departed_project_ids);
CREATE INDEX idx_memory_turns_deleted_proj     ON memory_turns USING gin (deleted_project_ids);

COMMENT ON TABLE  memory_turns IS '流水账（一轮对话 INPUT/OUTPUT 各一条，记忆主体）。可多挂项目按成员 ACL 只读共享';
COMMENT ON COLUMN memory_turns.tag_ids              IS 'L0 标签 id 集（指向 memory_tags）';
COMMENT ON COLUMN memory_turns.raw_content          IS '原文，gen 关态写入；90 天 TTL worker 软删 gen_done=false 的旧 raw';
COMMENT ON COLUMN memory_turns.gen_done             IS '是否跑过生成 LLM；false=仅 raw 未生成，不参与召回';
COMMENT ON COLUMN memory_turns.project_ids          IS '项目挂载槽，挂哪些项目就哪些成员可读；空=个人私有';
COMMENT ON COLUMN memory_turns.born_personal        IS '出身标记，写入定死；个人出身含后共享，纯项目出身=false';
COMMENT ON COLUMN memory_turns.departed_project_ids IS '作者已离职项目 id，追加不改 project_ids，保交接';
COMMENT ON COLUMN memory_turns.deleted_project_ids  IS '被删项目 id，追加不改 project_ids，scope 召回自然排除';

-- ============================================================================
-- 3. memory_summaries：总结提炼层（L1/L2 + provenance + 链式 + status）
-- ============================================================================
CREATE TABLE memory_summaries (
    id                BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id           BIGINT                   NOT NULL REFERENCES users(id) ON DELETE CASCADE,     -- 作者（只读自己）
    project_id        BIGINT                   REFERENCES projects(id) ON DELETE CASCADE,           -- 单值 scope 归属（NULL=个人，不可挂数组/不可分享）
    tag_id            BIGINT                   REFERENCES memory_tags(id) ON DELETE CASCADE,
    l1_summary        TEXT,                                                                          -- L1 概要
    l2_detail         TEXT,                                                                          -- L2 详述
    source_summary_id BIGINT,                                                                        -- 链式：再压缩自哪条 summary（防膨胀）
    source_turn_ids   BIGINT[]                 NOT NULL DEFAULT '{}',                              -- flat provenance：来自哪些 turn
    status            VARCHAR(20)              NOT NULL DEFAULT 'CLEAN' CHECK (status IN ('CLEAN','PENDING_CONFLICT','STALE')),
    summarized_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),                              -- 12h 规则时间基准（他人引用方 summary 的 summarized_at）
    created_by        BIGINT,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by        BIGINT,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted           INT                      NOT NULL DEFAULT 0,
    version           INT                      NOT NULL DEFAULT 0
);

CREATE INDEX idx_memory_summaries_user_proj_tag ON memory_summaries(user_id, project_id, tag_id) WHERE deleted = 0;
CREATE INDEX idx_memory_summaries_status        ON memory_summaries(user_id, status)             WHERE deleted = 0;
CREATE INDEX idx_memory_summaries_source_turns  ON memory_summaries USING gin (source_turn_ids);

COMMENT ON TABLE  memory_summaries IS '总结提炼层（周期/手动压缩流水账得精华条）。project_id 单值，不可挂数组/不可分享';
COMMENT ON COLUMN memory_summaries.project_id        IS 'scope 归属单值，NULL=个人；项目删除 ON DELETE CASCADE 自动清项目总结行，个人(NULL)不清';
COMMENT ON COLUMN memory_summaries.source_summary_id IS '链式溯源，防膨胀再压缩时指向上游 summary';
COMMENT ON COLUMN memory_summaries.source_turn_ids   IS 'flat provenance，@> [T] 查询受影响的 summary';
COMMENT ON COLUMN memory_summaries.status            IS 'CLEAN 干净 / PENDING_CONFLICT 时序互斥挂起 / STALE 源 turn 被删待重生';
COMMENT ON COLUMN memory_summaries.summarized_at     IS '12h 规则基准：他人引用本 summary 时 now-summarized_at>12h 拒删';

-- ============================================================================
-- 4. memory_summary_coverage：覆盖表（某作者某 turn 某 tag 某 scope 被其总结吃进）
--    无 deleted 列——随 summary/turn 级联清；UNIQUE 含 project_id=NULL 须 NULLS NOT DISTINCT。
-- ============================================================================
CREATE TABLE memory_summary_coverage (
    id          BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    turn_id     BIGINT                   NOT NULL REFERENCES memory_turns(id) ON DELETE CASCADE,
    tag_id      BIGINT                   NOT NULL REFERENCES memory_tags(id) ON DELETE CASCADE,
    summary_id  BIGINT                   REFERENCES memory_summaries(id) ON DELETE CASCADE,
    project_id  BIGINT                   REFERENCES projects(id) ON DELETE CASCADE,   -- NULL=个人 scope
    user_id     BIGINT                   NOT NULL REFERENCES users(id) ON DELETE CASCADE,  -- 作者（召回只认 user_id=召回者自己）
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    -- 同作者同 turn 同 tag 同 scope 只一行；NULLS NOT DISTINCT 让个人 scope(project_id=NULL) 约束生效
    CONSTRAINT uk_memory_coverage UNIQUE NULLS NOT DISTINCT (turn_id, tag_id, project_id, user_id)
);

CREATE INDEX idx_memory_coverage_user_turn ON memory_summary_coverage(user_id, turn_id);
CREATE INDEX idx_memory_coverage_summary   ON memory_summary_coverage(summary_id);

COMMENT ON TABLE  memory_summary_coverage IS '覆盖表（allCovered 判定依据）。召回只认 user_id=召回者自己的行';
COMMENT ON COLUMN memory_summary_coverage.user_id    IS '作者；召回恒按 user_id=self 判覆盖';
COMMENT ON COLUMN memory_summary_coverage.project_id IS 'NULL=个人 scope；NULLS NOT DISTINCT 保证个人 scope UNIQUE 生效';

-- ============================================================================
-- 5. memory_project_members：记忆专属成员表（角色 + ACTIVE/DEPARTED）
--    独立于 Agent 模块的 project_members（旧表不动）。无 deleted——离职置 DEPARTED 保交接。
-- ============================================================================
CREATE TABLE memory_project_members (
    id           BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id   BIGINT                   NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id      BIGINT                   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role         VARCHAR(20)              NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('OWNER','ADMIN','MEMBER')),
    recall_admin BOOLEAN                  NOT NULL DEFAULT false,    -- ACL 配置权（owner 兜底，admin 须此 flag）
    status       VARCHAR(20)              NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DEPARTED')),
    departed_at  TIMESTAMP WITH TIME ZONE,                           -- 离职时间（带「已离开人员·用户名·时间」标注）
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_memory_project_members UNIQUE (project_id, user_id)
);

CREATE INDEX idx_memory_members_user    ON memory_project_members(user_id);
CREATE INDEX idx_memory_members_project ON memory_project_members(project_id);

COMMENT ON TABLE  memory_project_members IS '记忆专属成员（与 Agent 模块 project_members 解耦）。离职 DEPARTED 不删行保交接';
COMMENT ON COLUMN memory_project_members.recall_admin IS 'ACL 配置权；owner 兜底全读，admin 须 recall_admin=true 才能配 ACL';
COMMENT ON COLUMN memory_project_members.status       IS 'ACTIVE 在职 / DEPARTED 离职（不删行，别人召回受离职开关控）';

-- ============================================================================
-- 6. memory_project_settings：项目级 gen 开关（owner 维度，默认开）
--    无 deleted——项目删 CASCADE 清。
-- ============================================================================
CREATE TABLE memory_project_settings (
    id         BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT                   NOT NULL UNIQUE REFERENCES projects(id) ON DELETE CASCADE,
    gen_enabled BOOLEAN                 NOT NULL DEFAULT true,   -- L0/L1/L2 同开同关
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE memory_project_settings IS '项目级 gen 开关（owner 维度，默认开）。与会员覆写 AND 方可生成';

-- ============================================================================
-- 7. memory_project_user_settings：会员个人 gen 覆写开关（默认开，会员自控）
-- ============================================================================
CREATE TABLE memory_project_user_settings (
    id          BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT                   NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id     BIGINT                   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    gen_enabled BOOLEAN                  NOT NULL DEFAULT true,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_memory_project_user_settings UNIQUE (project_id, user_id)
);

COMMENT ON TABLE memory_project_user_settings IS '会员个人 gen 覆写（默认开，会员自控）。owner 项目级 AND 会员覆写皆开才生成';

-- ============================================================================
-- 8. memory_consolidation_scopes：用户自动总结 scope 勾选集
--    UNIQUE 含 project_id=NULL(PERSONAL) 须 NULLS NOT DISTINCT。新用户默认插 PERSONAL（见下方 trigger）。
-- ============================================================================
CREATE TABLE memory_consolidation_scopes (
    id           BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT                   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope_kind   VARCHAR(20)              NOT NULL CHECK (scope_kind IN ('PERSONAL','PROJECT')),
    project_id   BIGINT                   REFERENCES projects(id) ON DELETE CASCADE,   -- PERSONAL=NULL
    auto_enabled BOOLEAN                  NOT NULL DEFAULT true,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_memory_consolidation_scopes UNIQUE NULLS NOT DISTINCT (user_id, scope_kind, project_id)
);

CREATE INDEX idx_memory_consolidation_user ON memory_consolidation_scopes(user_id);

COMMENT ON TABLE memory_consolidation_scopes IS '自动总结 scope 勾选集（默认勾 PERSONAL）。定时跑前判有无新增未总结 turn，无则空跳过';

-- ============================================================================
-- 9. memory_notifications：跨用户波及通知（流水账删除/项目删除影响他人）
--    无 deleted——resolved_at 标已处理。
-- ============================================================================
CREATE TABLE memory_notifications (
    id          BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT                   NOT NULL REFERENCES users(id) ON DELETE CASCADE,  -- 接收者
    type        VARCHAR(40)              NOT NULL CHECK (type IN ('SUMMARY_AFFECTED_BY_RECALL','PROJECT_DELETED_AFFECTED')),
    ref_id      BIGINT,                                                                          -- 关联 summary/project id
    message     TEXT,
    resolved_at TIMESTAMP WITH TIME ZONE,                                                 -- worker 重生完成后回填
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_memory_notif_user_unresolved ON memory_notifications(user_id, resolved_at);

COMMENT ON TABLE memory_notifications IS '跨用户波及通知。SUMMARY_AFFECTED_BY_recall：他人撤回 turn 波及我的 summary；PROJECT_DELETED_AFFECTED：项目删除影响';

-- ============================================================================
-- 10. 扩 memory_conflicts：加 tag_id + summary_id（新模型冲突只来自总结时序互斥，无 type 列）
--     旧列（block_label/new_memory 等）保留不动，H 收尾随旧表语义废弃。
-- ============================================================================
ALTER TABLE memory_conflicts ADD COLUMN tag_id    BIGINT REFERENCES memory_tags(id) ON DELETE CASCADE;
ALTER TABLE memory_conflicts ADD COLUMN summary_id BIGINT REFERENCES memory_summaries(id) ON DELETE CASCADE;

COMMENT ON COLUMN memory_conflicts.tag_id    IS '新模型：冲突关联的标签（时序互斥发生在同 tag 下）';
COMMENT ON COLUMN memory_conflicts.summary_id IS '新模型：冲突关联的待裁决 summary';

-- ============================================================================
-- 11. 新用户钩子：DB trigger（数据层纯净 + 一致性强保证，优于 AOP 多注册路径漏钩）
--     每注册一用户默认插 memory_consolidation_scopes(user, PERSONAL, NULL, auto_enabled=true)。
-- ============================================================================
CREATE OR REPLACE FUNCTION fn_memory_new_user_default_scope()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO memory_consolidation_scopes (user_id, scope_kind, project_id, auto_enabled, created_at, updated_at)
    VALUES (NEW.id, 'PERSONAL', NULL, true, NOW(), NOW());
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_memory_new_user_default_scope
    AFTER INSERT ON users
    FOR EACH ROW EXECUTE FUNCTION fn_memory_new_user_default_scope();
