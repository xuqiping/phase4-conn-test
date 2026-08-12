-- ============================================================
-- V78: memory_tags 唯一索引改 partial + memory:manage 权限 seed
--
-- 背景（V77 大类改造 E2E 暴露）：
--   uk_memory_tags_user_subject_topic 原为非 partial 唯一索引（含软删行）。
--   repair 归并时 survivor 改 topic=大类，若同 (user,subject,topic) 已被【软删的】
--   loser 占着 → 撞 UNIQUE → 409 整事务回滚。软删行不应再占唯一槽位（@TableLogic 语义）。
--   注：V77 已加 survivor 优先取干净标签规避【live 标签】撞 UNIQUE；本迁移补【软删行】撞 UNIQUE。
--
-- 78.1 唯一索引改 partial（仅 deleted=0 行参与唯一）—— 与 idx_memory_tags_user 同范式。
--     软删行不再占 (user_id,subject,topic) 槽 → survivor 可放心改 topic，repair 不再因软删 loser 回滚。
--     安全性：原非 partial 已保证 deleted=0 行无重复，故 partial 重建必成功。
--     注：原约束是 UNIQUE CONSTRAINT（非裸索引），DROP INDEX 会被「约束依赖该索引」拒绝，
--     故先 DROP CONSTRAINT（连带删索引），DROP INDEX 仅作幂等兜底（已删则 no-op）。
ALTER TABLE memory_tags DROP CONSTRAINT IF EXISTS uk_memory_tags_user_subject_topic;
DROP INDEX IF EXISTS uk_memory_tags_user_subject_topic;
CREATE UNIQUE INDEX uk_memory_tags_user_subject_topic
    ON memory_tags (user_id, subject, topic)
    NULLS NOT DISTINCT
    WHERE deleted = 0;

-- 78.2 memory:manage 权限 seed（V77 repair 端点 @RequirePermission("memory:manage")）
INSERT INTO permissions (name, code, resource, action) VALUES
    ('记忆管理', 'memory:manage', 'memory', 'manage')
ON CONFLICT (code) DO NOTHING;

-- 78.3 系统管理员：memory:manage（repair 一次性回填工具，仅管理员）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'admin' AND p.code = 'memory:manage'
ON CONFLICT DO NOTHING;
