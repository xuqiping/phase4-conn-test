import { describe, expect, it } from 'vitest'
import { RATIOS, deriveWh, deriveSizeString } from './imageSize'

// C4/C5（6x/Q5）：前端推导与后端 ImageSizeDeriver 同算法——7×3 全矩阵 + 边界 + 联动锚点。
describe('imageSize 比例推导', () => {
  const TIERS = ['2K', '3K', '4K']

  it('7 比例 × 3 档位全矩阵：像素落区间且比例正确（联动：切档位重算）', () => {
    for (const ratio of RATIOS) {
      const [a, b] = ratio.split(':').map(Number)
      const expectR = a / b
      for (const tier of TIERS) {
        const d = deriveWh(ratio, tier)
        expect('error' in d, `${ratio}+${tier}`).toBe(false)
        if ('w' in d) {
          expect(d.pixels, `${ratio}+${tier} 像素`).toBeGreaterThanOrEqual(3_686_400)
          expect(d.pixels, `${ratio}+${tier} 像素`).toBeLessThanOrEqual(16_777_216)
          const actual = d.w / d.h
          expect(Math.abs(actual - expectR) / expectR, `${ratio}+${tier} 比例`).toBeLessThan(0.02)
        }
      }
    }
  })

  it('与后端同锚点：1:1+2K→2048x2048；16:9+2K→2731x1536；16:9+4K→5461x3072', () => {
    expect(deriveSizeString('1:1', '2K')).toBe('2048x2048')
    expect(deriveSizeString('16:9', '2K')).toBe('2731x1536')
    expect(deriveSizeString('16:9', '4K')).toBe('5461x3072')
  })

  it('档位缺省 → 默认 2K（联动：比例参数变重算；size 空不掉链）', () => {
    expect(deriveSizeString('16:9', null)).toBe('2731x1536')
    expect(deriveSizeString('16:9', '  ')).toBe('2731x1536')
  })

  it('1K/1.5K 档 → 明确指引错误（联动：红字禁提交，切回 2K+ 自动恢复）', () => {
    const e1 = deriveWh('16:9', '1K')
    expect('error' in e1 && e1.error).toContain('不支持比例模式')
    const e2 = deriveWh('1:1', '1.5K')
    expect('error' in e2 && e2.error).toContain('自定义宽x高')
    // 切回 2K 恢复
    expect(deriveSizeString('16:9', '2K')).toBe('2731x1536')
  })

  it('非白名单比例 / 未知档位 → 错误', () => {
    expect('error' in deriveWh('21:9', '2K')).toBe(true)
    expect('error' in deriveWh('16:9', '8K')).toBe(true)
  })

  it('能力覆盖：放宽上下限后 1K 档可行', () => {
    const d = deriveWh('21:9', '2K', { minPixels: 1_000_000 })
    // 21:9 默认白名单外——前端工具白名单固定 7 预设（后端可配），此处仅验证放宽像素下限
    if ('error' in d) {
      expect(d.error).toContain('比例非法')
    } else {
      expect(d.w).toBeGreaterThan(0)
    }
    const low = deriveWh('1:1', '1K', { minPixels: 1_000_000 })
    expect('w' in low).toBe(true)
  })
})
