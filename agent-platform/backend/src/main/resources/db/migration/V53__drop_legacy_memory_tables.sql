-- V53: DROP legacy user_memories 栈（计划12 · H'-3，切流后死表）
-- 背景：新栈 9 表（V47）+ memory_conflicts 放宽（V48）+ memory_recall_acl（V49）+
--       memory_recall_scope_prefs（V50）+ 锁列（V51）+ 存量成员回填（V52）已全部落地，
--       H'-1 活聊天切流（ChatSessionService 召回/写入走新栈）已完成。
--       旧 user_memories / user_memory_projects 再无任何读写入口（MemoryController 全族、
--       MemoryService、UserMemoryMapper 均已删），可安全 DROP。
-- 依赖顺序：user_memory_projects（子，V33 建 FK REFERENCES user_memories(id) ON DELETE CASCADE）
--           先 DROP，再 DROP user_memories（父）。
-- 保留：memory_conflicts 表 + V48 放宽旧列（新栈 MemoryConflictResolutionService /
--       MemoryConsolidationTxService / MemoryConsolidationController 用，不撤）。
-- 老数据不迁移（项目未上线；旧设计备份分支 agent-platform-old_jiyi @ a1163c53）。
DROP TABLE IF EXISTS user_memory_projects;
DROP TABLE IF EXISTS user_memories;
