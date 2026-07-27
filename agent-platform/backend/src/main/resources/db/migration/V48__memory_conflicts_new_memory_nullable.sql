-- V48: memory_conflicts 老列放宽（计划12·迭代 A 补丁）。
-- V47 扩了 tag_id + summary_id 走新模型（总结时序互斥冲突），但老列 new_memory JSONB NOT NULL（V27）
-- 阻碍新模型 insert——新冲突只设 tag_id + summary_id + status，不写 new_memory。
-- V47 已执行不可改（Flyway checksum 锁），本迁移单独 drop NOT NULL。
-- 老列（block_label/new_memory/new_embedding/existing_memory_ids/ask_text/expires_at）H 收尾随旧表语义废弃。
ALTER TABLE memory_conflicts ALTER COLUMN new_memory DROP NOT NULL;
COMMENT ON COLUMN memory_conflicts.new_memory IS '旧模型 JSONB（V27）。新模型（V47+）冲突只设 tag_id+summary_id，本列留空。H 收尾随旧表废弃';
