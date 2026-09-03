import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  copyClipboardItem,
  copyClipboardItems,
  createClipboardGroup,
  deleteClipboardGroup,
  deleteClipboardItem,
  getClipboardItemDetail,
  getClipboardItems,
  getClipboardGroups,
  getClipboardSettings,
  getClipboardStorageUsage,
  listenClipboardChanged,
  pasteClipboardItem,
  rememberClipboardTargetWindow,
  moveClipboardItems,
  renameClipboardGroup,
  searchClipboardItems,
  startClipboardMonitor,
  stopClipboardMonitor,
  setClipboardItemsPinned,
  updateClipboardItemNote,
  updateClipboardSettings
} from '../api/clipboard'
import type {
  ClipboardDateRangePreset,
  ClipboardGroup,
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
  fileSaveMode: 'backup',
  backupDirectory: null,
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
  const datePreset = ref<ClipboardDateRangePreset>('all')
  const customStartDate = ref('')
  const customEndDate = ref('')
  const favoriteOnly = ref(false)
  const groups = ref<ClipboardGroup[]>([])
  const groupFilter = ref('all')
  const isQuickPanelOpen = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const settings = ref<ClipboardSettings>({ ...defaultSettings })
  const storageUsage = ref<ClipboardStorageUsage | null>(null)
  const selectedIds = ref<Set<string>>(new Set())
  let clipboardChangedUnlisten: (() => void) | null = null
  let reloadTimer: ReturnType<typeof setTimeout> | null = null

  const selectedItem = computed(() => items.value.find(item => item.id === selectedItemId.value) ?? null)

  function buildQuery() {
    const groupId = groupFilter.value === 'all'
      ? undefined
      : groupFilter.value === 'ungrouped'
        ? '__ungrouped__'
        : groupFilter.value
    return {
      query: searchQuery.value,
      kind: kindFilter.value,
      favoriteOnly: favoriteOnly.value,
      ...(groupId ? { groupId } : {}),
      ...dateRangeMillis(),
      limit: 100,
      offset: 0
    }
  }

  function dateRangeMillis() {
    const now = new Date()
    const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    const todayEnd = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 23, 59, 59, 999)

    if (datePreset.value === 'all') return {}
    if (datePreset.value === 'today') return { startAt: todayStart.getTime(), endAt: todayEnd.getTime() }
    if (datePreset.value === 'yesterday') {
      const start = new Date(todayStart)
      const end = new Date(todayEnd)
      start.setDate(start.getDate() - 1)
      end.setDate(end.getDate() - 1)
      return { startAt: start.getTime(), endAt: end.getTime() }
    }
    if (datePreset.value === 'last7Days') {
      const start = new Date(todayStart)
      start.setDate(start.getDate() - 6)
      return { startAt: start.getTime(), endAt: todayEnd.getTime() }
    }
    if (datePreset.value === 'last30Days') {
      const start = new Date(todayStart)
      start.setDate(start.getDate() - 29)
      return { startAt: start.getTime(), endAt: todayEnd.getTime() }
    }

    let startAt = customStartDate.value ? dateOnlyToLocalStart(customStartDate.value) : undefined
    let endAt = customEndDate.value ? dateOnlyToLocalEnd(customEndDate.value) : undefined
    if (startAt && endAt && startAt > endAt) {
      const nextStart = dateOnlyToLocalStart(customEndDate.value)
      const nextEnd = dateOnlyToLocalEnd(customStartDate.value)
      startAt = nextStart
      endAt = nextEnd
    }
    return { ...(startAt ? { startAt } : {}), ...(endAt ? { endAt } : {}) }
  }

  function dateOnlyToLocalStart(date: string) {
    return new Date(`${date}T00:00:00`).getTime()
  }

  function dateOnlyToLocalEnd(date: string) {
    return new Date(`${date}T23:59:59.999`).getTime()
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
    if (!clipboardChangedUnlisten) {
      clipboardChangedUnlisten = await listenClipboardChanged(() => {
        scheduleReload()
      })
    }
    await startClipboardMonitor()
  }

  async function stopMonitor() {
    if (reloadTimer) {
      clearTimeout(reloadTimer)
      reloadTimer = null
    }
    if (clipboardChangedUnlisten) {
      clipboardChangedUnlisten()
      clipboardChangedUnlisten = null
    }
    await stopClipboardMonitor()
  }

  function scheduleReload() {
    if (reloadTimer) return
    reloadTimer = setTimeout(() => {
      reloadTimer = null
      void loadItems()
    }, 100)
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
    selectedItemId.value = id
    if (selectedDetail.value?.id !== id) {
      selectedDetail.value = null
    }
    const detail = await getClipboardItemDetail(id)
    items.value = items.value.map(item => item.id === id ? { ...item, note: detail.note } : item)
    if (selectedItemId.value === id) {
      selectedDetail.value = detail
    }
    return detail
  }

  async function copyItem(id: string, format: ClipboardPasteFormat = 'original') {
    await copyClipboardItem(id, format)
  }

  async function copySelectedItems(format: ClipboardPasteFormat = 'original') {
    const ids = visibleSelectedIds()
    if (ids.length === 0) return 0
    await copyClipboardItems(ids, format)
    return ids.length
  }

  async function pasteItem(id: string, format: ClipboardPasteFormat = 'original') {
    await pasteClipboardItem(id, format)
  }

  async function deleteItem(id: string) {
    await deleteClipboardItem(id)
    removeItemsFromState([id])
  }

  async function deleteSelectedItems() {
    const ids = visibleSelectedIds()
    await Promise.all(ids.map(id => deleteClipboardItem(id)))
    removeItemsFromState(ids)
  }

  async function updateItemNote(id: string, note: string) {
    const normalizedNote = await updateClipboardItemNote(id, note.trim() ? note : null)
    const noteValue = normalizedNote ?? undefined
    items.value = items.value.map(item => item.id === id ? { ...item, note: noteValue } : item)
    if (selectedDetail.value?.id === id) {
      selectedDetail.value = { ...selectedDetail.value, note: noteValue }
    }
    return normalizedNote
  }

  async function loadGroups() {
    groups.value = await getClipboardGroups()
  }

  async function createGroup(name: string) {
    const group = await createClipboardGroup(name)
    groups.value = [...groups.value, group]
      .sort((left, right) => left.sortOrder - right.sortOrder || left.createdAt - right.createdAt)
    return group
  }

  async function renameGroup(id: string, name: string) {
    const group = await renameClipboardGroup(id, name)
    groups.value = groups.value.map(item => item.id === id ? group : item)
    return group
  }

  async function deleteGroup(id: string) {
    await deleteClipboardGroup(id)
    groups.value = groups.value.filter(group => group.id !== id)
    if (groupFilter.value === id) {
      groupFilter.value = 'ungrouped'
    }
  }

  async function moveItems(ids: string[], groupId: string | null) {
    if (ids.length === 0) return
    await moveClipboardItems(ids, groupId)
    await loadItems()
  }

  async function setItemsPinned(ids: string[], isPinned: boolean) {
    if (ids.length === 0) return
    await setClipboardItemsPinned(ids, isPinned)
    await loadItems()
  }

  function clearSelectedItem() {
    selectedItemId.value = null
    selectedDetail.value = null
  }

  function toggleSelected(id: string) {
    const next = new Set(selectedIds.value)
    if (next.has(id)) {
      next.delete(id)
      if (selectedItemId.value === id) {
        clearSelectedItem()
      }
    } else {
      next.add(id)
    }
    selectedIds.value = next
  }

  function selectAllVisible() {
    selectedIds.value = new Set(items.value.map(item => item.id))
  }

  function invertVisibleSelection() {
    const visibleIds = new Set(items.value.map(item => item.id))
    const next = new Set(selectedIds.value)
    for (const id of visibleIds) {
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
    }
    selectedIds.value = next
  }

  function clearSelection() {
    selectedIds.value = new Set()
    clearSelectedItem()
  }

  function selectedIdsForAction(fallbackId?: string) {
    const ids = visibleSelectedIds()
    if (!fallbackId || ids.length > 1) return ids
    return [fallbackId]
  }

  function visibleSelectedIds() {
    return items.value
      .map(item => item.id)
      .filter(id => selectedIds.value.has(id))
  }

  function removeItemsFromState(ids: string[]) {
    const idSet = new Set(ids)
    items.value = items.value.filter(item => !idSet.has(item.id))
    selectedIds.value = new Set(Array.from(selectedIds.value).filter(id => !idSet.has(id)))
    if (selectedItemId.value && idSet.has(selectedItemId.value)) {
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
    datePreset,
    customStartDate,
    customEndDate,
    favoriteOnly,
    groups,
    groupFilter,
    isQuickPanelOpen,
    loading,
    error,
    settings,
    storageUsage,
    selectedIds,
    startMonitor,
    stopMonitor,
    loadItems,
    searchItems,
    loadDetail,
    copyItem,
    copySelectedItems,
    pasteItem,
    deleteItem,
    deleteSelectedItems,
    updateItemNote,
    loadGroups,
    createGroup,
    renameGroup,
    deleteGroup,
    moveItems,
    setItemsPinned,
    clearSelectedItem,
    toggleSelected,
    selectedIdsForAction,
    selectAllVisible,
    invertVisibleSelection,
    clearSelection,
    loadSettings,
    updateSettings,
    refreshStorageUsage,
    openQuickPanel,
    closeQuickPanel
  }
})
