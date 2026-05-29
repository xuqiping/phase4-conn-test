import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  copyClipboardItem,
  deleteClipboardItem,
  getClipboardItemDetail,
  getClipboardItems,
  getClipboardSettings,
  getClipboardStorageUsage,
  pasteClipboardItem,
  rememberClipboardTargetWindow,
  searchClipboardItems,
  startClipboardMonitor,
  stopClipboardMonitor,
  updateClipboardSettings
} from '../api/clipboard'
import type {
  ClipboardItemDetail,
  ClipboardItemSummary,
  ClipboardKind,
  ClipboardPasteFormat,
  ClipboardSettings,
  ClipboardStorageUsage
} from '../types/clipboard'

const defaultSettings: ClipboardSettings = {
  monitorEnabled: true,
  quickPanelShortcut: 'CommandOrControl+Shift+V',
  autoPaste: false,
  protectSensitiveContent: true,
  enableOcr: true,
  enableLinkPreview: false,
  totalNonTextLimitMb: 2048,
  itemSizeLimitMb: 200,
  typeLimitsMb: { image: 1024, file: 2048, html: 500, linkPreview: 200 },
  fileExtensionMode: 'allow_all',
  fileExtensions: [],
  excludedApps: []
}

export const useClipboardStore = defineStore('clipboard', () => {
  const items = ref<ClipboardItemSummary[]>([])
  const selectedItemId = ref<string | null>(null)
  const selectedDetail = ref<ClipboardItemDetail | null>(null)
  const searchQuery = ref('')
  const kindFilter = ref<ClipboardKind | 'all'>('all')
  const favoriteOnly = ref(false)
  const isQuickPanelOpen = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const settings = ref<ClipboardSettings>({ ...defaultSettings })
  const storageUsage = ref<ClipboardStorageUsage | null>(null)

  const selectedItem = computed(() => items.value.find(item => item.id === selectedItemId.value) ?? null)

  function buildQuery() {
    return {
      query: searchQuery.value,
      kind: kindFilter.value,
      favoriteOnly: favoriteOnly.value,
      limit: 100,
      offset: 0
    }
  }

  async function runWithLoading<T>(action: () => Promise<T>): Promise<T> {
    loading.value = true
    error.value = null
    try {
      return await action()
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      throw err
    } finally {
      loading.value = false
    }
  }

  async function startMonitor() {
    await startClipboardMonitor()
  }

  async function stopMonitor() {
    await stopClipboardMonitor()
  }

  async function loadItems() {
    await runWithLoading(async () => {
      items.value = await getClipboardItems(buildQuery())
      if (!selectedItemId.value && items.value.length > 0) {
        selectedItemId.value = items.value[0].id
      }
    })
  }

  async function searchItems() {
    await runWithLoading(async () => {
      items.value = await searchClipboardItems(buildQuery())
      selectedItemId.value = items.value[0]?.id ?? null
    })
  }

  async function loadDetail(id: string) {
    selectedDetail.value = await getClipboardItemDetail(id)
    selectedItemId.value = id
  }

  async function copyItem(id: string, format: ClipboardPasteFormat = 'original') {
    await copyClipboardItem(id, format)
  }

  async function pasteItem(id: string, format: ClipboardPasteFormat = 'original') {
    await pasteClipboardItem(id, format)
  }

  async function deleteItem(id: string) {
    await deleteClipboardItem(id)
    items.value = items.value.filter(item => item.id !== id)
    if (selectedItemId.value === id) {
      selectedItemId.value = items.value[0]?.id ?? null
      selectedDetail.value = null
    }
  }

  async function loadSettings() {
    settings.value = await getClipboardSettings()
  }

  async function updateSettings(updates: Partial<ClipboardSettings>) {
    const next = { ...settings.value, ...updates }
    settings.value = await updateClipboardSettings(next)
  }

  async function refreshStorageUsage() {
    storageUsage.value = await getClipboardStorageUsage()
  }

  function openQuickPanel() {
    void rememberClipboardTargetWindow().finally(() => {
      isQuickPanelOpen.value = true
    })
  }

  function closeQuickPanel() {
    isQuickPanelOpen.value = false
  }

  return {
    items,
    selectedItemId,
    selectedDetail,
    selectedItem,
    searchQuery,
    kindFilter,
    favoriteOnly,
    isQuickPanelOpen,
    loading,
    error,
    settings,
    storageUsage,
    startMonitor,
    stopMonitor,
    loadItems,
    searchItems,
    loadDetail,
    copyItem,
    pasteItem,
    deleteItem,
    loadSettings,
    updateSettings,
    refreshStorageUsage,
    openQuickPanel,
    closeQuickPanel
  }
})
