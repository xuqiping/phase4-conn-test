import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ProcessSettings, ColumnConfig, ConfirmMode } from '../types/process'
import { getDefaultProcessColumns } from '../components/processColumns'

const STORAGE_KEY = 'process-settings'

export const PROCESS_SETTINGS_VERSION = 1

const defaultSettings: ProcessSettings = {
  version: PROCESS_SETTINGS_VERSION,
  columns: getDefaultProcessColumns(),
  autoRefresh: false,
  refreshInterval: 5000, // 5 seconds
  confirmMode: 'whitelist',
  whitelist: [
  'explorer.exe',
    'taskmgr.exe',
    'SystemSettings.exe',
    'dwm.exe',
    'csrss.exe',
    'winlogon.exe',
  'services.exe',
    'lsass.exe',
    'svchost.exe'
  ]
}

export function normalizeProcessSettings(stored?: Partial<ProcessSettings> | null): ProcessSettings {
  const legacyColumns = Array.isArray(stored?.columns) ? stored.columns : []
  const legacyByKey = new Map(legacyColumns.map(column => [column.key, column]))
  const isLegacy = (stored?.version ?? 0) < PROCESS_SETTINGS_VERSION
  const columns = getDefaultProcessColumns().map(defaultColumn => {
    const saved = legacyByKey.get(defaultColumn.key)
    const merged = saved ? { ...defaultColumn, ...saved, key: defaultColumn.key } : defaultColumn
    if (defaultColumn.key === 'windowTitle') {
      return {
        ...merged,
        visible: isLegacy ? true : merged.visible,
        sortable: true
      }
    }
    return merged
  })

  return {
    ...defaultSettings,
    ...stored,
    version: PROCESS_SETTINGS_VERSION,
    columns,
    whitelist: Array.isArray(stored?.whitelist)
      ? [...stored.whitelist]
      : [...defaultSettings.whitelist]
  }
}

export const useProcessSettingsStore = defineStore('processSettings', () => {
  const settings = ref<ProcessSettings>(normalizeProcessSettings())

  function loadSettings() {
    try {
      const stored = localStorage.getItem(STORAGE_KEY)
      if (stored) {
        const parsed = JSON.parse(stored) as Partial<ProcessSettings>
        settings.value = normalizeProcessSettings(parsed)
        if ((parsed.version ?? 0) < PROCESS_SETTINGS_VERSION) saveSettings()
      }
    } catch (error) {
      console.error('Failed to load process settings:', error)
      settings.value = normalizeProcessSettings()
    }
  }

  function saveSettings() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(settings.value))
    } catch (error) {
      console.error('Failed to save process settings:', error)
    }
  }

  function updateColumns(columns: ColumnConfig[]) {
    settings.value.columns = columns
    saveSettings()
  }

  function updateAutoRefresh(enabled: boolean) {
    settings.value.autoRefresh = enabled
    saveSettings()
  }

  function updateRefreshInterval(interval: number) {
    settings.value.refreshInterval = interval
    saveSettings()
  }

  function updateConfirmMode(mode: ConfirmMode) {
    settings.value.confirmMode = mode
    saveSettings()
  }

  function updateWhitelist(whitelist: string[]) {
    settings.value.whitelist = whitelist
    saveSettings()
  }

  function addToWhitelist(processName: string) {
    if (!settings.value.whitelist.includes(processName)) {
      settings.value.whitelist.push(processName)
      saveSettings()
    }
  }

  function removeFromWhitelist(processName: string) {
    settings.value.whitelist = settings.value.whitelist.filter(name => name !== processName)
    saveSettings()
  }

  function resetToDefaults() {
    settings.value = normalizeProcessSettings()
    saveSettings()
  }

  // Load settings on initialization
  loadSettings()

  return {
    settings,
    loadSettings,
    saveSettings,
    updateColumns,
    updateAutoRefresh,
    updateRefreshInterval,
    updateConfirmMode,
    updateWhitelist,
    addToWhitelist,
    removeFromWhitelist,
    resetToDefaults
  }
})
