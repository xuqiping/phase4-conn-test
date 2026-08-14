import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { CSS_MIRROR } from '@/theme/naive'
import { THEME_KEYS } from '@/stores/theme'

/**
 * 双源一致性：tokens/*.css 里的色值与 naive.ts 的 CSS_MIRROR 必须逐键相等。
 * 防「改了 css 忘改镜像」导致 naive 组件与自写组件两个色（plan 坑点表 #1）。
 */

/** 解析 css 文件 `:root[data-theme='x'] { --k: v }` 为键值表 */
function parseCssVars(path: string): Record<string, string> {
  const css = readFileSync(path, 'utf-8')
  const vars: Record<string, string> = {}
  const re = /--([\w-]+)\s*:\s*([^;]+);/g
  let m: RegExpExecArray | null
  while ((m = re.exec(css))) {
    vars[m[1]] = m[2].trim().toLowerCase()
  }
  return vars
}

// 每主题必须定义的最小变量集（漏定义会露底色/默认色）
const REQUIRED = ['sf-0', 'sf-1', 'sf-2', 'sf-3', 'sf-4', 'tx-1', 'tx-2', 'tx-3', 'accent', 'ok', 'warn', 'err', 'info', 'line-1', 'line-2']

describe('tokens 完整性与双源一致', () => {
  for (const key of THEME_KEYS) {
    // vitest 从项目根启动，用 cwd 相对路径（import.meta.url 在转换管线下不可靠）
    const vars = parseCssVars(resolve(process.cwd(), `src/theme/tokens/${key}.css`))

    it(`${key} 必需变量齐全`, () => {
      for (const name of REQUIRED) {
        expect(vars[name], `${key} 缺 --${name}`).toBeTruthy()
      }
    })

    it(`${key} css 与 naive.ts 镜像一致`, () => {
      const mirror = CSS_MIRROR[key]
      for (const [k, v] of Object.entries(mirror)) {
        expect(vars[k], `${key} --${k}: css=${vars[k]} vs mirror=${v}`).toBe(v.toLowerCase())
      }
    })
  }
})
