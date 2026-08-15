// JWT 守卫：Bearer access 校验；@CurrentUser() 取 userId（Step4 起各业务接口用）。
import { CanActivate, ExecutionContext, Injectable, SetMetadata, UnauthorizedException } from "@nestjs/common";
import { Reflector } from "@nestjs/core";
import type { Request } from "express";
import { AuthService } from "./auth.service";

export const IS_PUBLIC_KEY = "isPublic";
export const Public = () => SetMetadata(IS_PUBLIC_KEY, true);

@Injectable()
export class JwtGuard implements CanActivate {
  constructor(
    private reflector: Reflector,
    private auth: AuthService,
  ) {}

  async canActivate(ctx: ExecutionContext): Promise<boolean> {
    const isPublic = this.reflector.getAllAndOverride<boolean>(IS_PUBLIC_KEY, [
      ctx.getHandler(),
      ctx.getClass(),
    ]);
    if (isPublic) return true;

    const req = ctx.switchToHttp().getRequest<Request & { userId?: number }>();
    const header = req.headers.authorization;
    if (!header?.startsWith("Bearer ")) throw new UnauthorizedException("请先登录");
    req.userId = await this.auth.verifyAccessToken(header.slice(7));
    return true;
  }
}

import { createParamDecorator, ExecutionContext as Ctx } from "@nestjs/common";

export const CurrentUser = createParamDecorator((_data: unknown, ctx: Ctx): number => {
  return ctx.switchToHttp().getRequest<Request & { userId?: number }>().userId!;
});
