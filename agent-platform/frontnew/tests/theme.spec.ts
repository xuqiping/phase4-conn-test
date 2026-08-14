import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useThemeStore, THEME_KEYS } from '@/stores/theme'
import { THEMES } from '@/theme/themes'
import { NAIVE_TOKENS } from '@/theme/naive'

// 主题系统：切换 → dataset + localStorage + 恢复；非法值回退
describe('主题系统', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    delete document.documentElement.dataset.theme
  })

  it('setTheme 写 dataset 与 localStorage', () => {
    const store = useThemeStore()
    store.setTheme('cineon')
    expect(document.documentElement.dataset.theme).toBe('cineon')
    expect(localStorage.getItem('frontnew-theme')).toBe('cineon')
    expect(store.current).toBe('cineon')
  })

  it('init 从 localStorage 恢复', () => {
    localStorage.setItem('frontnew-theme', 'calm-slate')
    const store = useThemeStore()
    store.init()
    expect(store.current).toBe('calm-slate')
    expect(document.documentElement.dataset.theme).toBe('calm-slate')
  })

  it('localStorage 非法值回退默认 T1', () => {
    localStorage.setItem('frontnew-theme', 'xxx')
    const store = useThemeStore()
    store.init()
    expect(store.current).toBe('neon-pulse')
  })

  it('4 主题元信息与 overrides 齐全', () => {
    expect(THEMES).toHaveLength(4)
    for (const k of THEME_KEYS) {
      expect(NAIVE_TOKENS[k]).toBeTruthy()
      expect(NAIVE_TOKENS[k].common?.primaryColor).toBeTruthy()
    }
  })
})
