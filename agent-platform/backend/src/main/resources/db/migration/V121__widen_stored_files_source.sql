-- V121: 放宽 stored_files.source VARCHAR(16)→VARCHAR(32)（RAG Phase 4 实测修复）
-- 根因（Phase 4 复现实证）：ParseArtifactService.STORAGE_SOURCE = "KB_PARSE_ARTIFACT" 为 17 字符，
--   超过 V40 定义的 source VARCHAR(16)，知识库文档解析产物落库报「值太长了(16)」，文档转 FAILED。
--   2026-08-14 冒烟曾将该错误归因于 file_id 长度漂移（V120 已防御性拉齐 file_id=128，本地库本就为 128）；
--   实际超长列是 source。与 V60 放宽 assets.media_type 16→32 同一先例。
-- 现有存量值（KB/WORKFLOW/CHAT/PREVIEW 等）均 ≤16，放宽为纯扩容，无数据转换。
ALTER TABLE stored_files ALTER COLUMN source TYPE VARCHAR(32);
