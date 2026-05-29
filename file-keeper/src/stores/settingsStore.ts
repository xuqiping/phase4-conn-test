import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { usePreferredDark } from '@vueuse/core'
import type { Settings } from '../types/settings'

export const useSettingsStore = defineStore('settings', () => {
  // State
  const settings = ref<Settings>({
    theme: 'dark',
    defaultView: 'grid',
    globalShortcut: 'CommandOrControl+Alt+K',
    clipboardShortcut: 'CommandOrControl+Shift+V',
    minimizeToTray: true,
    autoStart: false,
    language: 'zh-CN',
    itemsPerPage: 50,
    iconMode: 'real' // 默认使用真实图标
  })

  // System theme detection
  const isSystemDark = usePreferredDark()

  // Computed effective theme
  const effectiveTheme = computed(() => {
    if (settings.value.theme === 'auto') {
      return isSystemDark.value ? 'dark' : 'light'
    }
    return settings.value.theme
  })

  // Actions
  function updateSettings(updates: Partial<Settings>) {
    Object.assign(settings.value, updates)
  }

  function setTheme(theme: 'light' | 'dark' | 'auto') {
    settings.value.theme = theme
  }

  function setViewMode(mode: 'grid' | 'list') {
    settings.value.defaultView = mode
  }

  function setIconMode(mode: 'real' | 'generic') {
    settings.value.iconMode = mode
  }

  function toggleTheme() {
    const themeOrder: Array<'light' | 'dark' | 'auto'> = ['light', 'dark', 'auto']
    const currentIndex = themeOrder.indexOf(settings.value.theme)
    const nextIndex = (currentIndex + 1) % themeOrder.length
    settings.value.theme = themeOrder[nextIndex]
  }

  function loadSettings(data: Settings) {
    settings.value = { ...settings.value, ...data }
  }

  return {
    // State
    settings,
    effectiveTheme,
    // Actions
    updateSettings,
    setTheme,
    setViewMode,
    setIconMode,
    toggleTheme,
    loadSettings
  }
}, {
  persist: {
    key: 'settings',
    paths: ['settings'],
    importantActions: ['updateSettings', 'setTheme', 'setViewMode', 'toggleTheme']
  }
})
