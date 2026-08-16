// 模型网关 chat：SSE 透传 + 末帧权威计量。
// 坑点预案①：SSE 必须禁压缩/禁缓冲、逐块 flush，否则用户侧逐字变憋一屏。
import { Body, Controller, HttpCode, Post, Res } from "@nestjs/common";
import { Response } from "express";
import { IsArray, IsOptional, IsString, MinLength, ValidateNested } from "class-validator";
import { Type } from "class-transformer";
import { CurrentUser } from "../auth/jwt.guard";
import { ProviderRegistry } from "./providers";
import { MeterService } from "./meter.service";
import { UpstreamError } from "./provider.interface";

class MessageDto {
  @IsString() @MinLength(1) role!: "user" | "assistant";
  @IsString() @MinLength(1) content!: string;
}

class ChatDto {
  @IsString() @MinLength(1) model!: string;
  @IsArray() @ValidateNested({ each: true }) @Type(() => MessageDto) messages!: MessageDto[];
  /** 请求级一次性 nonce（幂等键原料，网络重试同 nonce 不重扣） */
  @IsString() @MinLength(8) nonce!: string;
  /** 任务级稳定 ID（熔断按它累计；缺省视同 nonce，单轮任务也成立） */
  @IsOptional() @IsString() @MinLength(8) task_id?: string;
}

@Controller("gateway")
export class ChatController {
  constructor(
    private providers: ProviderRegistry,
    private meter: MeterService,
  ) {}

  /** 原生 Res 手写 SSE（不经序列化拦截器；错误码对齐 architecture §4.1：402/502/429） */
  @Post("chat")
  @HttpCode(200)
  async chat(
    @CurrentUser() userId: number,
    @Body() dto: ChatDto,
    @Res() res: Response,
  ): Promise<void> {
    // 调用前余额预估检查：空钱包/明显不够 → 402，先拒付再跑流
    const inputTokens = dto.messages.reduce((n, m) => n + Math.ceil(m.content.length / 2), 0);
    try {
      await this.meter.precheck(userId, dto.model, inputTokens);
    } catch (e) {
      const status = (e as { getStatus?: () => number }).getStatus?.() ?? 402;
      const msg = (e as { message?: string }).message ?? "余额不足，请充值后再试";
      res.status(status).json({ code: status, msg, data: null });
      return;
    }

    res.status(200);
    res.setHeader("content-type", "text/event-stream; charset=utf-8");
    res.setHeader("cache-control", "no-cache, no-transform"); // no-transform=禁代理压缩缓冲
    res.setHeader("x-accel-buffering", "no");
    res.flushHeaders();

    const send = (event: string, data: unknown) => {
      res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
    };

    try {
      const chunks = this.providers.resolve().chatStream({
        model: dto.model,
        messages: dto.messages.map((m) => ({ role: m.role, content: m.content })),
      });
      let usage: { input_tokens: number; output_tokens: number } | null = null;
      for await (const c of chunks) {
        if (c.type === "delta" && c.text) {
          send("delta", { text: c.text });
        } else if (c.type === "usage" && c.usage) {
          usage = c.usage; // 权威 usage 只在末帧，先记后入账
        }
      }
      if (!usage) usage = { input_tokens: inputTokens, output_tokens: 0 }; // 上游没给：按输入兜底计量
      const settled = await this.meter.settle({
        userId, nonce: dto.nonce, taskId: dto.task_id ?? dto.nonce, model: dto.model, usage,
      });
      send("done", {
        cost_cents: settled.cost_cents,
        task_spent_cents: settled.task_spent_cents,
        capped: settled.capped,
      });
      res.end();
      if (settled.capped) {
        // 熔断标记：客户端收到 capped=true 后不得再用同 task_id 发起续跑（联动点 1）
         
        console.error(`[fuse] user=${userId} task=${dto.task_id ?? dto.nonce} spent=${settled.task_spent_cents}c 触发单任务消费上限`);
      }
    } catch (e) {
      // 流已开：错误只能以 SSE event 形式下发；上游挂 → 502 语义，绝不透传上游原文。
      // 业务 HttpException（如扣费时余额不足 402）透传真实状态码+大白话，别误报成 500。
      const isUpstream = e instanceof UpstreamError;
      const http = e as { getStatus?: () => number; message?: string };
      const bizStatus = http.getStatus?.();
      const isBiz = bizStatus != null && !(e instanceof UpstreamError);
      send("error", {
        code: isUpstream ? 502 : (isBiz ? bizStatus : 500),
        msg: isUpstream
          ? "上游模型服务不可用，请稍后重试"
          : (isBiz ? (http.message ?? "请求失败，请稍后重试") : "服务开小差了，请稍后重试"),
      });
      res.end();
      if (!isUpstream && !isBiz) {
         
        console.error(`[chat-error] user=${userId} task=${dto.nonce}`, e);
      }
    }
  }
}
