-- V37: RAG 召回升级 Phase3 收尾 — rag_retrieval_logs 加 L1 专用 trace 列。
-- 背景：Phase3 接入 L1 文档向量通道后，L1 贡献仅经 docL1Sim boost 隐式体现在最终 rerank 分里，
-- trace 无独立可观测列 → 出问题/调 RRF 权重时查不动 L1 单通道贡献。
-- 纯加列（TEXT，可空），append-only 审计表，无回填、无锁竞争。
ALTER TABLE rag_retrieval_logs ADD COLUMN candidates_l1 TEXT;
