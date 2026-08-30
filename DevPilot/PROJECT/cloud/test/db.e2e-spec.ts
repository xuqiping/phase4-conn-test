// Step2 验收：PGlite 本地库上验证迁移产物——幂等键 UNIQUE 拦重复入账（安全清单核心）。
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { PGlite } from "@electric-sql/pglite";

const FLYWAY_DIR = join(__dirname, "..", "flyway");

async function freshDb() {
  const db = new PGlite(); // 内存库：每个用例干净起点
  for (const f of readdirSync(FLYWAY_DIR).filter((x) => x.endsWith(".sql")).sort()) {
    await db.exec(readFileSync(join(FLYWAY_DIR, f), "utf8"));
  }
  return db;
}

describe("云端建表 V1/V3（P02 Step2）", () => {
  it("迁移产出五表 + 账本幂等键 UNIQUE 拦重复", async () => {
    const db = await freshDb();
    const tables = (
      await db.sql`SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename`
    ).rows.map((r) => (r as { tablename: string }).tablename);
    for (const t of ["users", "wallets", "token_ledger", "recharge_orders", "api_identities"]) {
      expect(tables).toContain(t);
    }

    // 建用户 → 同 idempotency_key 第二笔入账必须被 UNIQUE 拒绝
    await db.sql`INSERT INTO users (phone) VALUES ('13800000000')`;
    await db.sql`
      INSERT INTO token_ledger (user_id, kind, amount_cents, idempotency_key)
      VALUES (1, 1, -100, 'nonce-abc')`;
    await expect(
      db.sql`
        INSERT INTO token_ledger (user_id, kind, amount_cents, idempotency_key)
        VALUES (1, 1, -100, 'nonce-abc')`,
    ).rejects.toThrow();
    await db.close();
  }, 30000);

  it("kind 越界值被 CHECK 拦（账本类型枚举）", async () => {
    const db = await freshDb();
    await db.sql`INSERT INTO users (phone) VALUES ('13800000001')`;
    await expect(
      db.sql`
        INSERT INTO token_ledger (user_id, kind, amount_cents, idempotency_key)
        VALUES (1, 9, -100, 'nonce-xyz')`,
    ).rejects.toThrow();
    await db.close();
  }, 30000);

  it("一用户一钱包：第二个钱包被 UNIQUE(user_id) 拦", async () => {
    const db = await freshDb();
    await db.sql`INSERT INTO users (phone) VALUES ('13800000002')`;
    await db.sql`INSERT INTO wallets (user_id) VALUES (1)`;
    await expect(db.sql`INSERT INTO wallets (user_id) VALUES (1)`).rejects.toThrow();
    await db.close();
  }, 30000);
});
