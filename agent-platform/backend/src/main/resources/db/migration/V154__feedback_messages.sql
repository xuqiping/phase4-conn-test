-- ============================================================
-- V154: 反馈留言表（19x 未解决#1：审核后 admin 可继续给用户留言，每次留言用户收通知）
--   feedback_messages 留言线程：目标=建议/提问，发送方=ADMIN/USER（当前仅 ADMIN 留言入口；
--                     USER 角色列预留——若后续开放用户回复，通知方向反之）
--   同步放宽 feedback_notifications 类型 CHECK：新增 SUGGESTION_MESSAGE / QUESTION_MESSAGE
--   （PG CHECK 不可 ALTER，DROP+ADD 重建——同表无数据依赖，重建零成本）
--
-- 回滚（rollback）：
--   ALTER TABLE feedback_notifications DROP CONSTRAINT chk_feedback_notify_type;
--   ALTER TABLE feedback_notifications ADD CONSTRAINT chk_feedback_notify_type
--       CHECK (type IN ('SUGGESTION_REVIEWED','QUESTION_ANSWERED'));
--   DROP TABLE IF EXISTS feedback_messages;
-- ============================================================

CREATE TABLE feedback_messages (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 留言目标：建议 / 提问（二选一，+ target_id 定位；不设外键——两类目标两表，应用层校验存在性）
    target_type         VARCHAR(16)  NOT NULL,
    target_id           BIGINT       NOT NULL,
    sender_id           BIGINT       NOT NULL,
    -- 发送方角色（通知方向按角色判定：ADMIN→告用户，USER→预留告 admin）
    sender_role         VARCHAR(8)   NOT NULL,
    content             VARCHAR(2000) NOT NULL,
    created_by          BIGINT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by          BIGINT,
    updated_at          TIMESTAMPTZ,
    deleted             INTEGER      NOT NULL DEFAULT 0,
    version             INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT chk_feedback_message_target CHECK (target_type IN ('SUGGESTION','QUESTION')),
    CONSTRAINT chk_feedback_message_role   CHECK (sender_role IN ('ADMIN','USER'))
);
-- 线程读取：按目标正序翻全部（单目标留言量级小，无需分页索引之外的覆盖）
CREATE INDEX idx_feedback_message_target ON feedback_messages (target_type, target_id, id) WHERE deleted = 0;

-- 通知类型 CHECK 重建（新增留言两类）
ALTER TABLE feedback_notifications DROP CONSTRAINT chk_feedback_notify_type;
ALTER TABLE feedback_notifications ADD CONSTRAINT chk_feedback_notify_type
    CHECK (type IN ('SUGGESTION_REVIEWED','QUESTION_ANSWERED','SUGGESTION_MESSAGE','QUESTION_MESSAGE'));
