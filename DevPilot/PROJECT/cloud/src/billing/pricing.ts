// 模型单价表（plan：定价配置走文件，不进库）。单位：分 / 百万 token，向上取整计费。
// 调价 = 改这里 + 部署；账本只记实际扣分数，与单价表解耦（历史价格不回溯）。
import { BadRequestException } from "@nestjs/common";

export interface ModelPrice {
  input_per_mtok_cents: number;
  output_per_mtok_cents: number;
}

export const PRICING: Record<string, ModelPrice> = {
  "gpt-4o-mini": { input_per_mtok_cents: 105, output_per_mtok_cents: 420 },
  "gpt-4o": { input_per_mtok_cents: 2100, output_per_mtok_cents: 8400 },
  "deepseek-chat": { input_per_mtok_cents: 14, output_per_mtok_cents: 28 },
};

/** 按单价表估算费用（分）。未知模型直接报错，禁止落到默认价。 */
export function estimateCostCents(model: string, tokensIn: number, tokensOut: number): number {
  const p = PRICING[model];
  if (!p) throw new BadRequestException(`暂不支持模型 ${model}`);
  const cost =
    (BigInt(tokensIn) * BigInt(p.input_per_mtok_cents)) / 1_000_000n +
    (BigInt(tokensOut) * BigInt(p.output_per_mtok_cents) + 999_999n) / 1_000_000n;
  return Number(cost);
}
