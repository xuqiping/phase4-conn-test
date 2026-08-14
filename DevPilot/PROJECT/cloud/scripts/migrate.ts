// 本地迁移执行器：PGlite（进程内 Postgres，无服务进程、无管理员权限问题）跑 ./flyway 脚本。
// 生产用 compose 里的 Flyway 容器——两边共用同一份 SQL；历史表名对齐 flyway_schema_history。
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { PGlite } from "@electric-sql/pglite";

const FLYWAY_DIR = join(__dirname, "..", "flyway");
const DATA_DIR = join(__dirname, "..", "data", "pglite"); // 本地库文件，.gitignore 已忽略 data/

async function main() {
  const db = new PGlite(DATA_DIR);

  await db.exec(`
    CREATE TABLE IF NOT EXISTS flyway_schema_history (
      installed_rank INT PRIMARY KEY,
      version        VARCHAR(50),
      description    VARCHAR(200) NOT NULL,
      type           VARCHAR(20) NOT NULL,
      script         VARCHAR(1000) NOT NULL,
      installed_by   VARCHAR(100) NOT NULL,
      installed_on   TIMESTAMP NOT NULL DEFAULT now(),
      execution_time INT NOT NULL,
      success        BOOLEAN NOT NULL
    )`);

  const files = readdirSync(FLYWAY_DIR).filter((f) => f.endsWith(".sql")).sort();
  const done = new Set(
    (await db.sql`SELECT script FROM flyway_schema_history`).rows.map(
      (r) => (r as { script: string }).script,
    ),
  );

  for (const f of files) {
    if (done.has(f)) {
      console.log(`[skip] ${f} 已应用`);
      continue;
    }
    const sqlText = readFileSync(join(FLYWAY_DIR, f), "utf8");
    const t0 = Date.now();
    await db.exec("BEGIN");
    try {
      await db.exec(sqlText);
      await db.sql`
        INSERT INTO flyway_schema_history
          (installed_rank, version, description, type, script, installed_by, execution_time, success)
        SELECT COALESCE(MAX(installed_rank),0)+1, ${f.split("__")[0]}, ${f}, 'SQL', ${f},
               'local-migrate', ${Date.now() - t0}, true
        FROM flyway_schema_history`;
      await db.exec("COMMIT");
      console.log(`[ok] ${f}`);
    } catch (e) {
      await db.exec("ROLLBACK");
      console.error(`[FAIL] ${f}`, e);
      process.exitCode = 1;
      break;
    }
  }
  await db.close();
}

void main();
