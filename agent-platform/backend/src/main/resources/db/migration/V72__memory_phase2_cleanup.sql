-- ============================================================
-- V67 · 记忆系统二期 P1 · 一期清理（FR-006）
-- 用途：turns 纯个人域化 + 一期 recall-acl 表下线。
--   ① memory_turns 删四列：project_ids / born_personal / departed_project_ids / deleted_project_ids
--      —— 二期定案：个人对话全量进个人流水账（原文不出个人域），项目记忆改走
--         memory_project_entries（收录规则路由蒸馏，V65），turns 不再挂项目。
--   ② DROP TABLE memory_recall_acl —— 一期 reader×target ACL 矩阵废弃（代码 6a 已下线，
--      二期项目记忆=蒸馏条目，ACTIVE 成员即可读，矩阵失去存在意义）。
-- 关联：V47 建 turns 四列；V49 建 memory_recall_acl；V65 建 rules/entries（替代方案）。
-- ⚠️ 已执行脚本不可改；本脚本执行后老代码（读写四列）必须已全部下线（Step 6b）。
-- ============================================================

ALTER TABLE memory_turns
    DROP COLUMN IF EXISTS project_ids,
    DROP COLUMN IF EXISTS born_personal,
    DROP COLUMN IF EXISTS departed_project_ids,
    DROP COLUMN IF EXISTS deleted_project_ids;

DROP TABLE IF EXISTS memory_recall_acl;
