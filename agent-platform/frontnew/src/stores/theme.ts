import { defineStore } from 'pinia'

export type ThemeKey = 'neon-pulse' | 'calm-slate' | 'hybrid-glow' | 'cineon'

export const THEME_KEYS: ThemeKey[] = ['neon-pulse', 'calm-slate', 'hybrid-glow', 'cineon']
const STORAGE_KEY = 'frontnew-theme'
const DEFAULT_THEME: ThemeKey = 'neon-pulse'

function isThemeKey(v: string | null): v is ThemeKey {
  return !!v && (THEME_KEYS as string[]).includes(v)
}

function readInitialTheme(): ThemeKey {
  try {
    // URL ?theme= 优先（运维/走查开关），其次 localStorage，最后默认
    const q = new URLSearchParams(window.location.search).get('theme')
    if (isThemeKey(q)) return q
    const saved = localStorage.getItem(STORAGE_KEY)
    if (isThemeKey(saved)) return saved
  } catch {
    /* 隐私模式等读取失败 → 默认 */
  }
  return DEFAULT_THEME
}

export const useThemeStore = defineStore('theme', {
  state: () => ({
    current: DEFAULT_THEME as ThemeKey
  }),
  actions: {
    /** 应用启动时调用一次：与 index.html 内联脚本同源逻辑，恢复主题 */
    init() {
      this.current = readInitialTheme()
      document.documentElement.dataset.theme = this.current
    },
    setTheme(name: ThemeKey) {
      this.current = name
      document.documentElement.dataset.theme = name
      try {
        localStorage.setItem(STORAGE_KEY, name)
      } catch {
        /* 写失败不阻断换肤 */
      }
    }
  }
})
