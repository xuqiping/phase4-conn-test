// 账号服务：注册（验证码 mock + 体验金防薅）/ 登录 / JWT 双令牌。
// 安全对应 security_strategy §3：BCrypt 密码、验证码限频 5 次/时、体验金同设备同手机号只发一次。
import { BadRequestException, ConflictException, Injectable, UnauthorizedException } from "@nestjs/common";
import * as bcrypt from "bcryptjs";
import * as jwt from "jsonwebtoken";
import { DbService } from "../db/db.service";
import { KvService } from "../common/kv.service";

export interface Tokens {
  token: string;
  refresh_token: string;
  user: { id: number; phone: string; nickname: string | null };
}

const ACCESS_TTL_SEC = 15 * 60; // 15 分钟
const REFRESH_TTL_SEC = 7 * 24 * 3600; // 7 天
const CODE_TTL_SEC = 5 * 60;
const CODE_SEND_LIMIT_PER_HOUR = 5;

@Injectable()
export class AuthService {
  constructor(
    private db: DbService,
    private kv: KvService,
  ) {}

  // ---- 验证码（本期 mock：固定码 123456；TODO 接短信商） ----

  async sendCode(phone: string) {
    const hits = await this.kv.incr(`sms:limit:${phone}`, 3600);
    if (hits > CODE_SEND_LIMIT_PER_HOUR) {
      throw new BadRequestException("验证码发送太频繁，请一小时后再试");
    }
    // mock 模式直接返回固定码（生产绝不回显）
    const code = process.env.MOCK_SMS === "false" ? "123456" : "123456";
    await this.kv.set(`sms:code:${phone}`, code, CODE_TTL_SEC);
    return { code_hint: process.env.NODE_ENV === "production" ? undefined : code };
  }

  private async verifyCode(phone: string, code: string) {
    const saved = await this.kv.get(`sms:code:${phone}`);
    if (!saved || saved !== code) {
      throw new UnauthorizedException("验证码错误或已过期");
    }
    await this.kv.del(`sms:code:${phone}`); // 一次性
  }

  // ---- 注册：建用户 + 钱包 + 体验金（防薅） ----

  async register(phone: string, code: string, password: string | null, deviceId: string) {
    await this.verifyCode(phone, code);

    const dup = await this.db.query(`SELECT id FROM users WHERE phone = $1`, [phone]);
    if (dup.rows.length > 0) throw new ConflictException("该手机号已注册，请直接登录");

    const passwordHash = password ? await bcrypt.hash(password, 10) : null;
    const created = await this.db.query<{ id: number }>(
      `INSERT INTO users (phone, password_hash, nickname) VALUES ($1, $2, $3) RETURNING id`,
      [phone, passwordHash, `用户${phone.slice(-4)}`],
    );
    const userId = created.rows[0].id;
    await this.db.query(`INSERT INTO wallets (user_id) VALUES ($1)`, [userId]);

    // 体验金防薅（security §3.4）：同手机号（user 唯一已保证）+ 同设备各只发一次
    if (process.env.GIFT_ENABLED !== "false") {
      const giftCents = Number(process.env.GIFT_CENTS_ON_REGISTER ?? 500);
      const deviceFirst = await this.kv.setNx(`gift:device:${deviceId}`, String(userId));
      if (deviceFirst) {
        await this.db.query(
          `INSERT INTO token_ledger (user_id, kind, amount_cents, idempotency_key)
           VALUES ($1, 3, $2, $3)`,
          [userId, giftCents, `gift-register-${userId}`],
        );
        await this.db.query(
          `UPDATE wallets SET gift_cents = gift_cents + $1, version = version + 1 WHERE user_id = $2`,
          [giftCents, userId],
        );
      }
    }

    const user = { id: userId, phone, nickname: `用户${phone.slice(-4)}` };
    return this.issueTokens(user);
  }

  // ---- 登录：密码或验证码二选一 ----

  async login(phone: string, credential: { code?: string; password?: string }) {
    const found = await this.db.query<{
      id: number; phone: string; nickname: string | null; password_hash: string | null;
    }>(`SELECT id, phone, nickname, password_hash FROM users WHERE phone = $1 AND deleted = 0`, [phone]);
    const row = found.rows[0];
    if (!row) throw new UnauthorizedException("账号或凭证不正确");

    if (credential.code) {
      await this.verifyCode(phone, credential.code);
    } else if (credential.password) {
      const ok = row.password_hash && (await bcrypt.compare(credential.password, row.password_hash));
      if (!ok) throw new UnauthorizedException("账号或凭证不正确");
    } else {
      throw new BadRequestException("请提供密码或验证码其中之一");
    }
    return this.issueTokens({ id: row.id, phone: row.phone, nickname: row.nickname });
  }

  // ---- JWT ----

  private sign(payload: object, secret: string, ttl: number) {
    return jwt.sign(payload, secret, { expiresIn: ttl });
  }

  private issueTokens(user: Tokens["user"]): Tokens {
    const token = this.sign({ sub: user.id, typ: "access" }, process.env.JWT_ACCESS_SECRET!, ACCESS_TTL_SEC);
    const refresh = this.sign({ sub: user.id, typ: "refresh" }, process.env.JWT_REFRESH_SECRET!, REFRESH_TTL_SEC);
    return { token, refresh_token: refresh, user };
  }

  /** 刷新 access；旧 refresh 进黑名单（一次性轮换） */
  async refresh(refreshToken: string): Promise<Tokens> {
    let payload: jwt.JwtPayload;
    try {
      payload = jwt.verify(refreshToken, process.env.JWT_REFRESH_SECRET!) as jwt.JwtPayload;
    } catch {
      throw new UnauthorizedException("登录已过期，请重新登录");
    }
    if (payload.typ !== "refresh") throw new UnauthorizedException("令牌类型错误");
    const blacklisted = await this.kv.get(`jwt:blacklist:${refreshToken}`);
    if (blacklisted) throw new UnauthorizedException("登录已过期，请重新登录");

    await this.kv.set(`jwt:blacklist:${refreshToken}`, "1", REFRESH_TTL_SEC);
    const found = await this.db.query<{ id: number; phone: string; nickname: string | null }>(
      `SELECT id, phone, nickname FROM users WHERE id = $1 AND deleted = 0`, [payload.sub],
    );
    const row = found.rows[0];
    if (!row) throw new UnauthorizedException("账号不存在");
    return this.issueTokens({ id: row.id, phone: row.phone, nickname: row.nickname });
  }

  /** HTTP 侧校验 access（AuthGuard 调用） */
  async verifyAccessToken(token: string): Promise<number> {
    try {
      const payload = jwt.verify(token, process.env.JWT_ACCESS_SECRET!) as jwt.JwtPayload;
      if (payload.typ !== "access") throw new Error();
      if (await this.kv.get(`jwt:blacklist:${token}`)) throw new Error();
      return Number(payload.sub);
    } catch {
      throw new UnauthorizedException("请先登录");
    }
  }

  async profile(userId: number) {
    const found = await this.db.query<{ id: number; phone: string; nickname: string | null }>(
      `SELECT id, phone, nickname FROM users WHERE id = $1 AND deleted = 0`, [userId],
    );
    const row = found.rows[0];
    if (!row) throw new UnauthorizedException("账号不存在");
    return row;
  }
}
