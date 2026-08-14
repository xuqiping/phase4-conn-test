// 健康检查（运维清单）：compose 编排与 K8s 探活都打这里。
import { Controller, Get } from "@nestjs/common";
import { R } from "./common/http.filter";

@Controller("healthz")
export class HealthController {
  @Get()
  check() {
    return R({ status: "ok" });
  }
}
