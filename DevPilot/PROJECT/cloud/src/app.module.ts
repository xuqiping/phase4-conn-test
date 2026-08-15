// 应用根模块：三业务域占位（auth / billing / gateway 按计划 Step3+ 逐个填实）。
import { Module } from "@nestjs/common";
import { ConfigModule } from "@nestjs/config";
import { APP_FILTER, APP_GUARD } from "@nestjs/core";
import { HealthController } from "./health.controller";
import { AllExceptionsFilter } from "./common/http.filter";
import { KvModule } from "./common/kv.module";
import { DbModule } from "./db/db.module";
import { AuthModule } from "./auth/auth.module";
import { JwtGuard } from "./auth/jwt.guard";

@Module({
  imports: [ConfigModule.forRoot({ isGlobal: true }), DbModule, KvModule, AuthModule],
  controllers: [HealthController],
  providers: [
    // 全局异常过滤器走 provider：main.ts 与测试实例行为一致
    { provide: APP_FILTER, useClass: AllExceptionsFilter },
    // 全局 JWT 守卫：默认要登录，@Public() 放行（healthz/账号接口）
    { provide: APP_GUARD, useClass: JwtGuard },
  ],
})
export class AppModule {}
