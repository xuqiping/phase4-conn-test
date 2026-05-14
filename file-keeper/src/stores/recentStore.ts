import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { FileItem } from '@/types/file'

export const useRecentStore = defineStore('recent', () => {
  const recentFiles = ref<FileItem[]>([])
  const MAX_RECENT = 10

  function addRecent(file: FileItem) {
    // Remove if already exists
    const existingIndex = recentFiles.value.findIndex(f => f.id === file.id)
    if (existingIndex !== -1) {
      recentFiles.value.splice(existingIndex, 1)
    }
    // Add to front
    recentFiles.value.unshift({ ...file })
    // Trim
    if (recentFiles.value.length > MAX_RECENT) {
      recentFiles.value.pop()
    }
  }

  function clearRecents() {
    recentFiles.value = []
  }

  return { recentFiles, addRecent, clearRecents }
}, {
  persist: {
    key: 'recent',
    paths: ['recentFiles']
  }
})
