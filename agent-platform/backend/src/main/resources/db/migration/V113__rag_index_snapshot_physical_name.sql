-- 保存真实物理索引名，避免控制面和索引创建器各自拼接造成 Alias 指向不存在目标。
ALTER TABLE rag_index_snapshots
    ADD COLUMN IF NOT EXISTS physical_index VARCHAR(255);

COMMENT ON COLUMN rag_index_snapshots.physical_index IS
    '由 KnowledgeIndexSchema 创建并登记的真实物理索引名；管理员接口不能直接输入该值';
