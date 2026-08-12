-- ============================================================================
-- V102 · 认证系统增强 · 多凭证账号模型
--
-- 目的：把现有单一「账密 + 钉钉」认证升级为多通道（邮箱/手机/微信），
--       核心是将「用户登录方式」从 users 表单字段解耦为独立凭证表 user_credential，
--       一个用户可持多条凭证（邮箱 + 手机 + 微信 + 密码），任一可用凭证都能登录同一账号。
--
-- 设计文档：workflow_output/docs/specs/认证系统增强设计.md §7
-- 计划：     workflow_output/docs/plans/认证系统增强.plan.md Chunk A
--
-- 幂等：全部 IF NOT EXISTS / ON CONFLICT DO NOTHING，重复执行无副作用。
-- 回滚：见 plan.md §七「可回滚」——删除凭证表 + 3 个新列 + 2 个索引即可，
--       不触碰存量 users.password，账密登录完全不受影响。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. users 表补充三个新列：手机号、微信 unionid、微信 openid。
--    均为可空：仅当用户用该方式注册/绑定时才有值，账密/钉钉用户保持 NULL。
-- ----------------------------------------------------------------------------
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone VARCHAR(20);
ALTER TABLE users ADD COLUMN IF NOT EXISTS wechat_unionid VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS wechat_openid VARCHAR(100);

COMMENT ON COLUMN users.phone          IS '手机号（手机验证码注册/绑定时填），可空';
COMMENT ON COLUMN users.wechat_unionid IS '微信开放平台 unionId（跨应用稳定标识），可空';
COMMENT ON COLUMN users.wechat_openid  IS '微信开放平台 openId（应用内标识），可空';

-- 手机号 / 微信 unionid 全局唯一，但可空（部分唯一索引只索引非 NULL 行）。
-- 唯一性兜底并发注册：同一手机号/微信被并发绑两个账号时，第二个 INSERT 抛冲突 → service 转 409。
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_phone
    ON users (phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_wechat_unionid
    ON users (wechat_unionid) WHERE wechat_unionid IS NOT NULL;

-- ----------------------------------------------------------------------------
-- 2. 用户凭证表 user_credential：一个用户一行账号、N 行凭证。
--
--    生活化比喻：users 是「人」，user_credential 是「这人手里的几把钥匙」——
--    密码是一把、邮箱是一把、手机是一把、微信是一把。有任意一把就能开门（登录），
--    找回密码、绑定/解绑、凭证验证状态都精确到某一把钥匙，而不是整个账号。
--
--    credential_type 取值：PASSWORD（账密）/ EMAIL（邮箱）/ PHONE（手机）/ WECHAT（微信）/ DINGTALK（钉钉）。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_credential (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- 归属用户（外键指向 users.id）。一个 user_id 下同类凭证唯一（见 uk_user_credential_type）。
    user_id         BIGINT NOT NULL REFERENCES users(id),

    -- 凭证类型：决定 identifier 字段的语义（见下）。
    credential_type VARCHAR(20) NOT NULL,

    -- 该凭证的唯一标识。按 credential_type 不同语义不同：
    --   PASSWORD → username；EMAIL → 邮箱地址；PHONE → 手机号；
    --   WECHAT   → unionid（优先，无则用 openid）；DINGTALK → unionid。
    identifier      VARCHAR(200) NOT NULL,

    -- 密钥材料。仅 PASSWORD 类型存 BCrypt 哈希；其余类型（手机/微信）无密码概念，为 NULL。
    secret          VARCHAR(200),

    -- 该凭证是否已验证真实性（邮箱点过激活链接 / 手机收过验证码 / 微信授权过）。
    -- 关键安全语义：未验证的 EMAIL 凭证【不可用于找回密码】（否则注册填假邮箱 → 找回发不到 → 漏洞）。
    verified        BOOLEAN NOT NULL DEFAULT FALSE,

    -- 首次验证通过的时间；未验证为 NULL。
    verified_at     TIMESTAMP WITH TIME ZONE,

    -- 与全项目 BaseEntity 对齐的四列：逻辑删除 + 乐观锁 + 时间戳。
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted         INT NOT NULL DEFAULT 0,   -- 逻辑删除：0 正常 / 1 已解绑（解绑走软删留痕）
    version         INT NOT NULL DEFAULT 0    -- 乐观锁
);

COMMENT ON TABLE  user_credential                    IS '用户凭证表：一个用户多条登录凭证（密码/邮箱/手机/微信/钉钉）';
COMMENT ON COLUMN user_credential.user_id            IS '归属用户 users.id';
COMMENT ON COLUMN user_credential.credential_type    IS 'PASSWORD/EMAIL/PHONE/WECHAT/DINGTALK';
COMMENT ON COLUMN user_credential.identifier         IS '凭证标识：用户名/邮箱/手机号/openid/unionid';
COMMENT ON COLUMN user_credential.secret             IS '仅 PASSWORD 存 BCrypt 哈希，其余为 NULL';
COMMENT ON COLUMN user_credential.verified           IS '是否已验证（未验证邮箱不可用于找回密码）';
COMMENT ON COLUMN user_credential.verified_at        IS '首次验证通过时间';
COMMENT ON COLUMN user_credential.deleted            IS '逻辑删除：0-正常，1-已解绑';

-- 登录查询索引：按 (类型 + 标识) 定位唯一凭证（手机号登录、微信登录的命中路径）。
-- 部分唯一索引（deleted=0）保证并发注册同一手机号/邮箱时第二个冲突报错，而非产生脏数据。
CREATE UNIQUE INDEX IF NOT EXISTS uk_credential_type_identifier
    ON user_credential (credential_type, identifier) WHERE deleted = 0;

-- 一个用户同类凭证唯一（一个人不能绑两个邮箱凭证；换绑走解绑旧 + 绑新）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_credential_type
    ON user_credential (user_id, credential_type) WHERE deleted = 0;

-- 设置页按用户列出所有凭证的查询索引。
CREATE INDEX IF NOT EXISTS idx_credential_user
    ON user_credential (user_id) WHERE deleted = 0;

-- ----------------------------------------------------------------------------
-- 3. 存量数据迁移：把现有账密用户、邮箱落成凭证行。
--    ON CONFLICT DO NOTHING 保证幂等（重复执行不重复插）。
--
--    关键安全决策：存量 PASSWORD 凭证 verified=TRUE（老用户密码立即可用，不影响登录）；
--                 存量 EMAIL 凭证 verified=FALSE（老用户邮箱本就没验证过，必须重新验证
--                 才能用于找回密码——这是堵找回漏洞的必要措施，不能标 TRUE）。
-- ----------------------------------------------------------------------------
INSERT INTO user_credential (user_id, credential_type, identifier, secret, verified, created_at)
SELECT id, 'PASSWORD', username, password, TRUE, created_at
FROM users
WHERE deleted = 0 AND username IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO user_credential (user_id, credential_type, identifier, verified, created_at)
SELECT id, 'EMAIL', email, FALSE, created_at
FROM users
WHERE deleted = 0 AND email IS NOT NULL AND email != ''
ON CONFLICT DO NOTHING;
