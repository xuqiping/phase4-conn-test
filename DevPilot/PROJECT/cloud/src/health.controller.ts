// 健康检查（运维清单）：compose 编排与 K8s 探活都打这里（@Public 免登录）。
import { Controller, Get } from "@nestjs/common";
import { R } from "./common/http.filter";
import { Public } from "./auth/jwt.guard";

@Controller("healthz")
export class HealthController {
  @Get()
  @Public()
  check() {
    return R({ status: "ok" });
  }
}
