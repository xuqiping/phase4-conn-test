import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { usePreferredDark } from '@vueuse/core'
import type { Settings } from '../types/settings'

const CLOSE_BEHAVIORS = new Set(['floating_ball', 'tray', 'exit'])

export const defaultSettings: Settings = {
  theme: 'dark',
  defaultView: 'grid',
  globalShortcut: 'CommandOrControl+Alt+K',
  clipboardShortcut: 'CommandOrControl+Shift+V',
  screenshotShortcut: 'CommandOrControl+Shift+X',
  minimizeToTray: true,
  closeBehavior: 'floating_ball',
  autoStart: false,
  language: 'zh-CN',
  itemsPerPage: 50,
  iconMode: 'real'
}

export function normalizeSettings(data: unknown): Settings {
  const persisted = data && typeof data === 'object'
    ? data as Record<string, unknown>
    : {}
  const explicitCloseBehavior = typeof persisted.closeBehavior === 'string'
    && CLOSE_BEHAVIORS.has(persisted.closeBehavior)
    ? persisted.closeBehavior as Settings['closeBehavior']
    : undefined
  const migratedCloseBehavior = explicitCloseBehavior
    ?? (persisted.minimizeToTray === true ? 'tray' : 'floating_ball')
  const floatingBallPosition = persisted.floatingBallPosition
    && typeof persisted.floatingBallPosition === 'object'
    ? persisted.floatingBallPosition as Settings['floatingBallPosition']
    : undefined

  return {
    ...defaultSettings,
    ...persisted,
    closeBehavior: migratedCloseBehavior,
    // Chunk 5 前 App.vue 还不能显示悬浮球，先安全降级为托盘入口。
    minimizeToTray: migratedCloseBehavior !== 'exit',
    ...(floatingBallPosition ? { floatingBallPosition } : {})
  } as Settings
}

export const useSettingsStore = defineStore('settings', () => {
  // State
  const settings = ref<Settings>(normalizeSettings(undefined))

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
    // Chunk 5 切换关闭行为 UI 前保留旧开关的即时语义。
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

  function loadSettings(data: unknown) {
    settings.value = normalizeSettings(data)
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
    importantActions: ['updateSettings', 'setTheme', 'setViewMode', 'toggleTheme'],
    migrate: stored => {
      const persistedState = stored && typeof stored === 'object'
        ? stored as Record<string, unknown>
        : {}
      return {
        ...persistedState,
        settings: normalizeSettings(persistedState.settings)
      }
    }
  }
})
