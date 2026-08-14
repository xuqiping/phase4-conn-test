import type { GlobalThemeOverrides } from 'naive-ui'
import type { ThemeKey } from '@/stores/theme'

/**
 * Naive UI overrides 与 tokens/*.css 的双源镜像。
 *
 * 结构：CSS_MIRROR 逐键照抄各主题 css 里的色值 → buildOverrides() 由镜像生成 naive 配置。
 * 改色纪律：先改 css，再同步本镜像；tests/tokens.spec.ts 会解析 css 与本镜像逐键比对，漂移即红。
 */
export const CSS_MIRROR: Record<ThemeKey, Record<string, string>> = {
  'neon-pulse': {
    accent: '#7c5cff',
    'accent-2': '#22d3ee',
    ok: '#34d399',
    warn: '#fbbf24',
    err: '#f87171',
    info: '#60a5fa',
    'sf-0': '#070b14',
    'sf-2': 'rgba(148, 163, 255, 0.06)',
    'sf-3': 'rgba(148, 163, 255, 0.1)',
    'tx-1': '#e8ecf8',
    'tx-2': '#9aa5c4',
    'line-1': 'rgba(148, 163, 255, 0.14)'
  },
  'calm-slate': {
    accent: '#5e6ad2',
    'accent-2': '#5e6ad2',
    ok: '#4cb782',
    warn: '#d9a13b',
    err: '#e5484d',
    info: '#5e6ad2',
    'sf-0': '#0f0f10',
    'sf-2': '#1c1c1e',
    'sf-3': '#242426',
    'tx-1': '#e8e8ea',
    'tx-2': '#9c9ca3',
    'line-1': 'rgba(255, 255, 255, 0.06)'
  },
  'hybrid-glow': {
    accent: '#5e6ad2',
    'accent-2': '#7c5cff',
    ok: '#4cb782',
    warn: '#d9a13b',
    err: '#e5484d',
    info: '#6d7ae0',
    'sf-0': '#101013',
    'sf-2': '#1c1c21',
    'sf-3': '#25252c',
    'tx-1': '#e9e9ee',
    'tx-2': '#9d9dab',
    'line-1': 'rgba(255, 255, 255, 0.07)'
  },
  cineon: {
    accent: '#f59e0b',
    'accent-2': '#ec4899',
    ok: '#84cc16',
    warn: '#fbbf24',
    err: '#ef4444',
    info: '#38bdf8',
    'sf-0': '#12100e',
    'sf-2': '#221e1a',
    'sf-3': '#2b2621',
    'tx-1': '#f3efe9',
    'tx-2': '#b0a89c',
    'line-1': 'rgba(245, 158, 11, 0.12)'
  }
}

function buildOverrides(m: Record<string, string>): GlobalThemeOverrides {
  return {
    common: {
      primaryColor: m.accent,
      primaryColorHover: m.accent,
      primaryColorPressed: m.accent,
      primaryColorSuppl: m.accent,
      successColor: m.ok,
      warningColor: m.warn,
      errorColor: m.err,
      infoColor: m.info,
      bodyColor: m['sf-0'],
      cardColor: m['sf-2'],
      modalColor: m['sf-3'],
      popoverColor: m['sf-3'],
      textColorBase: m['tx-1'],
      textColor1: m['tx-1'],
      textColor2: m['tx-2'],
      borderColor: m['line-1'],
      borderRadius: '10px',
      fontFamily: "Inter, 'PingFang SC', 'Microsoft YaHei', sans-serif",
      fontFamilyMono: "'JetBrains Mono', Consolas, monospace"
    },
    Card: { color: m['sf-2'], borderColor: m['line-1'] },
    DataTable: {
      thColor: m['sf-2'],
      tdColor: m['sf-2'],
      thTextColor: m['tx-2'],
      tdTextColor: m['tx-1'],
      borderColor: m['line-1']
    },
    Tag: { borderRadius: '6px' }
  }
}

export const NAIVE_TOKENS: Record<ThemeKey, GlobalThemeOverrides> = {
  'neon-pulse': buildOverrides(CSS_MIRROR['neon-pulse']),
  'calm-slate': buildOverrides(CSS_MIRROR['calm-slate']),
  'hybrid-glow': buildOverrides(CSS_MIRROR['hybrid-glow']),
  cineon: buildOverrides(CSS_MIRROR.cineon)
}
