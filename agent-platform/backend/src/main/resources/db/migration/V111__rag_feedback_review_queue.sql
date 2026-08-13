-- RAG 在线反馈待审核队列：一行代表一次用户反馈，任何反馈都不能直接修改线上排序。
CREATE TABLE IF NOT EXISTS rag_feedback_reviews (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,                 -- 反馈所属租户，用于隔离审核范围
    kb_id BIGINT NOT NULL,                     -- 对应知识库 ID
    eval_result_id BIGINT,                     -- 可选：关联 rag_eval_results.id，便于定位评测/在线结果
    category VARCHAR(40) NOT NULL,              -- 固定枚举：NOT_RELEVANT/OUTDATED/WRONG_CITATION/INCOMPLETE
    comment VARCHAR(1000),                      -- 用户补充说明；不存完整 Prompt、Chunk 或模型输出
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING/APPROVED/REJECTED
    submitted_by BIGINT NOT NULL,               -- 提交用户
    reviewed_by BIGINT,                         -- 审核管理员
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_rag_feedback_review_queue
    ON rag_feedback_reviews(tenant_id, status, created_at);
