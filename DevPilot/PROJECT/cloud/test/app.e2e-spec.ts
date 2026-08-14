// Step1 验收：/healthz 返 R 形态；全局前缀 /api/v1 生效。
import { INestApplication } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import request from "supertest";
import { AppModule } from "../src/app.module";

describe("云端骨架（P02 Step1）", () => {
  let app: INestApplication;

  beforeAll(async () => {
    const moduleRef = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();
    app = moduleRef.createNestApplication();
    app.setGlobalPrefix("api/v1");
    await app.init();
  });

  afterAll(async () => app.close());

  it("GET /api/v1/healthz → {code:200, data.status:'ok'}", async () => {
    const res = await request(app.getHttpServer()).get("/api/v1/healthz");
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ code: 200, msg: "success", data: { status: "ok" } });
  });

  it("未知路由 → 统一 404 R 形态（大白话）", async () => {
    const res = await request(app.getHttpServer()).get("/api/v1/nope");
    expect(res.status).toBe(404);
    expect(res.body.code).toBe(404);
    expect(typeof res.body.msg).toBe("string");
    expect(res.body.data).toBeNull();
  });
});
