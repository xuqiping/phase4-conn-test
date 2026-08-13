-- 蓝绿重建任务必须锁定目标快照和真实物理索引，禁止写入当前线上 write alias。
ALTER TABLE knowledge_index_jobs
    ADD COLUMN IF NOT EXISTS target_snapshot_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS target_physical_index VARCHAR(255);

COMMENT ON COLUMN knowledge_index_jobs.target_snapshot_id IS '仅蓝绿重建任务使用的目标快照 ID';
COMMENT ON COLUMN knowledge_index_jobs.target_physical_index IS '由服务端快照登记表解析的目标物理索引，客户端不可输入';

CREATE INDEX IF NOT EXISTS idx_index_job_snapshot_status
    ON knowledge_index_jobs(kb_id, target_snapshot_id, status)
    WHERE target_snapshot_id IS NOT NULL;

