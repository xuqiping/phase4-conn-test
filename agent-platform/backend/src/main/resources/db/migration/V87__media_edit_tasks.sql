-- ============================================================
-- V87: 视频剪辑渲染任务表 + media:edit 权限 seed（gated）
--   （从源项目 V55 移植，重编号到目标下一空位 V87；DDL/seed 内容不变）
-- 功能：视频剪辑（快速剪辑：剪切/拼接/字幕/BGM，单轨），后端 FFmpeg 渲染
--   plan: workflow_output/docs/plans/视频剪辑.plan.md Step1（FR-ED5/ED7）
-- 设计要点（照抄 V54 media_gen_tasks 的 append-only + SKIP-LOCKED 模式）：
--   1. append-only 任务日志 + 状态机（PENDING→RUNNING→SUCCEEDED/FAILED/DOWNLOAD_FAILED）。
--      不加 deleted/version：任务表不软删，靠归档清理（同 media_gen_tasks / stored_files）。
--   2. locked_until + attempt：worker 用 FOR UPDATE SKIP LOCKED 认领，崩溃恢复
--      （重启后下次 poll 自动续跑 RUNNING 行，照抄 media_gen_tasks claim）。
--   3. edit_spec JSONB：完整剪辑意图（clips[]/texts[]/audio/output），失败可读 spec 重放重渲。
--   4. result_file_id → stored_files.file_id（source=EDIT）；渲染产物复用 stored_files 单一咽喉点。
--   5. 不绑 provider：渲染在本地 FFmpeg，无 ark_task_id/provider_id/model 列（与 media_gen_tasks 区别）。
-- 权限策略 = gated：media:edit 仅 admin 默认有，普通 user 由 admin 按需授（同 media:gen / knowledge:write）。
-- ============================================================

-- 1. 视频剪辑渲染任务表
CREATE TABLE media_edit_tasks (
    id              BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at      TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    user_id         BIGINT,                                   -- nullable：系统调用无 user
    status          VARCHAR(20)                 NOT NULL DEFAULT 'PENDING',  -- PENDING|RUNNING|SUCCEEDED|FAILED|DOWNLOAD_FAILED
    edit_spec       JSONB                       NOT NULL,    -- 剪辑意图：{clips[],texts[],audio?,output?}（见 EditSpec）
    result_file_id  VARCHAR(128),                              -- → stored_files.file_id（SUCCEEDED 渲染落盘后填）
    error_msg       VARCHAR(256),                              -- 失败原因（截断，脱敏：FFmpeg stderr 尾部）
    attempt         INTEGER                     NOT NULL DEFAULT 0,  -- 认领/重试次数
    locked_until    TIMESTAMPTZ                                -- 认领锁（SKIP LOCKED + 过期重认领）
);

-- 2. 索引（plan 性能清单：历史列表按 user+时间，worker 认领按 status+时间）
CREATE INDEX idx_medit_user_time  ON media_edit_tasks(user_id, created_at);
CREATE INDEX idx_medit_status_tm  ON media_edit_tasks(status, created_at);

COMMENT ON TABLE  media_edit_tasks              IS '视频剪辑渲染任务（append-only 日志+状态机）。后端 FFmpeg 异步渲染用，复用 media_gen_tasks 模式。';
COMMENT ON COLUMN media_edit_tasks.status       IS 'PENDING|RUNNING|SUCCEEDED|FAILED|DOWNLOAD_FAILED';
COMMENT ON COLUMN media_edit_tasks.edit_spec    IS '剪辑意图 JSONB：clips[{fileId,sourceType,trimStart,trimEnd,order}] / texts[{content,start,end,position,fontSize}] / audio{fileId?,volume} / output{resolution?,fps?}';
COMMENT ON COLUMN media_edit_tasks.result_file_id IS '→ stored_files.file_id（source=EDIT）；渲染产物走单一存储咽喉点';
COMMENT ON COLUMN media_edit_tasks.locked_until IS '认领锁过期点；FOR UPDATE SKIP LOCKED + 过期重认领实现崩溃恢复';

-- 3. 权限 seed（gated：仅 admin 默认有 media:edit）
INSERT INTO permissions (name, code, resource, action) VALUES
    ('视频剪辑', 'media:edit', 'media', 'edit')
ON CONFLICT DO NOTHING;

-- 3.1 系统管理员：默认有（可剪辑 + 可授权/撤销）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'admin' AND p.code = 'media:edit'
ON CONFLICT DO NOTHING;

-- 注：普通 user / agent_admin 默认不给 media:edit（高成本能力，admin 按需授，同 media:gen）。

-- ============================================================
-- 回滚（rollback）：结构与 seed 一并 drop
-- DROP TABLE IF EXISTS media_edit_tasks;
-- DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code='media:edit');
-- DELETE FROM permissions WHERE code='media:edit';
-- ============================================================
