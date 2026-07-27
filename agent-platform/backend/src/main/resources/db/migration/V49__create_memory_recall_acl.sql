-- V49: 计划12 · 个人记忆重设计 · 迭代 I1 · 项目记忆读取 ACL（readableAuthors 授权表）。
-- 总体设计：项目工程文档/设计/个人记忆重设计-总体设计.md §3.6 + §6 向量 14。
-- 子 plan：项目工程文档/计划/计划12-I1-ACL前置.md。
--
-- 版本偏移：原 plan 硬绑 V48=recall_acl，但 V48 已被迭代 A 补丁（memory_conflicts 放宽）占用，
--   故顺延 V49（H DROP 顺延 V50）。版本号软标签，详见开发进度1.md。
--
-- 语义（对照 §3.6）：
--   readableAuthors(projectId, reader) = 项目内某 reader 可读哪些【作者】的流水账（召回 + 总结取数共用）。
--   - owner 兜底全读（无需本表行）→ 返回项目全部成员 user_id（含 DEPARTED 曾赋权，保交接）。
--   - admin/member → 本表 reader→target 授权集 ∪ {reader 自己}；默认并非项目全员可读。
--   - recall_admin=true 的 admin 仅多「配 ACL」权（I2 端点判），【读】仍走 ACL 集 + 自己，不扩读。
--   - summary 不受 ACL 影响（恒只读自己，向量 14）。
--   - DEPARTED 曾赋权的 target 仍保留行（保交接）；是否纳入召回由 L10 离职开关在 I3 接入时过滤，本表不滤。
--
-- 无 deleted 列：ACL 行 = 显式授权事实，撤销 = DELETE 行（append-only + 删，无软删必要，同 members/coverage 风格）。
-- 审计：created_by 记「谁授权」（向量 15：共享/挂载/删除/裁决留审计）。
--
-- 可逆（运维考量·可回滚）：本迁移建表无数据，逆操作 = `DROP TABLE memory_recall_acl;`（手动回滚或 H 收尾随旧栈清理）。
CREATE TABLE memory_recall_acl (
    id              BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id      BIGINT                   NOT NULL REFERENCES projects(id) ON DELETE CASCADE,   -- 项目删除随级联清
    reader_user_id  BIGINT                   NOT NULL REFERENCES users(id) ON DELETE CASCADE,       -- 谁读（被授权方）
    target_user_id  BIGINT                   NOT NULL REFERENCES users(id) ON DELETE CASCADE,       -- 读谁的（被读作者）
    created_by      BIGINT,                                                                            -- 授权操作人（审计）
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    -- 同项目同 reader→target 只一行授权；NULLS NOT DISTINCT 保险（三列均 NOT NULL，纯防御）
    CONSTRAINT uk_memory_recall_acl UNIQUE (project_id, reader_user_id, target_user_id)
);

-- resolver 主查询：按 (project_id, reader) 取全部 target → 单索引覆盖。
CREATE INDEX idx_memory_recall_acl_project_reader ON memory_recall_acl(project_id, reader_user_id);

COMMENT ON TABLE  memory_recall_acl IS '项目流水账读取授权（reader→target）。owner 兜底全读无需行；admin/member 按本表+自己；recall_admin 仅配权不扩读';
COMMENT ON COLUMN memory_recall_acl.reader_user_id IS '被授权读者；召回/总结取数时按本表取 target 集';
COMMENT ON COLUMN memory_recall_acl.target_user_id IS '被读作者；DEPARTED 后保交接不删行，L10 开关在 I3 过滤';
COMMENT ON COLUMN memory_recall_acl.created_by     IS '授权操作人（审计，向量 15）；撤销 = DELETE 行';
