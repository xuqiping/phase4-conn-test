import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ProcessSettings, ColumnConfig, ConfirmMode } from '../types/process'

const STORAGE_KEY = 'process-settings'

const defaultColumns: ColumnConfig[] = [
  { key: 'name', label: 'Name', width: '200px', visible: true, sortable: true },
  { key: 'category', label: 'Category', width: '120px', visible: true, sortable: true },
  { key: 'pid', label: 'PID', width: '80px', visible: true, sortable: true },
  { key: 'memory', label: 'Memory', width: '100px', visible: true, sortable: true },
  { key: 'cpu', label: 'CPU', width: '80px', visible: true, sortable: true },
  { key: 'runtime', label: 'Runtime', width: '100px', visible: true, sortable: true },
  { key: 'path', label: 'Path', width: '300px', visible: false, sortable: false },
  { key: 'windowTitle', label: 'Window Title', width: '200px', visible: false, sortable: false }
]

const defaultSettings: ProcessSettings = {
  columns: defaultColumns,
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

export const useProcessSettingsStore = defineStore('processSettings', () => {
  const settings = ref<ProcessSettings>({ ...defaultSettings })

  function loadSettings() {
    try {
      const stored = localStorage.getItem(STORAGE_KEY)
      if (stored) {
        const parsed = JSON.parse(stored) as ProcessSettings
        // Merge with defaults to handle new settings
        settings.value = {
          ...defaultSettings,
          ...parsed,
          columns: parsed.columns || defaultColumns
      }
    }
    } catch (error) {
      console.error('Failed to load process settings:', error)
      settings.value = { ...defaultSettings }
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
    settings.value = { ...defaultSettings }
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
