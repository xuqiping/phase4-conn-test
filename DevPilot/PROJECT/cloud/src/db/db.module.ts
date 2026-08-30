// 全局单例：DbService（PGlite 连接）与 KvService 全应用共享。
// PGlite 数据目录有文件锁——绝不能多实例（多模块各自 provide 会抢锁报错）。
import { Global, Module } from "@nestjs/common";
import { DbService } from "./db.service";

@Global()
@Module({
  providers: [DbService],
  exports: [DbService],
})
export class DbModule {}
