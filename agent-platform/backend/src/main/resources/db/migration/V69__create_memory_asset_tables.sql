-- V69: 记忆系统二期 P3 · 多模态文件记忆（memory_asset_memories / memory_asset_chunks，数据层）。
-- 总体设计：项目工程文档/设计/记忆系统二期-项目自动收录与多模态-总体设计.md（§3.4/§3.5）。
-- 子 plan：workflow_output/docs/plans/记忆系统二期_P3多模态.plan.md（Step 1）。规格 FR-201~205。
--
-- 语义：
--   ① 一文件一记忆：用户聊天上传一个文件 = 个人域一条 memory_asset_memories（「那个课件」心智）；
--   ② 分块深读层：memory_asset_chunks 存每页/每段要点 + halfvec 向量 + page_ref 语义锚点，
--      reflect 判深读时向量 top-k 进 prompt，回答引用须带 page_ref（D-19.12 幻觉对冲）；
--   ③ 状态机：PROCESSING（上传即返，异步 ingestion）→ READY / FAILED（可重试，retry_count 防无限重试）。
--
-- 顺带修正（P3 首个使用方才暴露）：V65 memory_project_entries.file_id 建为 BIGINT，
--   但 stored_files.file_id 是 VARCHAR(128)（UUID+ext 自然主键），类型不匹配无法引用。
--   当前全表无 FILE 条目（content_type 恒 TEXT，列全 NULL），直接 ALTER TYPE 无损。

CREATE TABLE memory_asset_memories (
    id              BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_user_id   BIGINT                   NOT NULL REFERENCES users(id) ON DELETE CASCADE,     -- 文件记忆=个人域资产，随用户死
    file_id         VARCHAR(128)             NOT NULL REFERENCES stored_files(file_id),           -- 原文件登记（V40，UUID+ext 自然主键）
    file_kind       VARCHAR(10)              NOT NULL
                    CHECK (file_kind IN ('IMAGE','DOC','PPT','PDF','AUDIO','VIDEO','OTHER')),     -- 按 mime 归类的模态
    original_name   VARCHAR(255)             NOT NULL,                                             -- 冗余存文件名（stored_files CLEANED 后仍可展示「原文件已删除」）
    l1_summary      TEXT,                                                                          -- 一句话总结（ingestion READY 后填；FAILED/弱记忆可空）
    l2_detail       TEXT,                                                                          -- 结构化详述（章节/每页要点/关键图描述）
    tag_ids         BIGINT[]                 NOT NULL DEFAULT '{}',                                -- 个人标签库归一（与对话记忆同体系，召回自然合流）
    ingest_status   VARCHAR(15)              NOT NULL DEFAULT 'PROCESSING'
                    CHECK (ingest_status IN ('PROCESSING','READY','FAILED')),
    ingest_error    VARCHAR(500),                                                                  -- FAILED 原因（固定话术，不透传异常细节）
    retry_count     INT                      NOT NULL DEFAULT 0,                                   -- 重试次数（上限由 worker 硬卡）
    weak_memory     BOOLEAN                  NOT NULL DEFAULT FALSE,                               -- 降级弱记忆=TRUE（仅元数据+「读不懂内容」，FR-205）
    created_by      BIGINT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by      BIGINT,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted         INT                      NOT NULL DEFAULT 0,
    version         INT                      NOT NULL DEFAULT 0
);

-- 一文件一记忆（file_id 本身全局唯一，天然防重）
CREATE UNIQUE INDEX uk_memory_asset_memories_file ON memory_asset_memories(file_id);
-- 召回主查询：按用户查 READY 文件记忆
CREATE INDEX idx_memory_asset_memories_owner_status ON memory_asset_memories(owner_user_id, ingest_status) WHERE deleted = 0;

COMMENT ON TABLE  memory_asset_memories IS '文件记忆（二期 P3）：一文件一条目，个人域资产。ingestion 异步出 l1/l2+tags；FAILED 可重试；弱记忆=读不懂内容降级';
COMMENT ON COLUMN memory_asset_memories.file_id      IS 'stored_files 登记行（V40 ACL 咽喉点 FileStorageService.load 强校验 owner）';
COMMENT ON COLUMN memory_asset_memories.file_kind    IS 'IMAGE/DOC/PPT/PDF/AUDIO/VIDEO/OTHER，按 mime 归类，决定 ingestion 分派';
COMMENT ON COLUMN memory_asset_memories.tag_ids      IS '个人标签库 id 集（MemoryTagResolver 归一），与对话记忆共享标签召回合流';
COMMENT ON COLUMN memory_asset_memories.ingest_status IS 'PROCESSING=解析中；READY=可召回；FAILED=可重试（retry_count 硬卡上限）';
COMMENT ON COLUMN memory_asset_memories.weak_memory  IS 'TRUE=模态降级弱记忆（OCR/转写失败，仅元数据+明示话术，FR-205）';

CREATE TABLE memory_asset_chunks (
    id              BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_memory_id BIGINT                   NOT NULL REFERENCES memory_asset_memories(id) ON DELETE CASCADE,  -- 随记忆死
    chunk_no        INT                      NOT NULL,                                                          -- 顺序号（页序/段序）
    chunk_text      TEXT                     NOT NULL,                                                          -- 页要点/段落文本
    chunk_embedding halfvec(2048),                                                                              -- 深读向量召回（向量统一 halfvec(2048) 铁律）
    page_ref        VARCHAR(50),                                                                                -- 语义锚点：「第12页」/「00:03:25」，可反向定位原文
    created_by      BIGINT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by      BIGINT,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted         INT                      NOT NULL DEFAULT 0,
    version         INT                      NOT NULL DEFAULT 0,
    CONSTRAINT uk_memory_asset_chunks_no UNIQUE (asset_memory_id, chunk_no)
);

-- 深读向量召回按记忆内 top-k（embedding 走 halfvec_ops，索引由查询模式决定，暂不加向量索引——行数预期低）
COMMENT ON TABLE  memory_asset_chunks IS '文件分块（深读层）：每页/每段要点+向量+page_ref 锚点。reflect 判深读时向量 top-k 进 prompt，引用须带 page_ref';
COMMENT ON COLUMN memory_asset_chunks.page_ref IS '语义锚点（页码/时间戳），回答引用必带，可审计可跳转原文位置（D-19.12）';

-- 修正 V65 类型失配：stored_files.file_id 是 VARCHAR(128)，条目引用列同步（当前无 FILE 条目，列全 NULL 无损）
ALTER TABLE memory_project_entries ALTER COLUMN file_id TYPE VARCHAR(128);
COMMENT ON COLUMN memory_project_entries.file_id IS 'content_type=FILE 时指向 stored_files.file_id（VARCHAR，UUID+ext）';
