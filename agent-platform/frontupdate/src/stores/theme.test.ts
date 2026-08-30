import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useThemeStore, THEME_LIST } from './theme'
import { STORAGE_KEYS, getStorage } from '@/utils/storage'

describe('theme store', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  // 中国风重做（ART-DIR-0001）：默认主题改为 ye-mo 夜墨，旧 deep-space 仍在列表中可选
  it('defaults to ye-mo when no stored theme', () => {
    const store = useThemeStore()
    expect(store.currentTheme).toBe('ye-mo')
  })

  it('currentThemeMeta matches currentTheme', () => {
    const store = useThemeStore()
    expect(store.currentThemeMeta.name).toBe(store.currentTheme)
  })

  it('setTheme updates state and localStorage', () => {
    const store = useThemeStore()
    store.setTheme('cyber-glow')
    expect(store.currentTheme).toBe('cyber-glow')
    expect(getStorage<string>(STORAGE_KEYS.THEME)).toBe('cyber-glow')
  })

  it('setTheme sets data-theme attribute on document', () => {
    const store = useThemeStore()
    store.setTheme('dark-pro')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark-pro')
  })

  it('initTheme applies stored theme', () => {
    localStorage.setItem(STORAGE_KEYS.THEME, JSON.stringify('cyber-glow'))
    setActivePinia(createPinia())
    const store = useThemeStore()
    store.initTheme()
    expect(document.documentElement.getAttribute('data-theme')).toBe('cyber-glow')
  })

  it('THEME_LIST has 5 themes（旧三套 + 高山流水双主题）', () => {
    expect(THEME_LIST).toHaveLength(5)
    expect(THEME_LIST.map(t => t.name)).toEqual(['deep-space', 'dark-pro', 'cyber-glow', 'ye-mo', 'xuan-zhi'])
  })
})
