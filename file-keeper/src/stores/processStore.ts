import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { ProcessInfo, ProcessCategory } from '../types/process'
import * as processApi from '../api/process'

type ProcessSortKey = 'name' | 'category' | 'pid' | 'memory' | 'cpu' | 'windowTitle'
type ProcessSortDirection = 'asc' | 'desc'

export const useProcessStore = defineStore('process', () => {
  // State
  const processes = ref<ProcessInfo[]>([])
  const selectedIds = ref<Set<number>>(new Set())
  const currentCategory = ref<ProcessCategory>('All')
  const isRefreshing = ref(false)
  const lastRefreshTime = ref<number>(0)
  const error = ref<string | null>(null)
  const sortKey = ref<ProcessSortKey | null>(null)
  const sortDirection = ref<ProcessSortDirection>('asc')

  function compareText(a: string, b: string): number {
    return a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' })
  }

  function compareProcesses(a: ProcessInfo, b: ProcessInfo, key: ProcessSortKey): number {
    switch (key) {
      case 'name':
        return compareText(a.name, b.name)
      case 'category':
        return compareText(a.category, b.category) || compareText(a.name, b.name)
      case 'pid':
        return a.pid - b.pid || compareText(a.name, b.name)
      case 'memory':
        return a.memory_mb - b.memory_mb || compareText(a.name, b.name)
      case 'cpu':
        return a.cpu_usage - b.cpu_usage || compareText(a.name, b.name)
      case 'windowTitle':
        return compareText(a.window_title, b.window_title) || compareText(a.name, b.name)
    }
  }

  function getDefaultSortDirection(key: ProcessSortKey): ProcessSortDirection {
    return key === 'memory' || key === 'cpu' ? 'desc' : 'asc'
  }

  // Computed
  const filteredProcesses = computed(() => {
    const filtered = currentCategory.value === 'All'
      ? processes.value
      : processes.value.filter(p => p.category === currentCategory.value)

    if (!sortKey.value) {
      return filtered
    }

    const direction = sortDirection.value === 'asc' ? 1 : -1
    return filtered.slice().sort((a, b) => direction * compareProcesses(a, b, sortKey.value!))
  })

  const selectedCount = computed(() => selectedIds.value.size)

  const categoryCounts = computed(() => {
    const counts: Record<ProcessCategory, number> = {
      All: processes.value.length,
      Browser: 0,
      Office: 0,
      Explorer: 0,
      Terminal: 0,
      Archive: 0,
      Document: 0,
    Media: 0,
    Image: 0,
      Communication: 0,
      Download: 0,
      Game: 0,
      System: 0,
      Other: 0
    }

    processes.value.forEach(p => {
      counts[p.category]++
    })

    return counts
  })

  const selectedProcesses = computed(() => {
    return processes.value.filter(p => selectedIds.value.has(p.window_handle))
  })

  // Actions
  async function refresh() {
    if (isRefreshing.value) return

    const startTime = performance.now()
    isRefreshing.value = true
    error.value = null

    try {
      const apiStartTime = performance.now()
      const result = await processApi.getRunningProcesses()
      const apiEndTime = performance.now()

      processes.value = result
      lastRefreshTime.value = Date.now()

      // Remove selected rows that no longer exist
      const currentWindowHandles = new Set(result.map(p => p.window_handle))
      selectedIds.value.forEach(windowHandle => {
        if (!currentWindowHandles.has(windowHandle)) {
          selectedIds.value.delete(windowHandle)
        }
      })

    const totalTime = performance.now() - startTime
    const apiTime = apiEndTime - apiStartTime
      const renderTime = totalTime - apiTime

      console.log(`[PERF] Process refresh - Total: ${totalTime.toFixed(2)}ms, API: ${apiTime.toFixed(2)}ms, Render: ${renderTime.toFixed(2)}ms, Count: ${result.length}`)
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      console.error('Failed to refresh processes:', err)
    } finally {
      isRefreshing.value = false
    }
  }

  function toggleSelect(windowHandle: number) {
    if (selectedIds.value.has(windowHandle)) {
      selectedIds.value.delete(windowHandle)
    } else {
      selectedIds.value.add(windowHandle)
    }
  }

  function selectAll() {
    filteredProcesses.value.forEach(p => {
      selectedIds.value.add(p.window_handle)
    })
  }

  function deselectAll() {
    selectedIds.value.clear()
  }

  function invertSelection() {
    const newSelection = new Set<number>()
    filteredProcesses.value.forEach(p => {
      if (!selectedIds.value.has(p.window_handle)) {
        newSelection.add(p.window_handle)
      }
    })
    selectedIds.value = newSelection
  }

  function setCategory(category: ProcessCategory) {
    currentCategory.value = category
  }

  function setSort(key: ProcessSortKey) {
    if (sortKey.value === key) {
      sortDirection.value = sortDirection.value === 'asc' ? 'desc' : 'asc'
      return
    }

    sortKey.value = key
    sortDirection.value = getDefaultSortDirection(key)
  }

  async function closeProcess(windowHandle: number): Promise<boolean> {
    try {
      const process = processes.value.find(p => p.window_handle === windowHandle)
      if (!process) {
      error.value = 'Process not found'
    return false
      }

      await processApi.closeProcess(process.window_handle)
      // Remove from list
      processes.value = processes.value.filter(p => p.window_handle !== windowHandle)
      selectedIds.value.delete(windowHandle)
      return true
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      return false
    }
  }

  async function closeSelected(): Promise<{ success: number; failed: number }> {
    const windowHandlesToClose = Array.from(selectedIds.value)
    if (windowHandlesToClose.length === 0) {
      return { success: 0, failed: 0 }
    }

    try {
      const result = await processApi.closeProcesses(windowHandlesToClose)

      // Remove closed processes from list (optimistically remove all selected)
      if (result.succeeded > 0) {
        processes.value = processes.value.filter(p => !windowHandlesToClose.includes(p.window_handle))
        windowHandlesToClose.forEach(windowHandle => selectedIds.value.delete(windowHandle))
      }

      if (result.failed > 0) {
        error.value = `Failed to close ${result.failed} process(es)`
      }

      return { success: result.succeeded, failed: result.failed }
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      return { success: 0, failed: windowHandlesToClose.length }
    }
  }

  async function killProcess(pid: number): Promise<boolean> {
    const windowHandlesToRemove = processes.value
      .filter(process => process.pid === pid)
      .map(process => process.window_handle)

    if (windowHandlesToRemove.length === 0) {
      error.value = 'Process not found'
      return false
    }

    try {
      await processApi.killProcess(pid)
      processes.value = processes.value.filter(process => process.pid !== pid)
      windowHandlesToRemove.forEach(windowHandle => selectedIds.value.delete(windowHandle))
      return true
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      return false
    }
  }

  async function killSelected(): Promise<{ success: number; failed: number }> {
    const selectedProcessRows = processes.value.filter(process => selectedIds.value.has(process.window_handle))
    const pidsToKill = Array.from(new Set(selectedProcessRows.map(process => process.pid)))

    if (pidsToKill.length === 0) {
      return { success: 0, failed: 0 }
    }

    try {
      const result = await processApi.killProcesses(pidsToKill)

      if (result.succeeded > 0) {
        processes.value = processes.value.filter(process => !pidsToKill.includes(process.pid))
        selectedIds.value.clear()
      }

      if (result.failed > 0) {
        error.value = `Failed to kill ${result.failed} process(es)`
      }

      return { success: result.succeeded, failed: result.failed }
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      return { success: 0, failed: pidsToKill.length }
    }
  }

  function clearProcesses() {
    processes.value = []
    selectedIds.value.clear()
    currentCategory.value = 'All'
    error.value = null
  }

  function clearError() {
    error.value = null
  }

  return {
    // State
    processes,
    selectedIds,
    currentCategory,
    isRefreshing,
    lastRefreshTime,
    error,
    sortKey,
    sortDirection,
    // Computed
    filteredProcesses,
    selectedCount,
    categoryCounts,
    selectedProcesses,
    // Actions
    refresh,
    toggleSelect,
    selectAll,
    deselectAll,
    invertSelection,
    setCategory,
    setSort,
    closeProcess,
    closeSelected,
    killProcess,
    killSelected,
    clearProcesses,
    clearError
  }
})
