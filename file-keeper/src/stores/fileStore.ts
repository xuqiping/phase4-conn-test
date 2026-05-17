import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { watchDebounced } from '@vueuse/core'
import type { FileItem } from '../types/file'
import { v4 as uuidv4 } from 'uuid'
import { useGroupStore } from './groupStore'
import { openFile } from '../api/files'
import { getFileIcon } from '../api/icons'
import { deriveIconFromExt } from '../utils/file'

export const useFileStore = defineStore('file', () => {
  const groupStore = useGroupStore()

  // State
  const files = ref<FileItem[]>([
    {
      id: '1',
      name: '2026年度产品规划.docx',
    path: 'C:/Users/Documents/Work',
      type: 'file',
      icon: 'word',
      tags: ['重要', '规划'],
      groupId: 'all',
      openCount: 24,
      lastOpened: Date.now() - 10 * 60 * 1000, // 10分钟前
      createdAt: Date.now() - 30 * 24 * 60 * 60 * 1000
    },
    {
    id: '2',
      name: 'File Keeper UI设计稿.fig',
      path: 'D:/Projects/FileKeeper/Design',
      type: 'file',
      icon: 'design',
      tags: ['设计'],
      groupId: 'all',
      openCount: 56,
      lastOpened: Date.now() - 2 * 60 * 60 * 1000, // 2小时前
      createdAt: Date.now() - 20 * 24 * 60 * 60 * 1000
    },
    {
      id: '3',
      name: 'Q3 财务报表.xlsx',
      path: 'C:/Users/Documents/Finance',
      type: 'file',
      icon: 'excel',
      tags: ['机密'],
      groupId: 'all',
      openCount: 12,
      lastOpened: Date.now() - 24 * 60 * 60 * 1000, // 昨天
      createdAt: Date.now() - 15 * 24 * 60 * 60 * 1000
    },
    {
      id: '4',
      name: '前端架构梳理',
      path: 'D:/Projects/Frontend',
      type: 'folder',
      icon: 'folder',
      tags: [],
      groupId: 'all',
      openCount: 128,
      lastOpened: Date.now() - 3 * 24 * 60 * 60 * 1000, // 3天前
      createdAt: Date.now() - 60 * 24 * 60 * 60 * 1000
    },
    {
      id: '5',
      name: 'App Logo 原型.png',
      path: 'D:/Assets/Images',
      type: 'file',
      icon: 'image',
      tags: ['素材'],
      groupId: 'all',
      openCount: 5,
      lastOpened: Date.now() - 7 * 24 * 60 * 60 * 1000, // 1周前
      createdAt: Date.now() - 45 * 24 * 60 * 60 * 1000
    },
    {
      id: '6',
      name: '核心算法.js',
      path: 'D:/Projects/FileKeeper/Src',
      type: 'file',
      icon: 'code',
    tags: ['代码'],
      groupId: 'all',
      openCount: 42,
      lastOpened: Date.now() - 30 * 1000, // 刚刚
    createdAt: Date.now() - 10 * 24 * 60 * 60 * 1000
    }
  ])
  const searchQuery = ref('')
  const debouncedSearchQuery = ref('')
  const sortBy = ref<'custom' | 'openCount' | 'name' | 'lastOpened' | 'createdAt'>('custom')

  // Watch searchQuery with debounce
  watchDebounced(
    searchQuery,
    (newQuery) => {
      debouncedSearchQuery.value = newQuery
    },
    { debounce: 300 }
  )

  // Getters
  const filteredFiles = computed(() => {
    let result = files.value

    // Filter by group
    if (groupStore.currentGroupId === 'all') {
      // Show all files
    } else if (groupStore.currentGroupId === 'recent') {
      // Show recently opened files (openCount > 20 or opened in last 7 days)
      result = result.filter(f =>
        f.openCount > 20 ||
        (f.lastOpened && Date.now() - f.lastOpened < 7 * 24 * 60 * 60 * 1000)
      )
    } else {
      result = result.filter(f => f.groupId === groupStore.currentGroupId)
    }

    // Sort
    const byName = (a: FileItem, b: FileItem) => a.name.localeCompare(b.name, 'zh')
  if (sortBy.value === 'custom') {
      result = result.slice().sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0))
    } else if (sortBy.value === 'openCount') {
      result = result.slice().sort((a, b) => (b.openCount - a.openCount) || byName(a, b))
    } else if (sortBy.value === 'name') {
      result = result.slice().sort(byName)
    } else if (sortBy.value === 'lastOpened') {
      result = result.slice().sort((a, b) => ((b.lastOpened ?? 0) - (a.lastOpened ?? 0)) || byName(a, b))
    } else if (sortBy.value === 'createdAt') {
      result = result.slice().sort((a, b) => (b.createdAt - a.createdAt) || byName(a, b))
    }

    // Filter by search query
    if (debouncedSearchQuery.value) {
      const query = debouncedSearchQuery.value.toLowerCase()

      // Check if query contains wildcard
      if (query.includes('*')) {
        // Convert wildcard pattern to regex
        const regexPattern = query
          .split('*')
          .map(part => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) // Escape special chars
          .join('.*') // Replace * with .*
        const regex = new RegExp(regexPattern, 'i')

        result = result.filter(f =>
       regex.test(f.name) ||
          regex.test(f.path) ||
        f.tags.some(tag => regex.test(tag))
    )
      } else {
        // Normal substring search
      result = result.filter(f =>
          f.name.toLowerCase().includes(query) ||
          f.path.toLowerCase().includes(query) ||
          f.tags.some(tag => tag.toLowerCase().includes(query))
        )
      }
    }

    return result
  })

  const recentFiles = computed(() => {
    return [...files.value]
      .filter(f => f.lastOpened)
      .sort((a, b) => (b.lastOpened || 0) - (a.lastOpened || 0))
    .slice(0, 10)
  })

  // Actions
  async function addFile(file: Omit<FileItem, 'id' | 'createdAt' | 'openCount' | 'orderIndex'>): Promise<FileItem | null> {
    // Check for duplicate path
    const existing = files.value.find(f => f.path === file.path)
    if (existing) {
      return null
    }

    // Load icon from system
    const iconData = await getFileIcon(file.path)

    // Calculate next orderIndex
    const maxOrderIndex = files.value.reduce((max, f) => Math.max(max, f.orderIndex ?? 0), -1)

    const newFile: FileItem = {
      ...file,
      id: uuidv4(),
      icon: iconData || file.icon || deriveIconFromExt(file.name),
      openCount: 0,
      orderIndex: maxOrderIndex + 1,
      createdAt: Date.now()
    }
    files.value.push(newFile)
    return newFile
  }

  function removeFile(id: string): boolean {
    const index = files.value.findIndex(f => f.id === id)
    if (index === -1) {
      return false
    }

    files.value.splice(index, 1)
    return true
  }

  function updateFile(id: string, updates: Partial<FileItem>) {
    const file = files.value.find(f => f.id === id)
    if (file) {
      Object.assign(file, updates)
    }
  }

  function recordOpen(id: string): void {
    const file = files.value.find(f => f.id === id)
    if (file) {
      file.openCount++
      file.lastOpened = Date.now()
    }
  }

  function setSearchQuery(query: string) {
    searchQuery.value = query
  }

  function loadFiles(data: FileItem[]) {
    // Initialize orderIndex for files that don't have it
    data.forEach((file, idx) => {
      if (file.orderIndex === undefined) {
        file.orderIndex = idx
      }
    })
  files.value = data
  }

  function updateOrder(orderedIds: string[]) {
    const newOrderMap = new Map(orderedIds.map((id, idx) => [id, idx]))
    files.value.forEach(file => {
    if (newOrderMap.has(file.id)) {
     file.orderIndex = newOrderMap.get(file.id)!
      }
    })
  }

  // Move a file to a 1-based position within the current filtered/visible list,
  // keeping the relative order of all other files in that list.
  function moveToPosition(id: string, targetPosition: number): boolean {
    const visible = filteredFiles.value
    const fromIdx = visible.findIndex(f => f.id === id)
    if (fromIdx === -1) return false

    const clamped = Math.max(1, Math.min(targetPosition, visible.length))
    const toIdx = clamped - 1
    if (fromIdx === toIdx) return false

    const reordered = visible.slice()
    const [moved] = reordered.splice(fromIdx, 1)
    reordered.splice(toIdx, 0, moved)

    // Re-stamp orderIndex for the reordered visible set; gap-pack so each one
    // gets a unique value while leaving non-visible files untouched.
    const visibleIds = new Set(visible.map(f => f.id))
    const otherFiles = files.value.filter(f => !visibleIds.has(f.id))
    const otherSorted = otherFiles
      .slice()
      .sort((a, b) => (a.orderIndex ?? 0) - (b.orderIndex ?? 0))
    let next = 0
    const stamp = (f: FileItem) => {
      const target = files.value.find(x => x.id === f.id)
      if (target) target.orderIndex = next++
    }
    // Interleave: keep non-visible files at their original relative ranks,
    // but for simplicity stamp visible-first then others. Both groups get
    // monotonically increasing indices so 'custom' sort is stable.
    reordered.forEach(stamp)
    otherSorted.forEach(stamp)

    return true
  }

  function setSortBy(mode: typeof sortBy.value) {
    sortBy.value = mode
  }

  function batchOpen(ids: string[]) {
    const filesToOpen = files.value.filter(f => ids.includes(f.id))
    filesToOpen.forEach(file => {
      openFile(file.path).catch(err => {
        console.error(`Failed to open ${file.name}:`, err)
      })
      recordOpen(file.id)
    })
  }

  function batchDelete(ids: string[]) {
    ids.forEach(id => removeFile(id))
  }

  function batchMove(ids: string[], targetGroupId: string) {
    ids.forEach(id => {
      updateFile(id, { groupId: targetGroupId })
    })
  }

  function batchAddTags(ids: string[], tags: string[]) {
    ids.forEach(id => {
      const file = files.value.find(f => f.id === id)
      if (file) {
        const newTags = [...new Set([...file.tags, ...tags])]
        updateFile(id, { tags: newTags })
      }
    })
  }

  return {
    // State
    files,
    searchQuery,
    sortBy,
    // Getters
    filteredFiles,
    recentFiles,
    // Actions
    addFile,
    removeFile,
    updateFile,
    recordOpen,
    setSearchQuery,
    setSortBy,
    loadFiles,
    updateOrder,
    moveToPosition,
    batchOpen,
    batchDelete,
    batchMove,
    batchAddTags
  }
}, {
  persist: {
    key: 'files',
    paths: ['files', 'sortBy'],
    importantActions: ['addFile', 'removeFile', 'updateFile']
  }
})
