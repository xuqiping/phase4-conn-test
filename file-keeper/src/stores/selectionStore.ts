import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useSelectionStore = defineStore('selection', () => {
  const selectedIds = ref<Set<string>>(new Set())

  const selectedCount = computed(() => selectedIds.value.size)
  const hasSelection = computed(() => selectedIds.value.size > 0)

  function toggleSelection(id: string) {
    if (selectedIds.value.has(id)) {
      selectedIds.value.delete(id)
    } else {
      selectedIds.value.add(id)
    }
    // Trigger reactivity
    selectedIds.value = new Set(selectedIds.value)
  }

  function selectAll(ids: string[]) {
    selectedIds.value = new Set(ids)
  }

  function clearSelection() {
    selectedIds.value.clear()
    selectedIds.value = new Set(selectedIds.value)
  }

  function isSelected(id: string): boolean {
    return selectedIds.value.has(id)
  }

  return {
    selectedIds,
    selectedCount,
    hasSelection,
    toggleSelection,
    selectAll,
    clearSelection,
    isSelected
  }
})
