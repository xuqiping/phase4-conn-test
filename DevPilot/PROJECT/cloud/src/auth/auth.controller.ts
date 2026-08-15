// 账号接口（对齐 architecture §4.1）：
// POST /auth/send-code | /auth/register | /auth/login | /auth/refresh
import { Body, Controller, Get, HttpCode, Post } from "@nestjs/common";
import { IsOptional, IsString, Length, Matches } from "class-validator";
import { R } from "../common/http.filter";
import { CurrentUser, Public } from "./jwt.guard";
import { AuthService } from "./auth.service";

class SendCodeDto {
  @Matches(/^1\d{10}$/, { message: "手机号格式不正确" }) phone!: string;
}

class RegisterDto {
  @Matches(/^1\d{10}$/, { message: "手机号格式不正确" }) phone!: string;
  @Length(6, 6, { message: "验证码为 6 位" }) code!: string;
  @IsOptional() @IsString() @Length(8, 64) password?: string | null;
  /** 设备指纹（客户端启动时生成并持久化；体验金防薅用） */
  @IsString() @Length(8, 128) deviceId!: string;
}

class LoginDto {
  @Matches(/^1\d{10}$/, { message: "手机号格式不正确" }) phone!: string;
  @IsOptional() @Length(6, 6) code?: string;
  @IsOptional() @Length(8, 64) password?: string;
}

class RefreshDto {
  @IsString() refresh_token!: string;
}

@Controller("auth")
export class AuthController {
  constructor(private auth: AuthService) {}

  @Post("send-code")
  @Public()
  @HttpCode(200)
  sendCode(@Body() dto: SendCodeDto) {
    return this.auth.sendCode(dto.phone).then(R);
  }

  @Post("register")
  @Public()
  @HttpCode(200)
  register(@Body() dto: RegisterDto) {
    return this.auth.register(dto.phone, dto.code, dto.password ?? null, dto.deviceId).then(R);
  }

  @Post("login")
  @Public()
  @HttpCode(200)
  login(@Body() dto: LoginDto) {
    const credential: { code?: string; password?: string } = {};
    if (dto.code) credential.code = dto.code;
    else if (dto.password) credential.password = dto.password;
    return this.auth.login(dto.phone, credential).then(R);
  }

  @Post("refresh")
  @Public()
  @HttpCode(200)
  refresh(@Body() dto: RefreshDto) {
    return this.auth.refresh(dto.refresh_token).then(R);
  }

  /** 受保护示例兼实用接口：客户端启动时探登录态（无 @Public → 走全局 JwtGuard） */
  @Get("me")
  me(@CurrentUser() userId: number) {
    return this.auth.profile(userId).then(R);
  }
}
