import type { GlobalThemeOverrides } from 'naive-ui'
import type { ThemeKey } from '@/stores/theme'

/**
 * Naive UI overrides 的 JS 镜像：与 tokens/*.css 同源，改色必须两边同步。
 * C8 有一致性单测拦截漂移。C2 填充真实色值。
 */
export const NAIVE_TOKENS: Record<ThemeKey, GlobalThemeOverrides> = {
  'neon-pulse': {},
  'calm-slate': {},
  'hybrid-glow': {},
  cineon: {}
}
