/**
 * C3/C4（6x/Q5）：图片「比例+档位」→ 宽x高 推导（与后端 ImageSizeDeriver 同算法）。
 *
 * 平台层（Seedream 等）只收 size（档位或显式宽x高）；前端选比例时按档位像素预算
 * 等面积推导 WxH 预览并随提交覆盖 size。1K/1.5K 档预算低于总像素下限 → 红字禁提交。
 */

/** 比例预设白名单（Q5：不拆自定义比例）。 */
export const RATIOS = ['1:1', '4:3', '3:4', '16:9', '9:16', '3:2', '2:3'] as const
export type ImageRatio = (typeof RATIOS)[number]

/** 档位像素预算（边长²）。 */
const TIER_BUDGET: Record<string, number> = {
  '1K': 1024 * 1024,
  '1.5K': 1536 * 1536,
  '2K': 2048 * 2048,
  '3K': 3072 * 3072,
  '4K': 4096 * 4096
}

/** 总像素上下限（与后端默认一致；provider capability 可覆盖）。 */
export const DEFAULT_MIN_PIXELS = 3_686_400
export const DEFAULT_MAX_PIXELS = 16_777_216

export interface DerivedWh {
  w: number
  h: number
  /** 总像素（w×h）。 */
  pixels: number
}

/**
 * 推导宽x高；不可行（档位预算越界/比例不在白名单）返回 error 中文指引。
 *
 * @param ratio 比例（如 "16:9"）
 * @param tier  档位（"2K"…；空 → 默认 "2K"）
 */
export function deriveWh(
  ratio: string,
  tier: string | null | undefined,
  opts: { minPixels?: number; maxPixels?: number } = {}
): DerivedWh | { error: string } {
  if (!(RATIOS as readonly string[]).includes(ratio)) {
    return { error: `比例非法: ${ratio}（可选 ${RATIOS.join('、')}）` }
  }
  const t = tier == null || tier.trim() === '' ? '2K' : tier.trim()
  const budget = TIER_BUDGET[t]
  if (budget == null) {
    return { error: `档位 ${t} 不支持比例模式（请用 2K/3K/4K，或改自定义宽x高）` }
  }
  const min = opts.minPixels ?? DEFAULT_MIN_PIXELS
  const max = opts.maxPixels ?? DEFAULT_MAX_PIXELS
  const [a, b] = ratio.split(':').map(Number)
  const r = a / b

  let w = Math.round(Math.sqrt(budget * r))
  let h = Math.round(w / r)
  // 舍入可能把总像素顶破上限 → 高度回落一步（面积近似守恒，同后端）
  while (w * h > max && h > 1) h--

  if (w * h < min) {
    return { error: `${t} 档不支持比例模式（总像素低于下限 ${min.toLocaleString()}），请用 2K 及以上或自定义宽x高` }
  }
  if (w * h > max) {
    return { error: `${t} 档不支持比例模式（总像素超上限 ${max.toLocaleString()}），请降低档位或自定义宽x高` }
  }
  const aspect = w / h
  if (aspect > 16 || aspect < 1 / 16) {
    return { error: `推导宽高比 ${w}:${h} 超出 [1:16, 16:1] 允许范围` }
  }
  return { w, h, pixels: w * h }
}

/** 推导成功 → "WxH"（提交 size 用）；失败返回 null。 */
export function deriveSizeString(ratio: string, tier: string | null | undefined): string | null {
  const d = deriveWh(ratio, tier)
  return 'w' in d ? `${d.w}x${d.h}` : null
}
