-- P02 Step2：云端账号/计费核心表（db_schema §1.2，金额全 *_cents 整数）
-- 注意：已执行脚本不可改（Flyway 校验和）；变更走新版本号。

CREATE TABLE users (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  phone         VARCHAR(20) UNIQUE NOT NULL,
  password_hash TEXT NULL,
  nickname      VARCHAR(50),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted       SMALLINT NOT NULL DEFAULT 0
);

CREATE TABLE wallets (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id      BIGINT NOT NULL UNIQUE REFERENCES users (id),
  balance_cents BIGINT NOT NULL DEFAULT 0,
  gift_cents   BIGINT NOT NULL DEFAULT 0,
  version      INT NOT NULL DEFAULT 0
);

-- 只增不改的账本（审计基座）：余额=账本推导，钱包只是缓存
CREATE TABLE token_ledger (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id         BIGINT NOT NULL REFERENCES users (id),
  task_id         VARCHAR(64) NULL,
  kind            SMALLINT NOT NULL CHECK (kind IN (1, 2, 3, 4)), -- 1消费 2充值 3赠送 4人工调整
  model           VARCHAR(50),
  tokens_in       INT NOT NULL DEFAULT 0,
  tokens_out      INT NOT NULL DEFAULT 0,
  amount_cents    BIGINT NOT NULL,
  idempotency_key VARCHAR(64) UNIQUE NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_user_time ON token_ledger (user_id, created_at DESC);

CREATE TABLE recharge_orders (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id      BIGINT NOT NULL REFERENCES users (id),
  pack_code    VARCHAR(20) NOT NULL,
  amount_cents BIGINT NOT NULL,
  bonus_cents  BIGINT NOT NULL DEFAULT 0,
  channel      VARCHAR(10) NOT NULL,
  status       SMALLINT NOT NULL DEFAULT 0, -- 0待支付 1已支付 2已关闭 3已退款
  trade_no     VARCHAR(64),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  paid_at      TIMESTAMPTZ
);

CREATE INDEX idx_orders_user ON recharge_orders (user_id, created_at DESC);
