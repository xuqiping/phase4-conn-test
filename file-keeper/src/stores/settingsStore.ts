import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Settings } from '../types/settings'

export const useSettingsStore = defineStore('settings', () => {
  // State
  const settings = ref<Settings>({
    theme: 'dark',
    defaultView: 'grid',
    globalShortcut: 'CommandOrControl+Shift+F',
    minimizeToTray: true,
    autoStart: false,
    language: 'zh-CN',
    itemsPerPage: 50
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

  function toggleTheme() {
    settings.value.theme = settings.value.theme === 'dark' ? 'light' : 'dark'
  }

  function loadSettings(data: Settings) {
    settings.value = { ...settings.value, ...data }
  }

  return {
    // State
    settings,
    // Actions
    updateSettings,
    setTheme,
    setViewMode,
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
