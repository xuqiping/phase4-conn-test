import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { FileItem } from '../types/file'
import { v4 as uuidv4 } from 'uuid'

export const useFileStore = defineStore('file', () => {
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
  const currentGroupId = ref('all')

  // Getters
  const filteredFiles = computed(() => {
    let result = files.value

    // Filter by group
    if (currentGroupId.value === 'all') {
      // Show all files
    } else if (currentGroupId.value === 'recent') {
      // Show recently opened files (openCount > 20 or opened in last 7 days)
      result = result.filter(f =>
        f.openCount > 20 ||
        (f.lastOpened && Date.now() - f.lastOpened < 7 * 24 * 60 * 60 * 1000)
      )
    } else {
      result = result.filter(f => f.groupId === currentGroupId.value)
    }

    // Filter by search query
    if (searchQuery.value) {
      const query = searchQuery.value.toLowerCase()
      result = result.filter(f =>
        f.name.toLowerCase().includes(query) ||
        f.path.toLowerCase().includes(query) ||
        f.tags.some(tag => tag.toLowerCase().includes(query))
      )
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
  function addFile(file: Omit<FileItem, 'id' | 'createdAt' | 'openCount'>): FileItem | null {
    // Check for duplicate path
    const existing = files.value.find(f => f.path === file.path)
    if (existing) {
      return null
    }

    const newFile: FileItem = {
      ...file,
      id: uuidv4(),
      openCount: 0,
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

  function recordOpen(id: string) {
    const file = files.value.find(f => f.id === id)
    if (file) {
      file.openCount++
      file.lastOpened = Date.now()
    }
  }

  function setSearchQuery(query: string) {
    searchQuery.value = query
  }

  function setCurrentGroup(groupId: string) {
    currentGroupId.value = groupId
  }

  function loadFiles(data: FileItem[]) {
    files.value = data
  }

  return {
    // State
    files,
    searchQuery,
    currentGroupId,
    // Getters
    filteredFiles,
    recentFiles,
    // Actions
    addFile,
    removeFile,
    updateFile,
    recordOpen,
    setSearchQuery,
    setCurrentGroup,
    loadFiles
  }
}, {
  persist: {
    key: 'files',
    paths: ['files'],
    importantActions: ['addFile', 'removeFile', 'updateFile']
  }
})
