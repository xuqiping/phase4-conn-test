-- V120: 防御性修复运行库 stored_files.file_id 结构漂移（RAG Phase 4 准入项）
-- 背景：部分运行库的 stored_files 建表早于/游离于 Flyway V40，file_id 实际为 VARCHAR(16)，
--   而业务写入的是 UUID+ext（最长 41+字符）→ 报「value too long for type character varying(16)」，
--   阻断知识库新文档上传（2026-08-14 Qwen 冒烟实证）。
-- 仓库 V40 一直定义为 VARCHAR(128)，本迁移把漂移库幂等拉齐；已正确的库为 no-op。
-- 漂移库中不可能存在超长存量行（超长写入本就会失败），无需 USING 转换。
ALTER TABLE stored_files ALTER COLUMN file_id TYPE VARCHAR(128);
