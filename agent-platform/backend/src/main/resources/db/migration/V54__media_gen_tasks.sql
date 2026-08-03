-- ============================================================
-- V54: 媒体生成任务表 + media:gen 权限 seed（gated）
-- 功能：SeedDance 2.0 视频生成（spec SeedDance视频生成.md §6，plan Step1）
-- 设计要点：
--   1. append-only 任务日志 + 状态机（PENDING→RUNNING→SUCCEEDED/FAILED/DOWNLOAD_FAILED）。
--      不加 deleted/version：任务表不软删，靠归档清理（同 stored_files 思路）。
--   2. locked_until + attempt：worker 用 FOR UPDATE SKIP LOCKED 认领，支持崩溃恢复
--      （照抄 knowledge_index_jobs claim 模式；服务重启后下次轮询自动续跑 RUNNING 行）。
--   3. usage 自带 tokens_cost/cost/status_flag，解耦未落地的 llm_usage_logs
--      （media token=像素换算，与文本分词口径不同，不可加总）。
--   4. 视频文件复用 V40 stored_files（写一行 source=MEDIA），result_file_id 指向其 file_id。
-- 权限策略 = gated：media:gen 仅 admin 默认有，普通 user 由 admin 按需授（同 V19 knowledge:write）。
-- ============================================================

-- 1. 媒体生成任务表
CREATE TABLE media_gen_tasks (
    id              BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at      TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    user_id         BIGINT,                                   -- nullable：系统调用无 user
    provider_id     BIGINT                      NOT NULL,    -- llm_providers.id（Ark provider，复用 doubao key）
    model           VARCHAR(128)                NOT NULL,    -- doubao-seedance-2-0 等
    task_type       VARCHAR(16)                 NOT NULL,    -- TEXT2VIDEO | IMAGE2VIDEO
    status          VARCHAR(20)                 NOT NULL DEFAULT 'PENDING',  -- PENDING|RUNNING|SUCCEEDED|FAILED|DOWNLOAD_FAILED
    ark_task_id     VARCHAR(128),                              -- Ark 返回的任务 id（轮询/崩溃恢复用）
    request_config  JSONB                       NOT NULL,    -- {prompt, duration, resolution, refFileId?}
    result_file_id  VARCHAR(128),                              -- → stored_files.file_id（SUCCEEDED 下载后填）
    tokens_cost     INT,                                      -- Ark usage.total_tokens 或像素公式估算
    cost            DECIMAL(12,6),                             -- nullable：MVP 不折成本，价表后回填
    status_flag     VARCHAR(16)                 DEFAULT 'SUCCESS',  -- SUCCESS | ESTIMATED | FAILED（usage 口径）
    error_msg       VARCHAR(256),                              -- 失败原因（截断）
    attempt         INTEGER                     NOT NULL DEFAULT 0,  -- 认领/重试次数
    locked_until    TIMESTAMPTZ                                -- 认领锁（SKIP LOCKED + 过期重认领）
);

-- 2. 索引（plan 性能清单）
CREATE INDEX idx_mgen_user_time  ON media_gen_tasks(user_id, created_at);
CREATE INDEX idx_mgen_status_tm  ON media_gen_tasks(status, created_at);
CREATE INDEX idx_mgen_ark_task   ON media_gen_tasks(ark_task_id);

COMMENT ON TABLE  media_gen_tasks              IS '媒体生成任务（append-only 日志+状态机）。SeedDance 视频生成异步轮询用。';
COMMENT ON COLUMN media_gen_tasks.task_type    IS 'TEXT2VIDEO 文生视频 / IMAGE2VIDEO 图生视频';
COMMENT ON COLUMN media_gen_tasks.status       IS 'PENDING|RUNNING|SUCCEEDED|FAILED|DOWNLOAD_FAILED';
COMMENT ON COLUMN media_gen_tasks.result_file_id IS '→ stored_files.file_id；Ark URL 有时效须即时下载落地';
COMMENT ON COLUMN media_gen_tasks.tokens_cost  IS 'Ark usage.total_tokens（status_flag=SUCCESS）或像素公式估算（ESTIMATED）';
COMMENT ON COLUMN media_gen_tasks.locked_until IS '认领锁过期点；FOR UPDATE SKIP LOCKED + 过期重认领实现崩溃恢复';

-- 3. 权限 seed（gated：仅 admin 默认有 media:gen）
INSERT INTO permissions (name, code, resource, action) VALUES
    ('生成视频', 'media:gen', 'media', 'gen')
ON CONFLICT DO NOTHING;

-- 3.1 系统管理员：默认有（可生成 + 可授权/撤销）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'admin' AND p.code = 'media:gen'
ON CONFLICT DO NOTHING;

-- 注：普通 user / agent_admin 默认不给 media:gen（高成本能力，admin 按需授，同 V19 knowledge:write）。

-- ============================================================
-- 回滚（rollback）：结构与 seed 一并 drop
-- DROP TABLE IF EXISTS media_gen_tasks;
-- DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code='media:gen');
-- DELETE FROM permissions WHERE code='media:gen';
-- ============================================================
