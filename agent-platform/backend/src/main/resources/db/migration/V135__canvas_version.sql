-- ============================================================
-- V135: 无限画布版本保存（2x 五轮：撤回/重做之外的「版本」时间线）
-- 功能：canvases 一张表只有最新快照（800ms 防抖整存整取），用户误删/改坏后无从回退。
--   版本表 = 用户手动「存版本」快照点（区别于撤销栈：跨会话、可命名、可恢复）。
-- 设计要点：
--   1. snapshot 整存 JSONB（镜像 canvases.snapshot 形状 {nodes,edges,...}，只存 fileId 引用不嵌 base64）。
--   2. 每画布保留最近 30 个版本：service 层插入后修剪（按 id 升序删超出部分），
--      防长期使用撑爆表（30 × 5MB 上限 ≈ 150MB/画布极端兜底）。
--   3. FK ON DELETE CASCADE：画布物理清理时版本随删（画布本身软删不触发；历史版本留在软删画布下无害）。
--   4. created_by 落建版本人（成员共享画布的未来扩展预留；当前 ownership=画布 owner 单人）。
-- 回滚：drop 表即回滚（版本是纯冗余快照，丢版本不丢最新画布）。
-- ============================================================

CREATE TABLE canvas_versions (
    id              BIGINT                    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_by      BIGINT,
    created_at      TIMESTAMPTZ               NOT NULL DEFAULT NOW(),
    updated_by      BIGINT,
    updated_at      TIMESTAMPTZ               NOT NULL DEFAULT NOW(),
    deleted         INTEGER                   NOT NULL DEFAULT 0,      -- 软删（@TableLogic）
    version         INTEGER                   NOT NULL DEFAULT 0,      -- 乐观锁行版本
    canvas_id       BIGINT                    NOT NULL,                -- 归属画布（ownership 经画布 loadOwned 咽喉点校验）
    label           VARCHAR(64)               NOT NULL,                -- 版本名（空则服务端补「版本 yyyy-MM-dd HH:mm」）
    snapshot        JSONB                     NOT NULL,                -- 画布结构快照（同 canvases.snapshot 形状）
    node_count      INTEGER                   NOT NULL DEFAULT 0,      -- 冗余摘要：列表免解析 JSONB
    CONSTRAINT fk_cv_canvas FOREIGN KEY (canvas_id) REFERENCES canvases(id) ON DELETE CASCADE
);
CREATE INDEX idx_cv_canvas ON canvas_versions(canvas_id) WHERE deleted = 0;
COMMENT ON TABLE  canvas_versions        IS '画布版本快照（2x 五轮）：手动存版本/列表/恢复/删除；每画布保留最近 30 个（service 修剪）';
COMMENT ON COLUMN canvas_versions.label  IS '版本名（用户可命名，缺省自动补时间戳）';
COMMENT ON COLUMN canvas_versions.snapshot IS '画布结构 JSON（同 canvases.snapshot 形状，只存 fileId 引用不嵌 base64）';
