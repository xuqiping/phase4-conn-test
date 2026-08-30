// KV 存储：验证码限频 / JWT 黑名单 / 后续搜索缓存。
// 本实现为进程内 Map（TTL 软过期）——开发与测试够用；
// TODO(生产)：REDIS_URL 有值时换 ioredis（接口不变，业务无感）。
import { Injectable } from "@nestjs/common";

interface Entry {
  value: string;
  expiresAt: number;
}

@Injectable()
export class KvService {
  private store = new Map<string, Entry>();

  private sweep() {
    const now = Date.now();
    for (const [k, v] of this.store) if (v.expiresAt <= now) this.store.delete(k);
  }

  async get(key: string): Promise<string | null> {
    this.sweep();
    return this.store.get(key)?.value ?? null;
  }

  async set(key: string, value: string, ttlSeconds = 3600): Promise<void> {
    this.store.set(key, { value, expiresAt: Date.now() + ttlSeconds * 1000 });
  }

  /** 自增并返回新值（限频计数）；首次从 1 起 */
  async incr(key: string, ttlSeconds: number): Promise<number> {
    const cur = await this.get(key);
    const next = (cur ? Number(cur) : 0) + 1;
    const existing = this.store.get(key);
    const expiresAt = existing?.expiresAt ?? Date.now() + ttlSeconds * 1000;
    this.store.set(key, { value: String(next), expiresAt });
    return next;
  }

  /** 仅当不存在时写入（防薅占位）；成功返回 true */
  async setNx(key: string, value: string, ttlSeconds = 86400 * 365): Promise<boolean> {
    this.sweep();
    if (this.store.has(key)) return false;
    this.store.set(key, { value, expiresAt: Date.now() + ttlSeconds * 1000 });
    return true;
  }

  async del(key: string): Promise<void> {
    this.store.delete(key);
  }
}
