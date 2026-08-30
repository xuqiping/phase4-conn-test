// 统一响应封装 R<T> 与全局异常过滤器（对齐通用约定：{ code, msg, data }）。
import {
  ArgumentsHost,
  Catch,
  ExceptionFilter,
  HttpException,
  HttpStatus,
  Injectable,
} from "@nestjs/common";
import { Request, Response } from "express";

/** 业务码沿用 HTTP 语义：200 成功 / 4xx 用户侧错 / 5xx 服务端错 */
export function R<T>(data: T, msg = "success") {
  return { code: 200, msg, data };
}

/** 把任意异常翻译成 R 形态；msg 全中文大白话，绝不透出堆栈（安全清单） */
@Catch()
@Injectable()
export class AllExceptionsFilter implements ExceptionFilter {
  catch(exception: unknown, host: ArgumentsHost) {
    const res = host.switchToHttp().getResponse<Response>();
    const req = host.switchToHttp().getRequest<Request>();
    if (exception instanceof HttpException) {
      const status = exception.getStatus();
      const body = exception.getResponse();
      const msg =
        typeof body === "string"
          ? body
          : String((body as Record<string, unknown>).message ?? exception.message);
      res.status(status).json({ code: status, msg, data: null });
      return;
    }
    // 未知异常：日志留 requestId（Step9 接 pino），对外只给大白话
     
    console.error(`[unhandled] ${req.method} ${req.url}`, exception);
    res.status(HttpStatus.INTERNAL_SERVER_ERROR).json({
      code: 500,
      msg: "服务开小差了，请稍后重试",
      data: null,
    });
  }
}
