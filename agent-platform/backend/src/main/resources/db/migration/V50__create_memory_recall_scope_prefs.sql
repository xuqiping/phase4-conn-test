-- V50: 计划12 · D-7 · 召回 scope 用户偏好（跨会话沿用，方案 A per-user 表）。
-- 设计 §3.3 line 113「保留上次选择，新会话沿用；首次无历史默认 {个人}」。
-- 子 plan：项目工程文档/计划/计划12-D-召回.md。
--
-- 方案选型（D-7 裁决）：per-user 独立表（非 KV 带 uid / 非 Redis）——
--   scope 多选是用户级强一致偏好（跨会话沿用，不可丢），不宜 Redis 缓存语义；
--   SystemSetting 全局表无 user_id 列，混用污染全局配置语义。
--
-- 1:1 用户偏好（user_id UNIQUE WHERE deleted=0），upsert 语义（无则插/有则改，业务上不主动删）。
-- 走 BaseEntity 软删 + version + MetaObjectHandler 自动填充（与全栈一致）。
--
-- ⚠️ 编号：原计划留 V50 给 H DROP（旧 user_memories/user_memory_projects），
--    H 迭代未做 → D-7 先占 V50；H 收尾 DROP 顺延 V51。
--    IT 实跑：MemoryRecallScopePrefIT 5 测 PG16 验 V50 建表/upsert/bigint[]/隔离/null 落库全绿。
--
-- 软删 UNIQUE：user_id WHERE deleted=0（软删后同用户可重建偏好）。
CREATE TABLE memory_recall_scope_prefs (
    id               BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT                   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    personal_on      BOOLEAN                  NOT NULL DEFAULT TRUE,     -- 个人 scope 开关（默认 {个人}）
    project_ids      BIGINT[]                 NOT NULL DEFAULT '{}',     -- 项目多选（经 resolver 过滤可访问）
    direction        VARCHAR(16)              NOT NULL DEFAULT 'BOTH',   -- INPUT/OUTPUT/BOTH
    relative_days    INT,                                                -- 时间窗相对天数（NULL=不限）
    tw_start         TIMESTAMP WITH TIME ZONE,                             -- 时间窗绝对下界（NULL=不限）
    tw_end           TIMESTAMP WITH TIME ZONE,                             -- 时间窗绝对上界（NULL=不限）
    include_departed BOOLEAN                  NOT NULL DEFAULT TRUE,     -- L10 离职开关（默认开，过滤接入在 I3）
    created_by       BIGINT,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by       BIGINT,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted          INT                      NOT NULL DEFAULT 0,
    version          INT                      NOT NULL DEFAULT 0
);

-- 部分唯一索引：user_id WHERE deleted=0（软删后同用户可重建偏好）。
-- ⚠️ PG 表级 CONSTRAINT 不支持 WHERE（42601），部分唯一只能走 CREATE UNIQUE INDEX（IT 跑通已验）。
CREATE UNIQUE INDEX uk_memory_recall_scope_prefs_user
    ON memory_recall_scope_prefs(user_id) WHERE deleted = 0;

COMMENT ON TABLE  memory_recall_scope_prefs IS '召回 scope 用户偏好（1:1，跨会话沿用；首次无历史默认 {个人}）';
COMMENT ON COLUMN memory_recall_scope_prefs.personal_on      IS '个人 scope 开关，默认 TRUE（首次默认 {个人}）';
COMMENT ON COLUMN memory_recall_scope_prefs.project_ids      IS '项目多选集（bigint[]，resolver 过滤可访问防越权）';
COMMENT ON COLUMN memory_recall_scope_prefs.direction        IS '方向过滤 INPUT/OUTPUT/BOTH';
COMMENT ON COLUMN memory_recall_scope_prefs.include_departed IS 'L10 离职开关（默认开，DEPARTED 过滤接入在 I3）';
