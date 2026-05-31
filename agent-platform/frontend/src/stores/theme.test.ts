import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useThemeStore, THEME_LIST } from './theme'
import { STORAGE_KEYS, getStorage } from '@/utils/storage'

describe('theme store', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('defaults to deep-space when no stored theme', () => {
    const store = useThemeStore()
    expect(store.currentTheme).toBe('deep-space')
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

  it('THEME_LIST has 3 themes', () => {
    expect(THEME_LIST).toHaveLength(3)
    expect(THEME_LIST.map(t => t.name)).toEqual(['deep-space', 'dark-pro', 'cyber-glow'])
  })
})
