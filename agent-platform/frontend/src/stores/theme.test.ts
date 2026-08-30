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

  it('initTheme keeps stored visible theme（xuan-zhi）', () => {
    localStorage.setItem(STORAGE_KEYS.THEME, JSON.stringify('xuan-zhi'))
    setActivePinia(createPinia())
    const store = useThemeStore()
    store.initTheme()
    expect(document.documentElement.getAttribute('data-theme')).toBe('xuan-zhi')
  })

  // FR-3 迁移：存量旧主题（已隐藏）→ 落夜墨并改写持久化，不出现「选择器里没有的幽灵主题」
  it('initTheme migrates stored hidden theme to ye-mo and persists', () => {
    localStorage.setItem(STORAGE_KEYS.THEME, JSON.stringify('cyber-glow'))
    setActivePinia(createPinia())
    const store = useThemeStore()
    store.initTheme()
    expect(store.currentTheme).toBe('ye-mo')
    expect(document.documentElement.getAttribute('data-theme')).toBe('ye-mo')
    expect(getStorage<string>(STORAGE_KEYS.THEME)).toBe('ye-mo')
  })

  it('THEME_LIST has 5 themes（旧三套 + 高山流水双主题）', () => {
    expect(THEME_LIST).toHaveLength(5)
    expect(THEME_LIST.map(t => t.name)).toEqual(['deep-space', 'dark-pro', 'cyber-glow', 'ye-mo', 'xuan-zhi'])
  })

  // FR-3 隐藏口径：选择器只见 夜墨/宣纸；隐藏≠删除（THEME_LIST 仍 5 项）
  it('visibleThemes only exposes ye-mo and xuan-zhi', () => {
    const store = useThemeStore()
    expect(store.visibleThemes.map(t => t.name)).toEqual(['ye-mo', 'xuan-zhi'])
  })
})
