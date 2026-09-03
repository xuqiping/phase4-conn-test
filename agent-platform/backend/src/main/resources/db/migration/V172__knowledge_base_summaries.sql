-- =====================================================================
-- V172: C7 库级摘要 L-KB 层（规格 §9.1，WP4 Step1）
-- knowledge_base_summaries：每知识库的库级摘要（全文档 L1 摘要 map-reduce 浓缩产物）。
--   版本化：每次重生成插新行 version+1，旧版留档（回看摘要演化）；
--   UNIQUE(kb_id, version) 防并发 worker 双写。
--   status：READY（可用）/ ERROR（连续 3 次生成失败置错待手动，summary 空）。
--   stats JSONB：{docCount, batchCount, model, attempt}——触发判定（文档数变更 ≥10%）
--   与运维排查的数据来源。
-- 泄露面控制：summary/topics 仅 Service 内部读取注入 prompt，任何 API 不下发
--   （规格 §9.3——保密库成员也只拿到 RAG 答案本身，拿不到库级摘要原文）。
-- 生活比喻：图书馆总目录卡——不复印任何一本书，只记「这层楼都讲什么」；
--   书搬走 10% 或卡片放了 7 天，夜里闭馆时重写一张新卡。
-- =====================================================================
CREATE TABLE knowledge_base_summaries (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL DEFAULT 1,
    kb_id        BIGINT       NOT NULL,                          -- 所属知识库
    version      INT          NOT NULL,                          -- 库内递增（1 起）
    status       VARCHAR(16)  NOT NULL DEFAULT 'READY',          -- READY / ERROR
    summary      TEXT,                                            -- 库级摘要 ≤2000 字（ERROR 行为 NULL）
    topics       JSONB,                                           -- 主题清单 ["差旅","报销",...]
    stats        JSONB,                                           -- {docCount,batchCount,model,attempt}
    generated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),             -- 生成完成时刻（触发判定的 7 天基线）
    created_by   BIGINT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by   BIGINT,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_kb_summary_version UNIQUE (kb_id, version)
);

CREATE INDEX idx_kb_summary_kb_latest ON knowledge_base_summaries (kb_id, version DESC);

COMMENT ON TABLE knowledge_base_summaries IS 'C7 库级摘要（L-KB）：全文档 L1 map-reduce 浓缩，仅内部注入 prompt 不对外下发';
COMMENT ON COLUMN knowledge_base_summaries.status IS 'READY=可用 / ERROR=连续失败待手动（触发判定跳过 ERROR 行，沿用上一 READY 版）';
