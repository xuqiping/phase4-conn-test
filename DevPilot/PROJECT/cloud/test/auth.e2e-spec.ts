// Step3 验收：注册→重复 409→登录(密码/验证码)→错凭证 401→限频→体验金防薅→JWT 刷新轮换。
import { INestApplication, ValidationPipe } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import request from "supertest";
import { AppModule } from "../src/app.module";
import { DbService } from "../src/db/db.service";

process.env.JWT_ACCESS_SECRET = "test-access";
process.env.JWT_REFRESH_SECRET = "test-refresh";
process.env.GIFT_CENTS_ON_REGISTER = "500";

let app: INestApplication;

beforeAll(async () => {
  const moduleRef = await Test.createTestingModule({ imports: [AppModule] })
    .overrideProvider(DbService)
    .useFactory({
      factory: async () => {
        // 每个测试套件一个内存库，先跑 Flyway 脚本建表
        // （必须覆盖 PGLITE_DIR，否则落到已迁移过的持久化目录报 already exists）
        process.env.PGLITE_DIR = ":memory:";
        const db = new DbService();
        const dir = join(__dirname, "..", "flyway");
        for (const f of readdirSync(dir).filter((x) => x.endsWith(".sql")).sort()) {
          await db.exec(readFileSync(join(dir, f), "utf8"));
        }
        return db;
      },
    })
    .compile();
  app = moduleRef.createNestApplication();
  app.setGlobalPrefix("api/v1");
  app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
  await app.init();
});

afterAll(async () => app.close());

async function sendCode(phone: string) {
  return request(app.getHttpServer()).post("/api/v1/auth/send-code").send({ phone });
}

describe("账号模块（P02 Step3，security §3）", () => {
  const phone = "13800001111";
  const deviceId = "device-aaaa-bbbb";

  it("发码 → 注册成功，自动建钱包+发 500 体验金", async () => {
    await sendCode(phone);
    const res = await request(app.getHttpServer())
      .post("/api/v1/auth/register")
      .send({ phone, code: "123456", password: "password8chars", deviceId });
    expect(res.status).toBe(200);
    expect(res.body.data.token).toBeTruthy();
    expect(res.body.data.refresh_token).toBeTruthy();
    expect(res.body.data.user.phone).toBe(phone);
  });

  it("重复注册 → 409 大白话", async () => {
    await sendCode(phone);
    const res = await request(app.getHttpServer())
      .post("/api/v1/auth/register")
      .send({ phone, code: "123456", deviceId });
    expect(res.status).toBe(409);
    expect(res.body.msg).toContain("已注册");
  });

  it("错误验证码 → 401；密码登录成功；错密码 → 401", async () => {
    const bad = await request(app.getHttpServer())
      .post("/api/v1/auth/login").send({ phone, code: "000000" });
    expect(bad.status).toBe(401);

    const ok = await request(app.getHttpServer())
      .post("/api/v1/auth/login").send({ phone, password: "password8chars" });
    expect(ok.status).toBe(200);

    const wrongPw = await request(app.getHttpServer())
      .post("/api/v1/auth/login").send({ phone, password: "wrongpassword" });
    expect(wrongPw.status).toBe(401);
  });

  it("验证码一次性：用过即失效", async () => {
    await sendCode(phone);
    const once = await request(app.getHttpServer())
      .post("/api/v1/auth/login").send({ phone, code: "123456" });
    expect(once.status).toBe(200);
    const twice = await request(app.getHttpServer())
      .post("/api/v1/auth/login").send({ phone, code: "123456" });
    expect(twice.status).toBe(401);
  });

  it("发码限频：第 6 次/时 → 400", async () => {
    for (let i = 0; i < 5; i++) {
      const r = await sendCode("13900002222");
      expect(r.status).toBe(200);
    }
    const sixth = await sendCode("13900002222");
    expect(sixth.status).toBe(400);
    expect(sixth.body.msg).toContain("频繁");
  });

  it("体验金防薅：同 deviceId 第二个号不发（钱包 gift=0）", async () => {
    // 借 service 直查库验证账本与钱包
    const db = app.get(DbService);
    const wallet1 = await db.query<{ gift_cents: string }>(
      `SELECT gift_cents FROM wallets w JOIN users u ON u.id=w.user_id WHERE u.phone=$1`, [phone]);
    expect(Number(wallet1.rows[0].gift_cents)).toBe(500);

    const phone2 = "13800003333";
    await sendCode(phone2);
    const res = await request(app.getHttpServer())
      .post("/api/v1/auth/register")
      .send({ phone: phone2, code: "123456", deviceId }); // 同设备
    expect(res.status).toBe(200);
    const wallet2 = await db.query<{ gift_cents: string }>(
      `SELECT gift_cents FROM wallets w JOIN users u ON u.id=w.user_id WHERE u.phone=$1`, [phone2]);
    expect(Number(wallet2.rows[0].gift_cents)).toBe(0);
  });

  it("refresh 轮换：旧 refresh 复用 → 401", async () => {
    const login = await request(app.getHttpServer())
      .post("/api/v1/auth/login").send({ phone, password: "password8chars" });
    const rt = login.body.data.refresh_token;
    const first = await request(app.getHttpServer())
      .post("/api/v1/auth/refresh").send({ refresh_token: rt });
    expect(first.status).toBe(200);
    const replay = await request(app.getHttpServer())
      .post("/api/v1/auth/refresh").send({ refresh_token: rt });
    expect(replay.status).toBe(401);
  });

  it("未登录访问受保护接口 → 401；带 token 访问 /auth/me → 200（全局 JwtGuard）", async () => {
    const res = await request(app.getHttpServer()).get("/api/v1/auth/me");
    expect(res.status).toBe(401);

    const login = await request(app.getHttpServer())
      .post("/api/v1/auth/login").send({ phone, password: "password8chars" });
    const me = await request(app.getHttpServer())
      .get("/api/v1/auth/me")
      .set("Authorization", `Bearer ${login.body.data.token}`);
    expect(me.status).toBe(200);
    expect(me.body.data.phone).toBe(phone);
  });
});
