// 应用根模块：三业务域占位（auth / billing / gateway 按计划 Step3+ 逐个填实）。
import { Module } from "@nestjs/common";
import { ConfigModule } from "@nestjs/config";
import { APP_FILTER } from "@nestjs/core";
import { HealthController } from "./health.controller";
import { AllExceptionsFilter } from "./common/http.filter";

@Module({
  imports: [ConfigModule.forRoot({ isGlobal: true })],
  controllers: [HealthController],
  providers: [
    // 全局异常过滤器走 provider：main.ts 与测试实例行为一致
    { provide: APP_FILTER, useClass: AllExceptionsFilter },
  ],
})
export class AppModule {}
