// 数据库服务：开发/测试用 PGlite（进程内 Postgres，零安装）；生产切 DATABASE_URL 的真 PG。
// 统一 query(text, params) → { rows }，上层业务不感知实现。
import { Injectable, OnModuleDestroy } from "@nestjs/common";
import { PGlite } from "@electric-sql/pglite";

export interface QueryResult<T = Record<string, unknown>> {
  rows: T[];
}

@Injectable()
export class DbService implements OnModuleDestroy {
  // TODO(生产)：DATABASE_URL 有值时换成 pg Client（TCP 连 compose 的 PG）。
  // PGlite 单连接即单写者，天然串行；账本写入的并发安全由「幂等键 UNIQUE + 乐观锁」兜底。
  private db =
    process.env.PGLITE_DIR === ":memory:" ? new PGlite() : new PGlite(process.env.PGLITE_DIR || "data/pglite");

  async query<T = Record<string, unknown>>(text: string, params: unknown[] = []): Promise<QueryResult<T>> {
    const res = await this.db.query(text, params as never[]);
    return { rows: res.rows as T[] };
  }

  /** 多语句脚本（迁移等）必须走 exec——query 是预编译，PG 拒绝一次塞多条 */
  async exec(text: string): Promise<void> {
    await this.db.exec(text);
  }

  async onModuleDestroy() {
    await this.db.close();
  }
}
