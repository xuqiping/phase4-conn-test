// 搜索接口：intent=web（联网搜索）| deep_read（网页转 Markdown）。受登录保护。
import { Body, Controller, HttpCode, Post } from "@nestjs/common";
import { IsIn, IsString, MinLength } from "class-validator";
import { CurrentUser } from "../../auth/jwt.guard";
import { R } from "../../common/http.filter";
import { SearchService } from "./search.service";

class SearchDto {
  @IsIn(["web", "deep_read"]) intent!: "web" | "deep_read";
  @IsString() @MinLength(2) query!: string; // web=关键词；deep_read=URL
  @IsString() @MinLength(8) nonce!: string; // 请求级幂等键原料
}

@Controller("gateway")
export class SearchController {
  constructor(private searchSvc: SearchService) {}

  @Post("search")
  @HttpCode(200)
  async search(@CurrentUser() userId: number, @Body() dto: SearchDto) {
    const data =
      dto.intent === "web"
        ? await this.searchSvc.web(userId, dto.query, dto.nonce)
        : await this.searchSvc.deepRead(userId, dto.query, dto.nonce);
    return R(data);
  }
}
