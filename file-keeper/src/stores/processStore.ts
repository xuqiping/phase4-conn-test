import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { ProcessInfo, ProcessCategory } from '../types/process'
import * as processApi from '../api/process'

export const useProcessStore = defineStore('process', () => {
  // State
  const processes = ref<ProcessInfo[]>([])
  const selectedIds = ref<Set<number>>(new Set())
  const currentCategory = ref<ProcessCategory>('All')
  const isRefreshing = ref(false)
  const lastRefreshTime = ref<number>(0)
  const error = ref<string | null>(null)

  // Computed
  const filteredProcesses = computed(() => {
    if (currentCategory.value === 'All') {
      return processes.value
    }
    return processes.value.filter(p => p.category === currentCategory.value)
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
    return processes.value.filter(p => selectedIds.value.has(p.pid))
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

      // Remove selected IDs that no longer exist
      const currentPids = new Set(result.map(p => p.pid))
      selectedIds.value.forEach(pid => {
        if (!currentPids.has(pid)) {
          selectedIds.value.delete(pid)
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

  function toggleSelect(pid: number) {
    if (selectedIds.value.has(pid)) {
      selectedIds.value.delete(pid)
    } else {
      selectedIds.value.add(pid)
    }
  }

  function selectAll() {
    filteredProcesses.value.forEach(p => {
      selectedIds.value.add(p.pid)
    })
  }

  function deselectAll() {
    selectedIds.value.clear()
  }

  function invertSelection() {
    const newSelection = new Set<number>()
    filteredProcesses.value.forEach(p => {
      if (!selectedIds.value.has(p.pid)) {
        newSelection.add(p.pid)
      }
    })
    selectedIds.value = newSelection
  }

  function setCategory(category: ProcessCategory) {
    currentCategory.value = category
  }

  async function closeProcess(pid: number): Promise<boolean> {
    try {
      const process = processes.value.find(p => p.pid === pid)
      if (!process) {
      error.value = 'Process not found'
    return false
      }

      await processApi.closeProcess(process.window_handle)
      // Remove from list
      processes.value = processes.value.filter(p => p.pid !== pid)
      selectedIds.value.delete(pid)
      return true
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      return false
    }
  }

  async function closeSelected(): Promise<{ success: number; failed: number }> {
    const pidsToClose = Array.from(selectedIds.value)
    if (pidsToClose.length === 0) {
      return { success: 0, failed: 0 }
    }

    try {
      const processesToClose = processes.value.filter(p => pidsToClose.includes(p.pid))
      const windowHandles = processesToClose.map(p => p.window_handle)

      const result = await processApi.closeProcesses(windowHandles)

      // Remove closed processes from list (optimistically remove all selected)
      if (result.succeeded > 0) {
        processes.value = processes.value.filter(p => !pidsToClose.includes(p.pid))
        pidsToClose.forEach(pid => selectedIds.value.delete(pid))
      }

      if (result.failed > 0) {
        error.value = `Failed to close ${result.failed} process(es)`
      }

      return { success: result.succeeded, failed: result.failed }
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      return { success: 0, failed: pidsToClose.length }
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
    closeProcess,
    closeSelected,
    clearProcesses,
    clearError
  }
})
