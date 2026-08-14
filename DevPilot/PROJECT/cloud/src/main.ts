// 云端入口：薄层服务，只做账号/计费/网关，零算力（ADR-002）。
import "reflect-metadata";
import { NestFactory } from "@nestjs/core";
import { AppModule } from "./app.module";

async function bootstrap() {
  const app = await NestFactory.create(AppModule);
  app.setGlobalPrefix("api/v1"); // 对齐 architecture §4.1 路径（过滤器已挂 AppModule）
  const port = Number(process.env.PORT ?? 3000);
  await app.listen(port);
  // eslint-disable-next-line no-console
  console.log(`[devpilot-cloud] listening on :${port}`);
}
void bootstrap();
