-- L3（P02 Step8）：用量镜像表——云端账本的本地只增镜像（AC-045 客户端半边）。
-- 与云端 token_ledger 同构（kind 语义一致：1消费 2充值 3赠送 4人工调整），
-- 幂等键 UNIQUE 保证云端重推/断线补拉不重记；对账=本地 SUM 与云端余额互相核对。

CREATE TABLE usage_mirror (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         INTEGER NOT NULL,
    kind            INTEGER NOT NULL CHECK (kind IN (1, 2, 3, 4)),
    model           TEXT,
    amount_cents    INTEGER NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    synced_at       TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_mirror_user ON usage_mirror (user_id, id);
