-- ============================================================
-- V55: 无限画布快照表 + canvas:write 权限 seed（gated）
-- 功能：无限画布创作页（plan 无限画布创作页.md §C1，FR IC-14/IC-16）
-- 设计要点：
--   1. 画布结构（节点/连线/位置）整存整取进 snapshot JSONB；图/视频/音频**产出物**
--      走 V40 stored_files（source=SOURCE_CANVAS），快照只存 fileId 引用——避免 JSONB 撑爆、支持增量（plan R-5）。
--   2. 继承 BaseEntity 模式（created_by/at + updated_by/at + deleted + version），
--      另设独立 user_id 列做 ownership 硬过滤（用户只能编/删自己的画布，与创建人解耦，便于未来转移归属）。
--   3. deleted 软删（@TableLogic）；删画布不级联清产出物 stored_files（历史/复用，plan 联动清单）。
-- 权限策略 = gated：canvas:write 仅 admin 默认有，普通 user 由 admin 按需授（同 V54 media:gen / V19 knowledge:write）。
-- ============================================================

-- 1. 画布快照表
CREATE TABLE canvases (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_by  BIGINT,
    created_at  TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    deleted     INTEGER                     NOT NULL DEFAULT 0,
    version     INTEGER                     NOT NULL DEFAULT 0,
    user_id     BIGINT                      NOT NULL,    -- ownership 硬过滤列（用户只能操作自己的画布）
    name        VARCHAR(128)                NOT NULL,    -- 画布名（用户可重命名）
    snapshot    JSONB                       NOT NULL DEFAULT '{}'::jsonb  -- 整张画布结构 JSON（节点/连线/位置），只存 fileId 引用不嵌 base64
);

-- 2. 索引（plan 性能清单）：按用户列自己的画布，最近更新优先
CREATE INDEX idx_canvas_user_time ON canvases(user_id, updated_at) WHERE deleted = 0;

COMMENT ON TABLE  canvases            IS '无限画布快照（LibTV 式创作页）。结构整存 JSONB，产出物走 stored_files(SOURCE_CANVAS)。';
COMMENT ON COLUMN canvases.user_id    IS '画布归属用户；ownership 硬过滤（与 created_by 解耦，便于转移归属）';
COMMENT ON COLUMN canvases.snapshot   IS '画布结构 JSON：{nodes:[...], edges:[...], viewport?}；产出物只存 fileId 引用不嵌 base64（防撑爆）';
COMMENT ON COLUMN canvases.deleted    IS '软删标记（@TableLogic）；删画布不级联清 stored_files 产出物';

-- 3. 权限 seed（gated：仅 admin 默认有 canvas:write）
INSERT INTO permissions (name, code, resource, action) VALUES
    ('无限画布', 'canvas:write', 'canvas', 'write')
ON CONFLICT DO NOTHING;

-- 3.1 系统管理员：默认有（可创作 + 可授权/撤销）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'admin' AND p.code = 'canvas:write'
ON CONFLICT DO NOTHING;

-- 注：普通 user / agent_admin 默认不给 canvas:write（admin 按需授，同 V54 media:gen）。

-- ============================================================
-- 回滚（rollback）：结构与 seed 一并 drop
-- DROP TABLE IF EXISTS canvases;
-- DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code='canvas:write');
-- DELETE FROM permissions WHERE code='canvas:write';
-- ============================================================
