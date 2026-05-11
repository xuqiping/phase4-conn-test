import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Group } from '../types/group'
import { v4 as uuidv4 } from 'uuid'

export const useGroupStore = defineStore('group', () => {
  // State
  const groups = ref<Group[]>([
    {
      id: 'all',
      name: '全部',
      order: 0,
    createdAt: Date.now()
    },
    {
      id: 'recent',
      name: '最近打开',
      order: 1,
      createdAt: Date.now()
    }
  ])

  const currentGroupId = ref<string>('all')

  // Getters
  const sortedGroups = computed(() => {
    return [...groups.value].sort((a, b) => a.order - b.order)
  })

  const customGroups = computed(() => {
    return groups.value.filter(g => g.id !== 'all' && g.id !== 'recent')
  })

  const currentGroup = computed(() => {
    return groups.value.find(g => g.id === currentGroupId.value)
  })

  // Actions
  function setCurrentGroup(id: string) {
    currentGroupId.value = id
  }

  function addGroup(name: string, color?: string, icon?: string) {
    const maxOrder = Math.max(...groups.value.map(g => g.order), 1)
    const newGroup: Group = {
      id: uuidv4(),
      name,
      color,
      icon,
      order: maxOrder + 1,
      createdAt: Date.now()
    }
    groups.value.push(newGroup)
    return newGroup
  }

  function removeGroup(id: string) {
    // Prevent removing default groups
    if (id === 'all' || id === 'recent') {
      return false
    }
    const index = groups.value.findIndex(g => g.id === id)
    if (index !== -1) {
      groups.value.splice(index, 1)
      return true
  }
    return false
  }

  function updateGroup(id: string, updates: Partial<Group>) {
    const group = groups.value.find(g => g.id === id)
    if (group) {
      Object.assign(group, updates)
      return true
    }
    return false
  }

  function reorderGroups(newOrder: Group[]) {
    newOrder.forEach((group, index) => {
      const existing = groups.value.find(g => g.id === group.id)
      if (existing) {
        existing.order = index
      }
    })
  }

  function loadGroups(data: Group[]) {
    // Merge with default groups
    const defaultGroups = groups.value.filter(g => g.id === 'all' || g.id === 'recent')
    const customGroups = data.filter(g => g.id !== 'all' && g.id !== 'recent')
    groups.value = [...defaultGroups, ...customGroups]
  }

  return {
    // State
    groups,
    currentGroupId,
    // Getters
    sortedGroups,
    customGroups,
    currentGroup,
    // Actions
    setCurrentGroup,
    addGroup,
    removeGroup,
    updateGroup,
    reorderGroups,
    loadGroups
  }
}, {
  persist: {
    key: 'groups',
    paths: ['groups', 'currentGroupId'],
    importantActions: ['addGroup', 'removeGroup', 'updateGroup', 'setCurrentGroup']
  }
})
