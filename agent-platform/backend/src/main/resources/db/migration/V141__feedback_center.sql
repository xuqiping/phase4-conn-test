-- ============================================================
-- V141: 反馈中心四表（19x#1/#2/#3，三合一「反馈与帮助」模块）
--   feedback_suggestions   建议台：用户提需求（username 快照+截图附件 jsonb），admin 审核回复
--   feedback_questions     提问台：用户提问 admin 回答，is_public=true 即 FAQ（公开视图脱敏）
--   help_articles          说明台：admin 维护 markdown 文章，slug 唯一短链，硬删（拍板：释放 slug 占坑）
--   feedback_notifications 站内通知：审核/回答结果推用户（铃铛轮询 count 走部分索引）
--
-- 通用决策：
--   - 状态机列 CHECK 硬卡（PENDING/ADOPTED/REJECTED/CLOSED；OPEN/ANSWERED/CLOSED），
--     翻转全走 service 层条件 UPDATE 抢态（0 行=已被处理）——同 V140 payment 先例。
--   - username 为提交时快照列：改名不影响历史展示（admin 视角溯源用）；
--     FAQ 公开视图 SQL 不 SELECT 该列（脱敏在字段不存在层做，非置空）。
--   - 四表均带 BaseEntity 六列（created_by/at + updated_by/at + deleted + version）；
--     help_articles 虽拍板硬删，deleted 列保留占位（@TableLogic 统一实体基类，硬删走显式 DELETE）。
--
-- 回滚（rollback，全新表——DROP 即功能消失，数据丢需确认）：
--   DROP TABLE IF EXISTS feedback_notifications;
--   DROP TABLE IF EXISTS help_articles;
--   DROP TABLE IF EXISTS feedback_questions;
--   DROP TABLE IF EXISTS feedback_suggestions;
-- ============================================================

-- ---------- 建议台 ----------
CREATE TABLE feedback_suggestions (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT      NOT NULL,
    -- 提交时用户名快照（改名不追溯；admin 列表直接展示免 JOIN）
    username            VARCHAR(64) NOT NULL,
    title               VARCHAR(120) NOT NULL,
    content             VARCHAR(4000) NOT NULL,
    -- 截图附件 fileId 数组（≤3，提交时逐 id 校验属主=提交人）；JSONB 免子表
    attachment_file_ids JSONB,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    reply               TEXT,
    reviewed_by         BIGINT,
    reviewed_at         TIMESTAMPTZ,
    created_by          BIGINT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by          BIGINT,
    updated_at          TIMESTAMPTZ,
    deleted             INTEGER      NOT NULL DEFAULT 0,
    version             INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT chk_suggestion_status CHECK (status IN ('PENDING','ADOPTED','REJECTED','CLOSED'))
);
-- admin 审核台按状态翻页；用户「我的建议」按时间倒序
CREATE INDEX idx_suggestion_status_time ON feedback_suggestions (status, created_at DESC) WHERE deleted = 0;
CREATE INDEX idx_suggestion_user_time   ON feedback_suggestions (user_id, created_at DESC) WHERE deleted = 0;

-- ---------- 提问台 ----------
CREATE TABLE feedback_questions (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT      NOT NULL,
    username            VARCHAR(64) NOT NULL,
    title               VARCHAR(120) NOT NULL,
    content             VARCHAR(4000) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    -- admin markdown 回答原文（渲染侧 html:false 防 XSS）
    answer              VARCHAR(8000),
    -- 公开即 FAQ；公开视图绝不带 username（脱敏）
    is_public           BOOLEAN     NOT NULL DEFAULT FALSE,
    answered_by         BIGINT,
    answered_at         TIMESTAMPTZ,
    created_by          BIGINT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by          BIGINT,
    updated_at          TIMESTAMPTZ,
    deleted             INTEGER      NOT NULL DEFAULT 0,
    version             INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT chk_question_status CHECK (status IN ('OPEN','ANSWERED','CLOSED'))
);
-- FAQ 公开列表（按回答时间倒序）；admin 按状态筛；用户「我的提问」
CREATE INDEX idx_question_faq         ON feedback_questions (answered_at DESC) WHERE deleted = 0 AND is_public = TRUE;
CREATE INDEX idx_question_status_time ON feedback_questions (status, created_at DESC) WHERE deleted = 0;
CREATE INDEX idx_question_user_time   ON feedback_questions (user_id, created_at DESC) WHERE deleted = 0;

-- ---------- 说明台 ----------
CREATE TABLE help_articles (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 英文短链名（用户端 /help/articles/{slug} 直达）；硬删释放占坑
    slug                VARCHAR(80)  NOT NULL,
    title               VARCHAR(120) NOT NULL,
    category            VARCHAR(40)  NOT NULL DEFAULT '通用',
    sort_order          INTEGER      NOT NULL DEFAULT 0,
    content_md          TEXT         NOT NULL,
    published           BOOLEAN      NOT NULL DEFAULT FALSE,
    published_at        TIMESTAMPTZ,
    created_by          BIGINT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by          BIGINT,
    updated_at          TIMESTAMPTZ,
    deleted             INTEGER      NOT NULL DEFAULT 0,
    version             INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT chk_article_slug CHECK (slug ~ '^[a-z0-9-]+$')
);
CREATE UNIQUE INDEX uk_help_article_slug ON help_articles (slug);
-- 用户目录：已发布按分类+排序
CREATE INDEX idx_article_pub_cat ON help_articles (category, sort_order, id) WHERE published = TRUE;

-- ---------- 站内通知 ----------
CREATE TABLE feedback_notifications (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT      NOT NULL,
    type                VARCHAR(32) NOT NULL,
    -- 关联建议/提问 id（点击跳对应 tab）
    ref_id              BIGINT      NOT NULL,
    -- 纯文本摘要（标题截断+结论；不渲染 HTML 防 XSS）
    message             VARCHAR(500) NOT NULL,
    read_at             TIMESTAMPTZ,
    created_by          BIGINT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by          BIGINT,
    updated_at          TIMESTAMPTZ,
    deleted             INTEGER      NOT NULL DEFAULT 0,
    version             INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT chk_feedback_notify_type CHECK (type IN ('SUGGESTION_REVIEWED','QUESTION_ANSWERED'))
);
-- 铃铛未读 count 高频轮询：部分索引只装未读行
CREATE INDEX idx_feedback_notify_unread ON feedback_notifications (user_id) WHERE read_at IS NULL AND deleted = 0;
CREATE INDEX idx_feedback_notify_user   ON feedback_notifications (user_id, id DESC) WHERE deleted = 0;
